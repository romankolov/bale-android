package ai.bale.client.data

import ai.bale.client.domain.*
import ai.bale.client.domain.Peer
import ai.bale.client.network.BaleRpc
import ai.bale.client.network.DirectoryApi
import ai.bale.client.network.FilesApi
import ai.bale.client.network.MessagingApi
import ai.bale.client.protocol.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import com.google.protobuf.Int64Value
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal class DefaultMessengerRepository(
    private val auth: AuthRepository,
    private val socket: BaleRpc,
    private val database: BaleDatabase,
    private val scope: CoroutineScope,
    private val gate: AccountGate,
    private val http: OkHttpClient = OkHttpClient(),
    private val mediaRoot: File? = null,
    private val notifier: MessageNotifier? = null,
    private val tokenProvider: () -> String? = { null },
    private val allowLoopbackHttpForTests: Boolean = false,
) : MessengerRepository {
    private val dao = database.messenger()
    private val refreshLock = Mutex()
    private val sendLock = Mutex()
    private val random = SecureRandom()
    private val dirtyDialogs = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val typing = MutableStateFlow<Map<String, Map<Int, Long>>>(emptyMap())
    private val mutableOnline = MutableStateFlow<Set<Int>>(emptySet())
    private val exPeerTypes = ConcurrentHashMap<String, Int>()
    private val ownAccessHashes = ConcurrentHashMap<Int, Long>()
    override val onlineUsers = mutableOnline.asStateFlow()
    override val connection = socket.state

    private data class ActiveClients(
        val account: Account,
        val messaging: MessagingApi,
        val files: FilesApi,
        val directory: DirectoryApi,
        val token: String?,
    )

    private suspend fun activeSession(): ActiveClients? = gate.mutex.withLock {
        val account = auth.account.value ?: return@withLock null
        val epoch = socket.sessionId
        ActiveClients(
            account = account,
            messaging = MessagingApi(socket, epoch),
            files = FilesApi(socket, http, allowLoopbackHttpForTests, epoch),
            directory = DirectoryApi(socket, epoch),
            token = tokenProvider(),
        )
    }

    private fun recordUserMeta(accountId: Int, user: Users, hashHint: Long = 0L) {
        if (user.id == 0) return
        exPeerTypes["$accountId:1:${user.id}"] = if (user.isBot.value) ExPeerKind.BOT else ExPeerKind.PRIVATE
        val resolvedHash = hashHint.takeIf { it != 0L } ?: user.accessHash
        if (user.id == accountId && resolvedHash != 0L) ownAccessHashes[accountId] = resolvedHash
    }

    private fun recordGroupMeta(accountId: Int, id: Int, groupType: Int) {
        if (id == 0) return
        exPeerTypes["$accountId:2:$id"] = when (groupType) {
            1 -> ExPeerKind.CHANNEL
            2 -> ExPeerKind.SUPERGROUP
            else -> ExPeerKind.GROUP
        }
    }

    private suspend fun resolveExPeerType(session: ActiveClients, peer: Peer): Int {
        val cacheKey = "${session.account.id}:${peer.key}"
        exPeerTypes[cacheKey]?.let { return it }
        if (peer.kind == 2) {
            val full = runCatching { session.directory.fullGroup(peer) }.getOrNull()
            if (full != null && full.id != 0) {
                recordGroupMeta(session.account.id, full.id, full.groupType)
                return exPeerTypes[cacheKey] ?: ExPeerKind.GROUP
            }
        }
        return peer.kind
    }

    init {
        scope.launch {
            connection.collectLatest { if (it == Connection.Connected) safely { refreshConversations() } }
        }
        scope.launch {
            // Do not block the socket-update reducer on one network refresh per event.
            dirtyDialogs.debounce(400).collect { safely { refreshConversations() } }
        }
        scope.launch { socket.updates.collect { event -> safely { applyUpdate(Update.parseFrom(event.payload), event.sessionId) } } }
        scope.launch {
            while (isActive) {
                delay(1_000)
                val now = System.currentTimeMillis()
                typing.update { peers -> peers.mapValues { (_, users) -> users.filterValues { it > now } }.filterValues { it.isNotEmpty() } }
            }
        }
        scope.launch {
            auth.account.collect {
                if (it == null) {
                    typing.value = emptyMap()
                    mutableOnline.value = emptySet()
                    exPeerTypes.clear()
                    ownAccessHashes.clear()
                }
            }
        }
    }

    override fun conversations(): Flow<List<Conversation>> = auth.account.flatMapLatest { account ->
        if (account == null) flowOf(emptyList()) else dao.conversations(account.id).map { it.map(ConversationEntity::domain) }
    }

    override fun messages(peer: Peer): Flow<List<ChatMessage>> = auth.account.flatMapLatest { account ->
        if (account == null) flowOf(emptyList()) else dao.messages(account.id, peer.key).map { it.map { row -> row.domain() } }
    }

    override fun pagedMessages(peer: Peer): Flow<PagingData<ChatMessage>> = auth.account.flatMapLatest { account ->
        if (account == null) flowOf(PagingData.empty()) else Pager(PagingConfig(pageSize = 50, enablePlaceholders = false, maxSize = 300)) {
            dao.pagedMessages(account.id, peer.key)
        }.flow.map { page -> page.map { it.domain() } }
    }

    override fun typingUsers(peer: Peer): Flow<Set<Int>> = combine(auth.account, typing) { account, values ->
        values["${account?.id}:${peer.key}"].orEmpty().keys
    }.distinctUntilChanged()

    override suspend fun refreshConversations() { loadDialogs(-1) }
    override suspend fun loadMoreConversations(): Int {
        val account = auth.account.value ?: return 0
        return loadDialogs(dao.oldestConversation(account.id) ?: -1)
    }

    private suspend fun loadDialogs(before: Long): Int = refreshLock.withLock {
        val session = activeSession() ?: return@withLock 0
        val account = session.account
        val api = session.messaging
        val response = api.dialogs(before)
        val users = response.usersList.associateBy { it.id }.toMutableMap()
        val groups = response.groupsList.associateBy { it.id }.toMutableMap()
        val userHashes = response.userPeersList.associate { it.uid to it.accessHash }
        val groupHashes = response.groupPeersList.associate { it.groupId to it.accessHash }
        val missingUsers = response.dialogsList.filter { it.peer.type == 1 && it.peer.id !in users }
            .map { UserPeers.newBuilder().setUid(it.peer.id).setAccessHash(userHashes[it.peer.id] ?: 0).build() }.distinctBy { it.uid }
        val missingGroups = response.dialogsList.filter { it.peer.type == 2 && it.peer.id !in groups }
            .map { GroupPeer.newBuilder().setGroupId(it.peer.id).setAccessHash(groupHashes[it.peer.id] ?: 0).build() }.distinctBy { it.groupId }
        if (missingUsers.isNotEmpty()) safely { users.putAll(api.users(missingUsers).usersList.associateBy { it.id }) }
        if (missingGroups.isNotEmpty()) safely { groups.putAll(api.groups(missingGroups).groupsList.associateBy { it.id }) }
        users.values.forEach { recordUserMeta(account.id, it, userHashes[it.id] ?: 0L) }
        groups.values.forEach { recordGroupMeta(account.id, it.id, it.groupType) }
        gate.mutex.withLock {
            if (auth.account.value != account) return@withLock
            val rows = response.dialogsList.filter { it.peer.type in 1..2 }.map {
                val p = it.peer
                val key = "${p.type}:${p.id}"
                val previous = dao.conversation(account.id, key)
                val hash = if (p.type == 1) userHashes[p.id] ?: users[p.id]?.accessHash else groupHashes[p.id] ?: groups[p.id]?.accessHash
                val name = if (p.type == 1) users[p.id]?.name else groups[p.id]?.title
                val directText = contentText(it.message)
                val preview = when {
                    directText.isNotBlank() -> directText
                    it.isMessageForwarded -> dao.message(account.id, key, it.rid)?.domain()?.text?.ifBlank { null }
                        ?: previous?.preview?.takeIf(String::isNotBlank)
                        ?: "↪"
                    else -> ""
                }
                ConversationEntity(
                    account.id, key, p.id, p.type, hash ?: previous?.accessHash ?: 0,
                    name ?: previous?.title ?: p.id.toString(), preview,
                    it.sortDate.takeIf { date -> date != 0L } ?: it.date, it.unreadCount,
                )
            }
            dao.conversations(rows)
        }
        response.dialogsCount
    }

    override suspend fun loadOlderHistory(peer: Peer): Int = loadHistory(peer, OLDER)
    override suspend fun loadHistory(peer: Peer, before: Long): Int {
        val session = activeSession() ?: return 0
        val account = session.account
        val cursor = if (before == OLDER) dao.oldestMessage(account.id, peer.key) ?: -1 else before
        val response = session.messaging.history(peer, cursor)
        val userHashes = response.userPeersList.associate { it.uid to it.accessHash }
        response.usersList.forEach { recordUserMeta(account.id, it, userHashes[it.id] ?: 0L) }
        response.groupsList.forEach { recordGroupMeta(account.id, it.id, it.groupType) }
        gate.mutex.withLock {
            if (auth.account.value == account) database.withTransaction {
                response.historyList.forEach { merge(it.entity(account.id, peer.key)) }
            }
        }
        if (response.historyList.any { it.pollMessageOrNull()?.pollId?.let { id -> id != 0L } == true }) {
            safely { hydratePollsForPeer(session, peer.key) }
        }
        return if (cursor > 0 && response.historyList.none { it.date < cursor }) 0 else response.historyCount
    }

    override suspend fun refreshPolls(peer: Peer) {
        val session = activeSession() ?: return
        safely { hydratePollsForPeer(session, peer.key) }
    }

    private suspend fun hydratePollsForPeer(session: ActiveClients, peerKey: String) {
        val account = session.account
        if (auth.account.value != account) return
        val existingRows = dao.messages(account.id, peerKey).first()
        val pollIds = existingRows.asSequence()
            .filter { !it.deleted }
            .mapNotNull { it.history().pollMessageOrNull()?.pollId?.takeIf { id -> id != 0L } }
            .distinct()
            .toList()
        if (pollIds.isEmpty()) return
        val resultsById = mutableMapOf<Long, PollResult>()
        pollIds.chunked(50).forEach { batch ->
            val fetched = session.messaging.pollResults(batch)
            fetched.forEachIndexed { idx, result ->
                val resolvedId = result.pollId.takeIf { it != 0L } ?: batch.getOrNull(idx) ?: return@forEachIndexed
                resultsById[resolvedId] = result
            }
        }
        if (resultsById.isEmpty()) return
        gate.mutex.withLock {
            if (auth.account.value != account) return@withLock
            val currentRows = dao.messages(account.id, peerKey).first()
            val updatedRows = currentRows.mapNotNull { row ->
                if (row.deleted) return@mapNotNull null
                val history = row.history()
                val pollId = history.pollMessageOrNull()?.pollId ?: return@mapNotNull null
                val result = resultsById[pollId] ?: return@mapNotNull null
                val updated = history.withPollResult(result)
                row.copy(metadata = updated.toByteArray())
            }
            if (updatedRows.isNotEmpty()) dao.messages(updatedRows)
        }
    }

    override suspend fun queue(peer: Peer, text: String, reply: ChatMessage?): Long =
        queueMessageContent(peer, MessagingApi.text(text.trim().also { require(it.isNotBlank() && it.length <= 4096) }), reply)

    private suspend fun queueMessageContent(peer: Peer, content: Message2, reply: ChatMessage?): Long = gate.mutex.withLock {
        require(reply == null || (reply.peerKey == peer.key && reply.delivery == Delivery.Sent && !reply.deleted))
        val account = auth.account.value ?: error("No active account")
        val rid = random.nextLong().ushr(2).coerceAtLeast(1)
        val history = History.newBuilder().setRid(rid).setSenderUid(account.id).setDate(System.currentTimeMillis()).setMessage(content)
        if (reply != null) {
            history.setQuotedMessage(
                QuotedMessage.newBuilder().setMessageId(Int64Value.of(reply.rid))
                    .setSenderUserId(reply.senderId).setMessageDate(reply.date)
                    .setQuotedMessageContent(MessagingApi.text(reply.text)),
            )
        }
        dao.messages(listOf(history.build().entity(account.id, peer.key).copy(delivery = Delivery.Pending.name, replySeq = reply?.sequence ?: 0)))
        rid
    }

    override suspend fun retry(peer: Peer, rid: Long) {
        val account = auth.account.value ?: return
        val message = dao.message(account.id, peer.key, rid) ?: return
        if (message.delivery == Delivery.Sent.name || message.deleted) return
        deliver(peer, message)
    }

    private suspend fun deliver(peer: Peer, message: MessageEntity) = sendLock.withLock {
        val session = activeSession() ?: return@withLock
        if (session.account.id != message.accountId) return@withLock
        val latest = dao.message(message.accountId, peer.key, message.rid) ?: return@withLock
        if (latest.delivery == Delivery.Sent.name || latest.deleted || auth.account.value?.id != message.accountId) return@withLock
        val exPeerType = resolveExPeerType(session, peer)
        gate.mutex.withLock { dao.delivery(message.accountId, peer.key, message.rid, Delivery.Pending.name, message.date) }
        try {
            val stored = latest.history()
            val reply = if (stored.hasQuotedMessage()) MessageReference(
                stored.quotedMessage.messageId.value, stored.quotedMessage.messageDate, latest.replySeq,
            ) else null
            val content = if (stored.hasMessage()) stored.message else MessagingApi.text(latest.text)
            val response = session.messaging.send(peer, latest.rid, content, reply, exPeerType)
            gate.mutex.withLock {
                if (auth.account.value?.id == message.accountId) {
                    dao.delivery(message.accountId, peer.key, message.rid, Delivery.Sent.name, response.date.takeIf { it > 0 } ?: message.date)
                }
            }
            dirtyDialogs.tryEmit(Unit)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                gate.mutex.withLock {
                    if (auth.account.value?.id == message.accountId && dao.message(message.accountId, peer.key, message.rid)?.delivery != Delivery.Sent.name) {
                        dao.delivery(message.accountId, peer.key, message.rid, Delivery.Failed.name, message.date)
                    }
                }
            }
            throw error
        }
    }

    override suspend fun sendMedia(peer: Peer, payload: UploadPayload, caption: String, reply: ChatMessage?) {
        val session = activeSession() ?: error("No account")
        val exPeerType = resolveExPeerType(session, peer)
        val thumbInfo = if (payload.kind == AttachmentKind.Photo) generateFastThumbnail(payload.bytes) else FastThumb()
        val enrichedPayload = if (payload.kind == AttachmentKind.Photo && (payload.width <= 0 || payload.height <= 0) &&
            thumbInfo.fullWidth > 0 && thumbInfo.fullHeight > 0
        ) {
            payload.copy(width = thumbInfo.fullWidth, height = thumbInfo.fullHeight)
        } else {
            payload
        }
        val uploaded = session.files.upload(session.account.id, enrichedPayload, peer, session.token, exPeerType)
        val ownerAccessHash = if (exPeerType == ExPeerKind.CHANNEL) peer.id.toLong() else session.account.id.toLong()
        val content = FilesApi.documentMessage(
            uploaded = uploaded,
            payload = enrichedPayload,
            caption = caption,
            accessHash = ownerAccessHash,
            thumbnail = thumbInfo.bytes,
            thumbWidth = thumbInfo.thumbWidth,
            thumbHeight = thumbInfo.thumbHeight,
        )
        val rid = queueMessageContent(peer, content, reply)
        cacheLocalBytes(session.account.id, uploaded.fileId, uploaded.name, payload.bytes)
        retry(peer, rid)
    }

    override suspend fun downloadMedia(attachment: Attachment): String {
        val session = activeSession() ?: error("No account")
        val root = mediaRoot ?: File(System.getProperty("java.io.tmpdir"), "bale-media")
        val target = File(File(root, session.account.id.toString()), "${attachment.fileId}_${FilesApi.sanitizeFileName(attachment.name)}")
        if (attachment.fileId != 0L && target.isFile && target.length() > 0) return target.absolutePath
        if (attachment.fileId == 0L) {
            require(attachment.thumbnail.isNotEmpty()) { "No downloadable payload" }
            withContext(Dispatchers.IO) {
                target.parentFile?.mkdirs()
                target.writeBytes(attachment.thumbnail)
            }
            return target.absolutePath
        }
        val bytes = session.files.download(attachment, session.token)
        return cacheLocalBytes(session.account.id, attachment.fileId, attachment.name, bytes)
    }

    private suspend fun cacheLocalBytes(accountId: Int, fileId: Long, name: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val root = mediaRoot ?: File(System.getProperty("java.io.tmpdir"), "bale-media")
            val dir = File(root, accountId.toString()).apply { mkdirs() }
            val file = File(dir, "${fileId}_${FilesApi.sanitizeFileName(name)}")
            file.writeBytes(bytes)
            file.absolutePath
        }

    override suspend fun markRead(peer: Peer, date: Long) {
        val session = activeSession() ?: return
        if (date <= 0) return
        session.messaging.read(peer, date)
        if (auth.account.value == session.account) dirtyDialogs.tryEmit(Unit)
    }

    override suspend fun edit(peer: Peer, message: ChatMessage, text: String) {
        val session = activeSession() ?: error("No account")
        val account = session.account
        require(message.peerKey == peer.key && message.senderId == account.id && message.attachment == null && message.poll == null && !message.deleted && message.delivery == Delivery.Sent)
        require(text.isNotBlank() && text.length <= 4096)
        session.messaging.edit(peer, message.rid, text.trim())
        gate.mutex.withLock {
            if (auth.account.value == account) dao.message(account.id, peer.key, message.rid)?.let { row ->
                if (!row.deleted) {
                    val history = row.history().toBuilder().setMessage(MessagingApi.text(text.trim())).setEditedAt(Int64Value.of(System.currentTimeMillis())).build()
                    dao.messages(listOf(row.copy(text = text.trim(), metadata = history.toByteArray())))
                }
            }
        }
        dirtyDialogs.tryEmit(Unit)
    }

    override suspend fun delete(peer: Peer, message: ChatMessage, forEveryone: Boolean) {
        val session = activeSession() ?: error("No account")
        val account = session.account
        require(message.peerKey == peer.key)
        if (message.delivery != Delivery.Sent) {
            require(message.delivery == Delivery.Failed) { "Cannot discard an in-flight message" }
            gate.mutex.withLock { if (auth.account.value == account) check(dao.discardPending(account.id, peer.key, message.rid) == 1) }
            return
        }
        require(!message.deleted && (!forEveryone || message.senderId == account.id))
        session.messaging.delete(peer, message.date, forEveryone)
        gate.mutex.withLock {
            if (auth.account.value == account) database.withTransaction {
                dao.tombstones(listOf(MessageTombstone(account.id, peer.key, message.date, message.rid)))
                dao.deleteRid(account.id, peer.key, message.rid)
            }
        }
        dirtyDialogs.tryEmit(Unit)
    }

    override suspend fun forward(from: Peer, to: Peer, message: ChatMessage) {
        val session = activeSession() ?: error("No account")
        require(message.peerKey == from.key && !message.deleted && message.delivery == Delivery.Sent)
        val toExPeerType = resolveExPeerType(session, to)
        session.messaging.forward(from, to, message.reference, random.nextLong().ushr(2).coerceAtLeast(1), toExPeerType)
        dirtyDialogs.tryEmit(Unit)
    }

    override suspend fun react(peer: Peer, message: ChatMessage, code: String, remove: Boolean) {
        val session = activeSession() ?: error("No account")
        val account = session.account
        require(message.peerKey == peer.key && message.delivery == Delivery.Sent && !message.deleted && code.isNotBlank() && code.length <= 32)
        val reactions = session.messaging.react(peer, message.reference, code, remove)
        gate.mutex.withLock { if (auth.account.value == account) replaceReactions(account.id, peer.key, message.rid, reactions) }
    }

    override suspend fun pinMessage(peer: Peer, message: ChatMessage, pin: Boolean) {
        val session = activeSession() ?: error("No account")
        session.messaging.pinMessage(peer, message.reference, pin)
    }

    override suspend fun clearChat(peer: Peer) {
        val session = activeSession() ?: error("No account")
        val account = session.account
        session.messaging.clearChat(peer)
        gate.mutex.withLock {
            if (auth.account.value == account) {
                val existing = dao.messages(account.id, peer.key).first()
                dao.deleteDates(account.id, peer.key, existing.map { it.date })
            }
        }
        dirtyDialogs.tryEmit(Unit)
    }

    override suspend fun deleteChat(peer: Peer) {
        val session = activeSession() ?: error("No account")
        session.messaging.deleteChat(peer)
        dirtyDialogs.tryEmit(Unit)
    }

    override suspend fun setDialogPinned(peer: Peer, pinned: Boolean) {
        val session = activeSession() ?: error("No account")
        session.messaging.setDialogPinned(peer, pinned)
        dirtyDialogs.tryEmit(Unit)
    }

    override suspend fun setDialogArchived(peer: Peer, archived: Boolean) {
        val session = activeSession() ?: error("No account")
        session.messaging.setDialogArchived(peer, archived)
        dirtyDialogs.tryEmit(Unit)
    }

    override suspend fun createPoll(peer: Peer, question: String, options: List<String>, anonymous: Boolean, multipleChoice: Boolean) {
        val cleanQuestion = question.trim()
        val cleanOptions = options.map { it.trim() }.filter { it.isNotBlank() }
        require(cleanQuestion.isNotBlank() && cleanOptions.size >= 2)
        val session = activeSession() ?: error("No account")
        val exPeerType = resolveExPeerType(session, peer)
        val pollId = session.messaging.createPoll(
            peer = peer,
            question = cleanQuestion,
            options = cleanOptions,
            anonymous = anonymous,
            multiple = multipleChoice,
            now = 0L,
            exPeerType = exPeerType,
        )
        val content = Message2.newBuilder()
            .setPollMessage(MessagingApi.pollMessage(pollId, cleanQuestion, cleanOptions, anonymous, multipleChoice))
            .build()
        val rid = queueMessageContent(peer, content, null)
        retry(peer, rid)
    }

    override suspend fun votePoll(peer: Peer, message: ChatMessage, optionIds: List<Int>, retract: Boolean) {
        val poll = message.poll ?: error("Not a poll message")
        val session = activeSession() ?: error("No account")
        val account = session.account
        val alreadyVoted = poll.options.any { it.chosen }
        val result = if (retract || optionIds.isEmpty()) {
            session.messaging.votePoll(poll.pollId, emptyList(), retract = true, now = 0L)
        } else {
            if (alreadyVoted) {
                session.messaging.votePoll(poll.pollId, emptyList(), retract = true, now = 0L)
            }
            session.messaging.votePoll(poll.pollId, optionIds, retract = false, now = 0L)
        }
        gate.mutex.withLock {
            if (auth.account.value == account) dao.message(account.id, peer.key, message.rid)?.let { row ->
                val updatedHistory = row.history().withPollResult(result)
                dao.messages(listOf(row.copy(metadata = updatedHistory.toByteArray())))
            }
        }
    }

    override suspend fun closePoll(peer: Peer, message: ChatMessage) {
        val poll = message.poll ?: error("Not a poll message")
        val session = activeSession() ?: error("No account")
        val account = session.account
        session.messaging.closePoll(poll.pollId)
        val refreshed = runCatching { session.messaging.pollResults(listOf(poll.pollId)).firstOrNull() }.getOrNull()
        gate.mutex.withLock {
            if (auth.account.value == account) dao.message(account.id, peer.key, message.rid)?.let { row ->
                val history = row.history()
                val baseResult = refreshed ?: history.pollMessageOrNull()?.result ?: PollResult.getDefaultInstance()
                val updatedHistory = history.withPollResult(baseResult.toBuilder().setIsClosed(true).build())
                dao.messages(listOf(row.copy(metadata = updatedHistory.toByteArray())))
            }
        }
    }

    override suspend fun pressBotButton(peer: Peer, button: BotButton) {
        val content = when (button.kind) {
            BotButtonKind.Callback -> MessagingApi.templateResponse(button.value.ifBlank { button.label }, button.templateId)
            BotButtonKind.ReplyText -> MessagingApi.text(button.value.ifBlank { button.label })
            BotButtonKind.OpenUrl, BotButtonKind.CopyText -> return
        }
        val rid = queueMessageContent(peer, content, null)
        retry(peer, rid)
    }

    // --- Contacts, Profiles & Communities ---
    override suspend fun contacts(): List<ContactEntry> {
        val session = activeSession() ?: return emptyList()
        val resp = session.directory.contacts()
        val hashes = resp.userPeersList.associate { it.uid to it.accessHash }
        resp.usersList.forEach { recordUserMeta(session.account.id, it, hashes[it.id] ?: 0L) }
        return resp.usersList.map { user ->
            ContactEntry(
                peer = Peer(user.id, 1, hashes[user.id] ?: user.accessHash),
                name = user.name.ifBlank { user.nick.value.ifBlank { user.id.toString() } },
                handle = user.nick.value,
                isBot = user.isBot.value,
                isGroup = false,
            )
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun searchDirectory(query: String): List<ContactEntry> {
        if (query.isBlank()) return emptyList()
        val session = activeSession() ?: return emptyList()
        val resp = session.directory.searchContacts(query)
        val userHashes = resp.userPeersList.associate { it.uid to it.accessHash }
        val groupHashes = resp.groupPeersList.associate { it.groupId to it.accessHash }
        resp.usersList.forEach { recordUserMeta(session.account.id, it, userHashes[it.id] ?: 0L) }
        resp.groupsList.forEach { recordGroupMeta(session.account.id, it.id, it.groupType) }
        val users = resp.usersList.map { user ->
            ContactEntry(
                peer = Peer(user.id, 1, userHashes[user.id] ?: user.accessHash),
                name = user.name.ifBlank { user.nick.value.ifBlank { user.id.toString() } },
                handle = user.nick.value,
                isBot = user.isBot.value,
                isGroup = false,
            )
        }
        val groups = resp.groupsList.map { group ->
            ContactEntry(
                peer = Peer(group.id, 2, groupHashes[group.id] ?: group.accessHash),
                name = group.title.ifBlank { group.nick.value.ifBlank { group.id.toString() } },
                handle = group.nick.value,
                isBot = false,
                isGroup = true,
            )
        }
        return users + groups
    }

    override suspend fun addContact(peer: Peer) {
        val session = activeSession() ?: error("No account")
        session.directory.addContact(peer)
    }

    override suspend fun removeContact(peer: Peer) {
        val session = activeSession() ?: error("No account")
        session.directory.removeContact(peer)
    }

    override suspend fun setBlocked(peer: Peer, blocked: Boolean) {
        val session = activeSession() ?: error("No account")
        session.directory.setBlocked(peer, blocked)
    }

    override suspend fun loadProfile(peer: Peer): PeerProfile {
        val session = activeSession() ?: error("No account")
        val account = session.account
        val pins = runCatching {
            session.messaging.pinnedMessages(peer).map { it.entity(account.id, peer.key).domain() }
        }.getOrDefault(emptyList())
        return if (peer.kind == 1) {
            val full = session.directory.fullUser(peer)
            val resolvedHash = full.accessHash.takeIf { it != 0L } ?: peer.accessHash
            if (peer.id == account.id && resolvedHash != 0L) ownAccessHashes[account.id] = resolvedHash
            exPeerTypes["${account.id}:${peer.key}"] = if (full.isBot.value) ExPeerKind.BOT else ExPeerKind.PRIVATE
            PeerProfile(
                peer = Peer(peer.id, 1, resolvedHash),
                title = full.name.ifBlank { peer.id.toString() },
                handle = full.nick.value,
                about = full.about.value,
                isGroup = false,
                isChannel = false,
                isBot = full.isBot.value,
                isBlocked = full.isBlocked.value,
                pinnedMessages = pins,
            )
        } else {
            val full = session.directory.fullGroup(peer)
            if (full.id != 0) recordGroupMeta(account.id, full.id, full.groupType)
            val rawMembers = runCatching { session.directory.members(peer) }.getOrDefault(full.members17List)
            val userMap = runCatching {
                session.messaging.users(rawMembers.map { UserPeers.newBuilder().setUid(it.uid).build() })
                    .usersList.associateBy { it.id }
            }.getOrDefault(emptyMap())
            val invite = runCatching { session.directory.inviteUrl(peer) }.getOrDefault("")
            PeerProfile(
                peer = Peer(peer.id, 2, full.accessHash.takeIf { it != 0L } ?: peer.accessHash),
                title = full.title.ifBlank { peer.id.toString() },
                handle = full.nick.value,
                about = full.about.value,
                isGroup = true,
                isChannel = full.groupType == 1,
                isMember = if (full.hasIsMember()) full.isMember.value else true,
                isOwner = full.ownerUid == account.id,
                membersCount = full.membersCount10.value.takeIf { it > 0 } ?: rawMembers.size,
                inviteUrl = invite,
                members = rawMembers.map { m ->
                    val u = userMap[m.uid]
                    GroupMember(
                        userId = m.uid,
                        accessHash = u?.accessHash ?: 0L,
                        name = u?.name?.ifBlank { null } ?: m.uid.toString(),
                        isAdmin = m.isAdmin.value || m.uid == full.ownerUid,
                        title = m.title.value,
                    )
                },
                pinnedMessages = pins,
            )
        }
    }

    override suspend fun updateOwnProfile(name: String, handle: String, about: String) {
        val session = activeSession() ?: error("No account")
        session.directory.editProfile(name, handle, about)
    }

    override suspend fun uploadOwnAvatar(payload: UploadPayload) {
        val session = activeSession() ?: error("No account")
        val ownPeer = Peer(session.account.id, 1, ownAccessHashes[session.account.id] ?: 0L)
        val uploaded = session.files.upload(session.account.id, payload, ownPeer, session.token, ExPeerKind.PRIVATE)
        session.directory.editAvatar(uploaded.fileId, session.account.id.toLong())
    }

    override suspend fun createCommunity(title: String, isChannel: Boolean, handle: String, members: List<Peer>): Peer {
        require(title.isNotBlank())
        val session = activeSession() ?: error("No account")
        val group = session.directory.createCommunity(
            random.nextLong().ushr(2).coerceAtLeast(1), title, isChannel, handle, members,
        )
        recordGroupMeta(session.account.id, group.id, if (isChannel) 1 else group.groupType)
        val created = Peer(group.id, 2, group.accessHash)
        gate.mutex.withLock {
            if (auth.account.value == session.account) {
                dao.conversations(listOf(
                    ConversationEntity(session.account.id, created.key, created.id, 2, created.accessHash,
                        group.title.ifBlank { title.trim() }, "", System.currentTimeMillis(), 0),
                ))
            }
        }
        dirtyDialogs.tryEmit(Unit)
        return created
    }

    override suspend fun joinInviteOrHandle(input: String): Peer? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        val session = activeSession() ?: error("No account")
        val joinTokenMatch = Regex("^(?:https?://)?ble\\.ir/join/([A-Za-z0-9_-]+)$").find(trimmed)
        val group = if (joinTokenMatch != null) {
            session.directory.joinByToken(joinTokenMatch.groupValues[1])
        } else {
            val handle = trimmed.removePrefix("https://ble.ir/").removePrefix("http://ble.ir/").removePrefix("ble.ir/").removePrefix("@")
            val found = searchDirectory(handle).firstOrNull() ?: return null
            if (!found.isGroup) return found.peer
            session.directory.joinPublic(found.peer)
        }
        if (group.id == 0) return null
        recordGroupMeta(session.account.id, group.id, group.groupType)
        val joined = Peer(group.id, 2, group.accessHash)
        dirtyDialogs.tryEmit(Unit)
        return joined
    }

    override suspend fun leaveCommunity(peer: Peer) {
        val session = activeSession() ?: error("No account")
        session.directory.leaveGroup(peer, random.nextLong().ushr(2).coerceAtLeast(1))
        dirtyDialogs.tryEmit(Unit)
    }

    override suspend fun editCommunity(peer: Peer, title: String, about: String) {
        val session = activeSession() ?: error("No account")
        session.directory.editCommunity(peer, random.nextLong().ushr(2).coerceAtLeast(1), title, about)
        dirtyDialogs.tryEmit(Unit)
    }

    override suspend fun inviteMember(group: Peer, user: Peer) {
        val session = activeSession() ?: error("No account")
        session.directory.inviteUser(group, user, random.nextLong().ushr(2).coerceAtLeast(1))
    }

    override suspend fun kickMember(group: Peer, user: Peer) {
        val session = activeSession() ?: error("No account")
        session.directory.kickUser(group, user, random.nextLong().ushr(2).coerceAtLeast(1))
    }

    override suspend fun setMemberAdmin(group: Peer, user: Peer, admin: Boolean, title: String) {
        val session = activeSession() ?: error("No account")
        session.directory.setMemberAdmin(group, user, admin, title)
    }

    override suspend fun revokeInviteLink(group: Peer): String {
        val session = activeSession() ?: error("No account")
        return session.directory.revokeInviteUrl(group)
    }

    // --- Stories ---
    override suspend fun loadStories(): List<StoryItem> {
        val session = activeSession() ?: return emptyList()
        val resp = session.directory.allStories()
        val userStories = resp.userStoriesList.filter { !it.isHidden }.map { s ->
            val media = if (s.storyContent.hasMedia() && s.storyContent.media.hasFileLocation()) {
                val loc = s.storyContent.media.fileLocation
                Attachment(
                    fileId = loc.fileId,
                    accessHash = loc.accessHash,
                    storageVersion = if (loc.hasFileStorageVersion()) loc.fileStorageVersion.value else 0,
                    name = "story_${s.id}",
                    mimeType = if (s.storyContent.media.hasVideo()) "video/mp4" else "image/jpeg",
                    sizeBytes = s.storyContent.media.fileSize.toInt(),
                    kind = if (s.storyContent.media.hasVideo()) AttachmentKind.Video else AttachmentKind.Photo,
                    thumbnail = if (s.storyContent.media.hasFastThumb()) s.storyContent.media.fastThumb.thumb.toByteArray() else byteArrayOf(),
                )
            } else null
            StoryItem(
                id = s.id,
                ownerId = s.ownerUserId,
                ownerTitle = "User ${s.ownerUserId}",
                text = if (s.storyContent.hasText()) s.storyContent.text.text else "",
                createdAt = s.createdAt,
                myReaction = s.reaction.value,
                isChannel = false,
                media = media,
            )
        }
        val channelStories = resp.channelStoriesList.filter { !it.isHidden }.map { s ->
            val media = if (s.hasStoryContent() && s.storyContent.hasFileLocation()) {
                val loc = s.storyContent.fileLocation
                Attachment(
                    fileId = loc.fileId,
                    accessHash = loc.accessHash,
                    storageVersion = if (loc.hasFileStorageVersion()) loc.fileStorageVersion.value else 0,
                    name = "story_${s.id}",
                    mimeType = if (s.storyContent.hasVideo()) "video/mp4" else "image/jpeg",
                    sizeBytes = s.storyContent.fileSize.toInt(),
                    kind = if (s.storyContent.hasVideo()) AttachmentKind.Video else AttachmentKind.Photo,
                    thumbnail = if (s.storyContent.hasFastThumb()) s.storyContent.fastThumb.thumb.toByteArray() else byteArrayOf(),
                )
            } else null
            StoryItem(
                id = s.id,
                ownerId = s.exPeer.id.takeIf { it != 0 } ?: s.ownerUserId,
                ownerTitle = "Channel ${s.exPeer.id.takeIf { it != 0 } ?: s.ownerUserId}",
                text = "",
                createdAt = s.createdAt,
                myReaction = s.reaction.value,
                isChannel = true,
                media = media,
            )
        }
        return (userStories + channelStories).sortedByDescending { it.createdAt }
    }

    override suspend fun postTextStory(text: String) {
        require(text.isNotBlank() && text.length <= 500)
        val session = activeSession() ?: error("No account")
        session.directory.addTextStory(text)
    }

    override suspend fun reactStory(storyId: String, emoji: String) {
        val session = activeSession() ?: error("No account")
        session.directory.reactStory(storyId, emoji)
    }

    override suspend fun deleteStory(storyId: String) {
        val session = activeSession() ?: error("No account")
        session.directory.removeStory(storyId)
    }

    override suspend fun searchMessages(query: String, peer: Peer?): SearchPage {
        val session = activeSession() ?: error("No account")
        val account = session.account
        require(query.isNotBlank() && query.length <= 256)
        val response = session.messaging.search(query.trim(), peer)
        if (auth.account.value != account) return SearchPage(emptyList(), 0)
        val users = response.usersList.associateBy { it.id }
        val groups = response.groupsList.associateBy { it.id }
        val hits = response.searchResultsList.mapNotNull { entry ->
            val result = entry.result
            if (result.peer.type !in 1..2) return@mapNotNull null
            val p = result.peer
            val cached = dao.conversation(account.id, "${p.type}:${p.id}")
            val hash = if (p.type == 1) users[p.id]?.accessHash else groups[p.id]?.accessHash
            val title = if (p.type == 1) users[p.id]?.name else groups[p.id]?.title
            val history = if (result.hasMessageContainer()) result.messageContainer else History.newBuilder()
                .setRid(result.rid).setDate(result.date).setSenderUid(result.senderId).setMessage(result.content).build()
            SearchHit(Peer(p.id, p.type, hash ?: cached?.accessHash ?: 0), title ?: cached?.title ?: p.id.toString(), history.entity(account.id, "${p.type}:${p.id}").domain())
        }
        return SearchPage(hits, response.resultCount)
    }

    override suspend fun setTyping(peer: Peer, active: Boolean) {
        val session = activeSession() ?: return
        if (connection.value == Connection.Connected) session.messaging.typing(peer, active)
    }

    private suspend fun applyUpdate(update: Update, session: Long) {
        val account = auth.account.value ?: return
        var notifyTitle: String? = null
        var notifyText: String? = null
        var notifyId = 0
        var incomingPollPeer: Peer? = null
        gate.mutex.withLock {
            if (auth.account.value != account || session != socket.sessionId) return@withLock
            database.withTransaction {
                if (update.hasMessage()) {
                    val m = update.message
                    val key = "${m.peer.type}:${m.peer.id}"
                    val history = History.newBuilder().setRid(m.rid).setDate(m.date).setSenderUid(m.senderUid).setMessage(m.message)
                    if (m.hasQuotedMessage()) history.setQuotedMessage(m.quotedMessage)
                    val built = history.build()
                    merge(built.entity(account.id, key))
                    if (built.pollMessageOrNull()?.pollId?.let { it != 0L } == true && m.peer.type in 1..2) {
                        incomingPollPeer = Peer(m.peer.id, m.peer.type, dao.conversation(account.id, key)?.accessHash ?: 0L)
                    }
                    if (m.senderUid != account.id && !m.isSilent) {
                        notifyTitle = dao.conversation(account.id, key)?.title ?: key
                        notifyText = contentText(effectiveHistoryMessage(built)).ifBlank { "New message" }
                        notifyId = key.hashCode()
                    }
                }
                if (update.hasMessages()) {
                    val m = update.messages
                    val key = "${m.peer.type}:${m.peer.id}"
                    merge(m.field18.entity(account.id, key))
                    if (m.field18.pollMessageOrNull()?.pollId?.let { it != 0L } == true && m.peer.type in 1..2) {
                        incomingPollPeer = Peer(m.peer.id, m.peer.type, dao.conversation(account.id, key)?.accessHash ?: 0L)
                    }
                }
                if (update.hasMessageSent()) {
                    val m = update.messageSent
                    dao.delivery(account.id, "${m.peer.type}:${m.peer.id}", m.rid, Delivery.Sent.name, m.date)
                }
                if (update.hasMessageContentChanged()) {
                    val m = update.messageContentChanged
                    val key = "${m.peer.type}:${m.peer.id}"
                    dao.message(account.id, key, m.rid)?.let { row ->
                        if (!row.deleted) {
                            val content = if (m.hasUpdatedContentTypeMessage()) m.updatedContentTypeMessage else m.message
                            val history = row.history().toBuilder().setMessage(content).setEditedAt(Int64Value.of(System.currentTimeMillis())).build()
                            dao.messages(listOf(row.copy(text = contentText(content), supported = isSupportedMessage(content), metadata = history.toByteArray())))
                        }
                    }
                }
                if (update.hasMessageDelete()) {
                    val m = update.messageDelete
                    val key = "${m.peer.type}:${m.peer.id}"
                    if (m.hasDates()) {
                        val dates = m.dates.datesList.filter { it > 0 }
                        dao.tombstones(dates.map { MessageTombstone(account.id, key, it, 0) })
                        dao.deleteDates(account.id, key, dates)
                    }
                    if (m.hasField34()) {
                        val id = m.field34
                        if (id.date > 0 || id.rid != 0L) dao.tombstones(listOf(MessageTombstone(account.id, key, id.date, id.rid)))
                        if (id.rid != 0L) dao.deleteRid(account.id, key, id.rid)
                        else if (id.date > 0) dao.deleteDates(account.id, key, listOf(id.date))
                    }
                }
                if (update.hasMessageRead()) {
                    val m = update.messageRead
                    dao.receipt(account.id, "${m.peer.type}:${m.peer.id}", m.readDate, true)
                }
                if (update.hasMessageReceived()) {
                    val m = update.messageReceived
                    dao.receipt(account.id, "${m.peer.type}:${m.peer.id}", m.receivedDate, false)
                }
                if (update.hasMessageReadByMe()) {
                    val m = update.messageReadByMe
                    if (m.hasUnreadCounter()) dao.unread(account.id, "${m.peer.type}:${m.peer.id}", m.unreadCounter.value.coerceAtLeast(0))
                }
                if (update.hasReactionsUpdate()) {
                    val m = update.reactionsUpdate
                    replaceReactions(account.id, "${m.peer.type}:${m.peer.id}", m.rid, m.reactionsList)
                }
                if (update.hasMessageReactions()) {
                    val m = update.messageReactions
                    replaceReactions(account.id, "${m.peer.type}:${m.peer.id}", m.rid, m.reactionsList)
                }
            }
        }
        if (session != socket.sessionId || auth.account.value != account) return
        if (notifyTitle != null && notifyText != null) notifier?.show(notifyId, notifyTitle!!, notifyText!!)
        incomingPollPeer?.let { targetPeer -> scope.launch { safely { refreshPolls(targetPeer) } } }
        if (update.hasUserOnline()) mutableOnline.update { it + update.userOnline.uid }
        if (update.hasUserOffline()) mutableOnline.update { it - update.userOffline.uid }
        if (update.hasTyping()) {
            val m = update.typing
            if (m.uid != account.id) typing.update {
                it + ("${account.id}:${m.peer.type}:${m.peer.id}" to (it["${account.id}:${m.peer.type}:${m.peer.id}"].orEmpty() + (m.uid to System.currentTimeMillis() + 6_000)))
            }
        }
        if (update.hasTypingStop()) {
            val m = update.typingStop
            typing.update { it + ("${account.id}:${m.peer.type}:${m.peer.id}" to (it["${account.id}:${m.peer.type}:${m.peer.id}"].orEmpty() - m.uid)) }
        }
        if (update.hasMessage() || update.hasMessages() || update.hasMessageSent() || update.hasMessageContentChanged() ||
            update.hasMessageDelete() || update.hasMessageReadByMe() || update.hasGroupTitleChanged() ||
            update.hasPinnedDialogsChanged() || update.hasDialogArchiveStatus()
        ) dirtyDialogs.tryEmit(Unit)
    }

    private suspend fun replaceReactions(account: Int, key: String, rid: Long, incoming: List<Field264>) {
        dao.message(account, key, rid)?.let { row ->
            if (row.deleted) return
            val valid = incoming.filter { it.code.isNotBlank() && (it.cardinality.value > 0 || it.usersCount > 0) }
            val updated = row.history().toBuilder().clearReactions().addAllReactions(valid).build()
            dao.messages(listOf(row.copy(metadata = updated.toByteArray())))
        }
    }

    private suspend fun merge(incoming: MessageEntity) {
        val old = dao.message(incoming.accountId, incoming.peerKey, incoming.rid)
        if (old?.deleted == true || dao.isDeleted(incoming.accountId, incoming.peerKey, incoming.date, incoming.rid)) return
        val mergedHistory = mergeHistoryPreservingState(old?.history(), incoming.history())
        val effective = effectiveHistoryMessage(mergedHistory)
        val mergedText = incoming.text.ifBlank { contentText(effective) }
        val mergedSupported = incoming.supported || isSupportedMessage(effective)
        dao.messages(listOf(incoming.copy(
            text = mergedText,
            supported = mergedSupported,
            metadata = mergedHistory.toByteArray(),
            received = old?.received ?: false,
            read = old?.read ?: false,
            replySeq = old?.replySeq ?: 0,
        )))
    }

    private data class FastThumb(
        val bytes: ByteArray = byteArrayOf(),
        val thumbWidth: Int = 0,
        val thumbHeight: Int = 0,
        val fullWidth: Int = 0,
        val fullHeight: Int = 0,
    )

    private fun generateFastThumbnail(raw: ByteArray, maxEdge: Int = 90): FastThumb = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val fullW = bounds.outWidth
        val fullH = bounds.outHeight
        if (fullW <= 0 || fullH <= 0) {
            FastThumb()
        } else {
            var sample = 1
            while (fullW / sample > maxEdge * 2 || fullH / sample > maxEdge * 2) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            if (decoded == null) {
                FastThumb(fullWidth = fullW, fullHeight = fullH)
            } else {
                val scale = minOf(1f, maxEdge.toFloat() / maxOf(decoded.width, decoded.height))
                val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
                val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
                val scaled = if (targetW == decoded.width && targetH == decoded.height) decoded else {
                    Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                        if (it !== decoded) decoded.recycle()
                    }
                }
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 35, out)
                scaled.recycle()
                FastThumb(out.toByteArray(), targetW, targetH, fullW, fullH)
            }
        }
    } catch (_: Throwable) {
        FastThumb()
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* user retry/reconnect remains available; no private payload logging */ }
    }

    companion object { const val OLDER = -2L }
}
