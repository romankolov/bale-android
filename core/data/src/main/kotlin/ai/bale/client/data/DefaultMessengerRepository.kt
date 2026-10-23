package ai.bale.client.data

import ai.bale.client.domain.Account
import ai.bale.client.domain.AuthRepository
import ai.bale.client.domain.ChatMessage
import ai.bale.client.domain.Connection
import ai.bale.client.domain.Conversation
import ai.bale.client.domain.Delivery
import ai.bale.client.domain.MessageReference
import ai.bale.client.domain.MessengerRepository
import ai.bale.client.domain.Peer
import ai.bale.client.domain.SearchHit
import ai.bale.client.domain.SearchPage
import ai.bale.client.network.BaleRpc
import ai.bale.client.network.MessagingApi
import ai.bale.client.protocol.*
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
import java.security.SecureRandom

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal class DefaultMessengerRepository(
    private val auth: AuthRepository,
    private val socket: BaleRpc,
    private val database: BaleDatabase,
    scope: CoroutineScope,
    private val gate: AccountGate,
) : MessengerRepository {
    private val dao = database.messenger()
    private suspend fun activeSession(): Pair<Account, MessagingApi>? = gate.mutex.withLock {
        auth.account.value?.let { it to MessagingApi(socket, socket.sessionId) }
    }
    private val refreshLock = Mutex()
    private val sendLock = Mutex()
    private val random = SecureRandom()
    private val dirtyDialogs = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val typing = MutableStateFlow<Map<String, Map<Int, Long>>>(emptyMap())
    override val connection = socket.state

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
        scope.launch { auth.account.collect { if (it == null) typing.value = emptyMap() } }
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
        val (account, api) = activeSession() ?: return@withLock 0
        val response = api.dialogs(before)
        val users = response.usersList.associateBy { it.id }.toMutableMap()
        val groups = response.groupsList.associateBy { it.id }.toMutableMap()
        val userHashes = response.userPeersList.associate { it.uid to it.accessHash }
        val groupHashes = response.groupPeersList.associate { it.groupId to it.accessHash }
        // A page may contain IDs without names. Resolve only missing peers, preserving the page on failure.
        val missingUsers = response.dialogsList.filter { it.peer.type == 1 && it.peer.id !in users }
            .map { UserPeers.newBuilder().setUid(it.peer.id).setAccessHash(userHashes[it.peer.id] ?: 0).build() }.distinctBy { it.uid }
        val missingGroups = response.dialogsList.filter { it.peer.type == 2 && it.peer.id !in groups }
            .map { GroupPeer.newBuilder().setGroupId(it.peer.id).setAccessHash(groupHashes[it.peer.id] ?: 0).build() }.distinctBy { it.groupId }
        if (missingUsers.isNotEmpty()) safely { users.putAll(api.users(missingUsers).usersList.associateBy { it.id }) }
        if (missingGroups.isNotEmpty()) safely { groups.putAll(api.groups(missingGroups).groupsList.associateBy { it.id }) }
        gate.mutex.withLock {
            if (auth.account.value != account) return@withLock
            val rows = response.dialogsList.filter { it.peer.type in 1..2 }.map {
                val p = it.peer
                val previous = dao.conversation(account.id, "${p.type}:${p.id}")
                val hash = if (p.type == 1) userHashes[p.id] ?: users[p.id]?.accessHash else groupHashes[p.id] ?: groups[p.id]?.accessHash
                val name = if (p.type == 1) users[p.id]?.name else groups[p.id]?.title
                ConversationEntity(account.id, "${p.type}:${p.id}", p.id, p.type, hash ?: previous?.accessHash ?: 0,
                    name ?: previous?.title ?: p.id.toString(), contentText(it.message), it.sortDate.takeIf { date -> date != 0L } ?: it.date, it.unreadCount)
            }
            dao.conversations(rows)
        }
        response.dialogsCount
    }
    override suspend fun loadOlderHistory(peer: Peer): Int = loadHistory(peer, OLDER)
    override suspend fun loadHistory(peer: Peer, before: Long): Int {
        val (account, api) = activeSession() ?: return 0
        // -2 is the explicit "continue older" sentinel; -1 remains the documented first page.
        val cursor = if (before == OLDER) dao.oldestMessage(account.id, peer.key) ?: -1 else before
        val response = api.history(peer, cursor)
        gate.mutex.withLock {
            if (auth.account.value == account) database.withTransaction {
                response.historyList.forEach { merge(it.entity(account.id, peer.key)) }
            }
        }
        return if (cursor > 0 && response.historyList.none { it.date < cursor }) 0 else response.historyCount
    }
    override suspend fun queue(peer: Peer, text: String, reply: ChatMessage?): Long = gate.mutex.withLock {
        require(text.isNotBlank() && text.length <= 4096)
        require(reply == null || (reply.peerKey == peer.key && reply.delivery == Delivery.Sent && !reply.deleted))
        val account = auth.account.value ?: error("No active account")
        val rid = random.nextLong().ushr(2).coerceAtLeast(1)
        val history = History.newBuilder().setRid(rid).setSenderUid(account.id).setDate(System.currentTimeMillis())
            .setMessage(MessagingApi.text(text.trim()))
        if (reply != null) history.setQuotedMessage(QuotedMessage.newBuilder().setMessageId(Int64Value.of(reply.rid))
            .setSenderUserId(reply.senderId).setMessageDate(reply.date).setQuotedMessageContent(MessagingApi.text(reply.text)))
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
        val (account, api) = activeSession() ?: return@withLock
        if (account.id != message.accountId) return@withLock
        val latest = dao.message(message.accountId, peer.key, message.rid) ?: return@withLock
        if (latest.delivery == Delivery.Sent.name || latest.deleted || auth.account.value?.id != message.accountId) return@withLock
        gate.mutex.withLock { dao.delivery(message.accountId, peer.key, message.rid, Delivery.Pending.name, message.date) }
        try {
            val stored = latest.history()
            val reply = if (stored.hasQuotedMessage()) MessageReference(stored.quotedMessage.messageId.value,
                stored.quotedMessage.messageDate, latest.replySeq) else null
            val content = if (stored.hasMessage()) stored.message else MessagingApi.text(latest.text)
            val response = api.send(peer, latest.rid, content, reply)
            gate.mutex.withLock {
                if (auth.account.value?.id == message.accountId) dao.delivery(message.accountId, peer.key, message.rid,
                    Delivery.Sent.name, response.date.takeIf { it > 0 } ?: message.date)
            }
            dirtyDialogs.tryEmit(Unit)
        } catch (error: Exception) {
            withContext(NonCancellable) { gate.mutex.withLock {
                if (auth.account.value?.id == message.accountId && dao.message(message.accountId, peer.key, message.rid)?.delivery != Delivery.Sent.name)
                    dao.delivery(message.accountId, peer.key, message.rid, Delivery.Failed.name, message.date)
            } }
            throw error
        }
    }
    override suspend fun markRead(peer: Peer, date: Long) {
        val (account, api) = activeSession() ?: return
        if (date <= 0) return
        api.read(peer, date)
        if (auth.account.value == account) dirtyDialogs.tryEmit(Unit)
    }
    override suspend fun edit(peer: Peer, message: ChatMessage, text: String) {
        val (account, api) = activeSession() ?: error("No account")
        require(message.peerKey == peer.key && message.senderId == account.id && message.supported && !message.deleted && message.delivery == Delivery.Sent)
        require(text.isNotBlank() && text.length <= 4096)
        api.edit(peer, message.rid, text.trim())
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
        val (account, api) = activeSession() ?: error("No account")
        require(message.peerKey == peer.key)
        if (message.delivery != Delivery.Sent) {
            require(message.delivery == Delivery.Failed) { "Cannot discard an in-flight message" }
            gate.mutex.withLock { if (auth.account.value == account) check(dao.discardPending(account.id, peer.key, message.rid) == 1) }
            return
        }
        require(!message.deleted && (!forEveryone || message.senderId == account.id))
        api.delete(peer, message.date, forEveryone)
        gate.mutex.withLock { if (auth.account.value == account) database.withTransaction {
            dao.tombstones(listOf(MessageTombstone(account.id, peer.key, message.date, message.rid)))
            dao.deleteRid(account.id, peer.key, message.rid)
        } }
        dirtyDialogs.tryEmit(Unit)
    }
    override suspend fun forward(from: Peer, to: Peer, message: ChatMessage) {
        val (_, api) = activeSession() ?: error("No account")
        require(message.peerKey == from.key && !message.deleted && message.delivery == Delivery.Sent)
        api.forward(from, to, message.reference, random.nextLong().ushr(2).coerceAtLeast(1))
        dirtyDialogs.tryEmit(Unit)
    }
    override suspend fun react(peer: Peer, message: ChatMessage, code: String, remove: Boolean) {
        val (account, api) = activeSession() ?: error("No account")
        require(message.peerKey == peer.key && message.delivery == Delivery.Sent && !message.deleted && code.isNotBlank() && code.length <= 32)
        val reaction = api.react(peer, message.reference, code, remove)
        gate.mutex.withLock { if (auth.account.value == account && reaction != null) updateReaction(account.id, peer.key, message.rid, reaction) }
    }
    override suspend fun searchMessages(query: String, peer: Peer?): SearchPage {
        val (account, api) = activeSession() ?: error("No account")
        require(query.isNotBlank() && query.length <= 256)
        val response = api.search(query.trim(), peer)
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
        val (_, api) = activeSession() ?: return
        if (connection.value == Connection.Connected) api.typing(peer, active)
    }

    private suspend fun applyUpdate(update: Update, session: Long) {
        val account = auth.account.value ?: return
        gate.mutex.withLock {
            if (auth.account.value != account || session != socket.sessionId) return@withLock
            database.withTransaction {
                if (update.hasMessage()) {
                    val m = update.message
                    val history = History.newBuilder().setRid(m.rid).setDate(m.date).setSenderUid(m.senderUid).setMessage(m.message)
                    if (m.hasQuotedMessage()) history.setQuotedMessage(m.quotedMessage)
                    merge(history.build().entity(account.id, "${m.peer.type}:${m.peer.id}"))
                }
                if (update.hasMessages()) {
                    val m = update.messages
                    merge(m.field18.entity(account.id, "${m.peer.type}:${m.peer.id}"))
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
                            dao.messages(listOf(row.copy(text = contentText(content), supported = content.hasTextMessage(), metadata = history.toByteArray())))
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
                    if (m.hasField26()) updateReaction(account.id, "${m.peer.type}:${m.peer.id}", m.rid, m.field26)
                }
            }
        }
        if (session != socket.sessionId || auth.account.value != account) return
        if (update.hasTyping()) {
            val m = update.typing
            if (m.uid != account.id) typing.update { it + ("${account.id}:${m.peer.type}:${m.peer.id}" to (it["${account.id}:${m.peer.type}:${m.peer.id}"].orEmpty() + (m.uid to System.currentTimeMillis() + 6_000))) }
        }
        if (update.hasTypingStop()) {
            val m = update.typingStop
            typing.update { it + ("${account.id}:${m.peer.type}:${m.peer.id}" to (it["${account.id}:${m.peer.type}:${m.peer.id}"].orEmpty() - m.uid)) }
        }
        if (update.hasMessage() || update.hasMessages() || update.hasMessageSent() || update.hasMessageContentChanged() ||
            update.hasMessageDelete() || update.hasMessageReadByMe()) dirtyDialogs.tryEmit(Unit)
    }
    private suspend fun updateReaction(account: Int, key: String, rid: Long, reaction: Field264) {
        if (reaction.code.isBlank()) return
        dao.message(account, key, rid)?.let { row ->
            if (row.deleted) return
            val history = row.history()
            val reactions = history.reactionsList.filter { it.code != reaction.code }.toMutableList()
            if (reaction.cardinality.value > 0) reactions += reaction
            dao.messages(listOf(row.copy(metadata = history.toBuilder().clearReactions().addAllReactions(reactions).build().toByteArray())))
        }
    }
    private suspend fun merge(incoming: MessageEntity) {
        val old = dao.message(incoming.accountId, incoming.peerKey, incoming.rid)
        if (old?.deleted == true || dao.isDeleted(incoming.accountId, incoming.peerKey, incoming.date, incoming.rid)) return
        val metadata = incoming.history().toBuilder()
        if (old != null && !metadata.hasQuotedMessage() && old.history().hasQuotedMessage()) metadata.setQuotedMessage(old.history().quotedMessage)
        dao.messages(listOf(incoming.copy(metadata = metadata.build().toByteArray(), received = old?.received ?: false,
            read = old?.read ?: false, replySeq = old?.replySeq ?: 0)))
    }
    private suspend fun safely(block: suspend () -> Unit) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* user retry/reconnect remains available; no private payload logging */ }
    }
    companion object { const val OLDER = -2L }
}
