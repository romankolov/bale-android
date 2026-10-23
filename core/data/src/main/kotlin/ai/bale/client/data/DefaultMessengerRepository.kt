package ai.bale.client.data

import ai.bale.client.domain.*
import ai.bale.client.domain.Peer
import ai.bale.client.network.BaleRpc
import ai.bale.client.network.DirectoryApi
import ai.bale.client.network.FilesApi
import ai.bale.client.network.MessagingApi
import ai.bale.client.network.MessagingApi.Companion.exWire
import ai.bale.client.network.PresenceApi
import ai.bale.client.network.RichFeaturesApi
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
import java.util.Collections
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
    private val preferencesStore: ClientPreferencesStore = ClientPreferencesStore(),
) : MessengerRepository {
    private val dao = database.messenger()
    private val refreshLock = Mutex()
    private val sendLock = Mutex()
    private val random = SecureRandom()
    private val dirtyDialogs = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val typing = MutableStateFlow<Map<String, Map<Int, Long>>>(emptyMap())
    private val mutableOnline = MutableStateFlow<Set<Int>>(emptySet())
    private val mutablePresence = MutableStateFlow<Map<Int, PeerPresence>>(emptyMap())
    private val mutableGroupOnline = MutableStateFlow<Map<Int, Int>>(emptyMap())
    private val mutableAvatarPaths = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val mutableFolders = MutableStateFlow<List<ChatFolder>>(emptyList())
    private val peerMetaVersion = MutableStateFlow(0L)
    private val exPeerTypes = ConcurrentHashMap<String, Int>()
    private val ownAccessHashes = ConcurrentHashMap<Int, Long>()
    private val peerAvatars = ConcurrentHashMap<String, AvatarRef>()
    private val userNames = ConcurrentHashMap<String, String>()
    private val groupTitles = ConcurrentHashMap<String, String>()
    private val pendingSenders = MutableSharedFlow<Int>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val presenceSubscribed = ConcurrentHashMap<String, Int>()
    private val pendingSenderIds: MutableSet<Int> = Collections.newSetFromMap(ConcurrentHashMap())
    private val mutableLastPushAt = MutableStateFlow(0L)
    override val lastPushAt: StateFlow<Long> = mutableLastPushAt.asStateFlow()
    private var onlineAnnouncedSession = 0L
    @Volatile private var foregroundPeer: Peer? = null

    override fun noteForegroundPeer(peer: Peer?) {
        foregroundPeer = peer
    }
    private val inFlightAvatars: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())

    override val onlineUsers = mutableOnline.asStateFlow()
    override val peerPresence: StateFlow<Map<Int, PeerPresence>> = mutablePresence.asStateFlow()
    override val groupOnlineCounts: StateFlow<Map<Int, Int>> = mutableGroupOnline.asStateFlow()
    override val connection = socket.state
    override val preferences: StateFlow<ClientPreferences> = preferencesStore.preferences
    override val avatarPaths: StateFlow<Map<Long, String>> = mutableAvatarPaths.asStateFlow()
    override val folders: StateFlow<List<ChatFolder>> = mutableFolders.asStateFlow()

    private data class ActiveClients(
        val account: Account,
        val messaging: MessagingApi,
        val files: FilesApi,
        val directory: DirectoryApi,
        val rich: RichFeaturesApi,
        val presence: PresenceApi,
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
            rich = RichFeaturesApi(socket, epoch),
            presence = PresenceApi(socket, epoch),
            token = tokenProvider(),
        )
    }

    // --- Peer metadata caches used for avatars, sender names and ExPeer routing ---
    private fun recordUserMeta(accountId: Int, user: Users, hashHint: Long = 0L) {
        if (user.id == 0) return
        var changed = false
        val cacheKey = "$accountId:1:${user.id}"
        val nextType = if (user.isBot.value) ExPeerKind.BOT else ExPeerKind.PRIVATE
        if (exPeerTypes.put(cacheKey, nextType) != nextType) changed = true
        val resolvedHash = hashHint.takeIf { it != 0L } ?: user.accessHash
        if (user.id == accountId && resolvedHash != 0L) ownAccessHashes[accountId] = resolvedHash
        val name = user.name.ifBlank { user.nick.value }
        if (name.isNotBlank() && userNames.put("$accountId:${user.id}", name) != name) changed = true
        if (user.hasAvatar()) {
            user.avatar.toRefOrNull()?.let { ref -> if (peerAvatars.put(cacheKey, ref) != ref) changed = true }
        }
        if (changed) peerMetaVersion.update { it + 1 }
    }

    private fun recordGroupMeta(accountId: Int, group: Groups2) {
        if (group.id == 0) return
        recordGroupType(accountId, group.id, group.groupType)
        if (group.title.isNotBlank() && groupTitles.put("$accountId:${group.id}", group.title) != group.title) {
            peerMetaVersion.update { it + 1 }
        }
        if (group.hasAvatar()) {
            group.avatar.toRefOrNull()?.let { ref ->
                if (peerAvatars.put("$accountId:2:${group.id}", ref) != ref) peerMetaVersion.update { it + 1 }
            }
        }
    }

    private fun recordGroupType(accountId: Int, id: Int, groupType: Int) {
        if (id == 0) return
        if (exPeerTypes.put("$accountId:2:$id", groupType.exType()) != groupType.exType()) {
            peerMetaVersion.update { it + 1 }
        }
    }

    private fun Int.exType(): Int = when (this) {
        1 -> ExPeerKind.CHANNEL
        2 -> ExPeerKind.SUPERGROUP
        else -> ExPeerKind.GROUP
    }

    private suspend fun hydrateUsers(session: ActiveClients, userIds: List<Int>) {
        val missing = userIds.filter { it != 0 && !userNames.containsKey("${session.account.id}:${it}") }.distinct()
        if (missing.isEmpty()) return
        safely {
            // The web client resolves peers in bounded pages; oversized batches are truncated server-side.
            missing.chunked(100).forEach { chunk ->
                val response = session.messaging.users(chunk.map { UserPeers.newBuilder().setUid(it).build() })
                response.usersList.forEach { recordUserMeta(session.account.id, it) }
            }
        }
    }

    /** Pull presence for user peers, cache it and subscribe to live online/offline pushes. */
    private suspend fun refreshPresence(session: ActiveClients, userIds: List<Int>, hashOf: (Int) -> Long = { 0L }) {
        val targets = userIds.filter { it != 0 && it != session.account.id }.distinct().take(150)
        if (targets.isEmpty()) return
        safely {
            val response = session.presence.usersPresence(targets, subscribe = true)
            val online = mutableSetOf<Int>()
            val next = mutablePresence.value.toMutableMap()
            response.presencesList.forEach { entry ->
                when {
                    entry.hasUserOnline() -> {
                        val peer = entry.userOnline.peer
                        if (peer.uid != 0) {
                            online += peer.uid
                            val previous = next[peer.uid]
                            next[peer.uid] = (previous ?: PeerPresence()).copy(online = true, lastSeenUnknown = false)
                        }
                    }
                    entry.hasUserOffline() -> {
                        val peer = entry.userOffline.peer
                        if (peer.uid != 0) {
                            val date = if (entry.userOffline.hasDate()) entry.userOffline.date.value else 0L
                            val unknown = entry.userOffline.userLastSeenUnknown.value
                            next[peer.uid] = PeerPresence(
                                online = false,
                                lastSeenEpochSec = date,
                                lastSeenUnknown = unknown,
                                unknownLevel = if (unknown) entry.userOffline.unknownLastSeenValue else 0,
                            )
                        }
                    }
                }
            }
            if (next.isNotEmpty()) mutablePresence.update { current -> current + next.filterKeys { it in targets } }
            mutableOnline.update { ids -> ids + online }
        }
        val subscriptionEpoch = "${session.account.id}:${socket.sessionId}"
        val fresh = targets.filter { presenceSubscribed.putIfAbsent("$subscriptionEpoch:$it", 1) == null }
        if (fresh.isNotEmpty()) {
            safely {
                session.presence.subscribeFromOnline(fresh.mapNotNull { uid -> PresenceApi.userPeer(Peer(uid, 1, hashOf(uid))) })
            }
        }
    }

    private suspend fun resolveExPeerType(session: ActiveClients, peer: Peer): Int {
        val cacheKey = "${session.account.id}:${peer.key}"
        exPeerTypes[cacheKey]?.let { return it }
        if (peer.kind == 2) {
            val full = runCatching { session.directory.fullGroup(peer) }.getOrNull()
            if (full != null && full.id != 0) {
                recordGroupType(session.account.id, full.id, full.groupType)
                if (full.hasAvatar()) peerAvatars[cacheKey] = full.avatar.toRefOrNull() ?: peerAvatars[cacheKey] ?: AvatarRef(0, 0)
                return exPeerTypes[cacheKey] ?: ExPeerKind.GROUP
            }
        }
        return peer.kind
    }

    private fun enrichConversation(account: Account, row: ConversationEntity, overlay: AccountUiOverlay): Conversation {
        val cacheKey = "${account.id}:${row.peerKey}"
        val exType = exPeerTypes[cacheKey] ?: row.kind
        val saved = row.kind == 1 && row.peerId == account.id
        // Prefer resolved profile names over the numeric placeholder that may have been stored
        // before Users/LoadUsers answered; the list re-emits whenever the metadata cache changes.
        val resolved = if (row.kind == 1) userNames["${account.id}:${row.peerId}"] else groupTitles["${account.id}:${row.peerId}"]
        val staleIdTitle = row.title.isBlank() || row.title == row.peerId.toString()
        return row.domain().copy(
            title = if (saved) "Saved Messages" else resolved?.takeIf { it.isNotBlank() } ?: if (staleIdTitle) row.peerId.toString() else row.title,
            pinned = row.peerKey in overlay.pinnedPeers,
            archived = row.peerKey in overlay.archivedPeers,
            muted = row.peerKey in overlay.mutedPeers,
            markedUnread = row.peerKey in overlay.markedUnreadPeers,
            unreadMentions = overlay.unreadMentions[row.peerKey] ?: 0,
            draft = overlay.drafts[row.peerKey].orEmpty(),
            isChannel = exType == ExPeerKind.CHANNEL || exType == ExPeerKind.SUPERGROUP,
            isBot = exType == ExPeerKind.BOT,
            isSavedMessages = saved,
            avatar = peerAvatars[cacheKey],
        )
    }

    /**
     * Live bump of the dialog row when a message is merged from the socket: recency, preview and
     * (for incoming senders only) the unread counter. Peers that are not in the local list yet get
     * a minimal row so the chat appears immediately instead of after the next manual refresh.
     */
    private suspend fun bumpConversation(account: Account, peerType: Int, peerId: Int, date: Long, history: History, incoming: Boolean) {
        if (peerId == 0) return
        val kind = if (peerType == 1) 1 else 2
        val key = "$kind:$peerId"
        val preview = contentText(effectiveHistoryMessage(history))
        val touched = dao.bumpOnMessage(account.id, key, date, preview, if (incoming) 1 else 0)
        if (touched > 0) return
        if (!incoming) return
        dao.conversations(
            listOf(
                ConversationEntity(
                    account.id, key, peerId, kind, 0L,
                    (if (kind == 1) userNames["${account.id}:$peerId"] else groupTitles["${account.id}:$peerId"]) ?: peerId.toString(),
                    preview, date, 1,
                ),
            ),
        )
    }

    private fun enrichMessage(account: Account, message: ChatMessage): ChatMessage {
        val own = message.senderId == account.id
        return message.copy(
            senderName = if (own) account.name else userNames["${account.id}:${message.senderId}"].orEmpty(),
            senderAvatar = if (own) peerAvatars["${account.id}:1:${account.id}"] else peerAvatars["${account.id}:1:${message.senderId}"],
        )
    }

    init {
        scope.launch {
            connection.collectLatest { state ->
                if (state == Connection.Connected) {
                    mutableLastPushAt.value = System.currentTimeMillis()
                    safely { refreshConversations() }
                    // Announce this client online once per socket epoch so peers see us (and we get pushes).
                    val epoch = socket.sessionId
                    if (epoch != 0L && onlineAnnouncedSession != epoch) {
                        onlineAnnouncedSession = epoch
                        activeSession()?.let { session -> safely { session.presence.setOnline(true) } }
                    }
                }
            }
        }
        scope.launch {
            // Senders observed only through pushed updates need the same name/avatar hydration the
            // dialog loader performs; batch them instead of firing one RPC per message.
            pendingSenders.debounce(600).collect {
                val account = auth.account.value ?: return@collect
                val missing = pendingSenderIds.filter { uid -> !userNames.containsKey("${account.id}:$uid") }.toList()
                pendingSenderIds.clear()
                if (missing.isNotEmpty()) activeSession()?.let { session ->
                    safely { hydrateUsers(session, missing) }
                    safely { refreshPresence(session, missing) }
                }
            }
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
                typing.update { peers -> peers.mapValues { (_, at) -> at.filterValues { it > now } }.filterValues { it.isNotEmpty() } }
            }
        }
        scope.launch {
            // Push-staleness watchdog: the socket should keep lastPushAt moving. If it has been
            // quiet while still reporting Connected, re-poll the dialogs and the open chat once per
            // window so the list never depends on a manual pull-to-refresh.
            while (isActive) {
                delay(10_000)
                if (socket.state.value != Connection.Connected) continue
                if (System.currentTimeMillis() - mutableLastPushAt.value <= PUSH_STALE_MS) continue
                mutableLastPushAt.value = System.currentTimeMillis()
                val foreground = foregroundPeer
                safely { refreshConversations() }
                if (foreground != null) safely { loadHistory(foreground) }
            }
        }
        scope.launch {
            var previousAccountId: Int? = null
            auth.account.collect { current ->
                if (current == null) {
                    previousAccountId?.let { accountId ->
                        preferencesStore.clearAccount(accountId)
                        exPeerTypes.keys.removeAll { it.startsWith("$accountId:") }
                        peerAvatars.keys.removeAll { it.startsWith("$accountId:") }
                        userNames.keys.removeAll { it.startsWith("$accountId:") }
                    }
                    typing.value = emptyMap()
                    mutableOnline.value = emptySet()
                    mutableFolders.value = emptyList()
                    mutablePresence.value = emptyMap()
                    mutableGroupOnline.value = emptyMap()
                    pendingSenderIds.clear()
                    presenceSubscribed.keys.removeAll { it.startsWith("$previousAccountId:") }
                    groupTitles.keys.removeAll { it.startsWith("$previousAccountId:") }
                    ownAccessHashes.clear()
                    peerMetaVersion.update { it + 1 }
                }
                previousAccountId = current?.id
            }
        }
    }

    // --- Reactive reads ---
    override fun conversations(): Flow<List<Conversation>> = auth.account.flatMapLatest { account ->
        if (account == null) {
            flowOf(emptyList())
        } else {
            combine(dao.conversations(account.id), preferencesStore.overlays, peerMetaVersion) { rows, overlays, _ ->
                val overlay = overlays[account.id] ?: preferencesStore.overlayFor(account.id)
                rows.map { enrichConversation(account, it, overlay) }
                    .sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.date })
            }
        }
    }

    override fun messages(peer: Peer): Flow<List<ChatMessage>> = auth.account.flatMapLatest { account ->
        if (account == null) {
            flowOf(emptyList())
        } else {
            combine(dao.messages(account.id, peer.key), peerMetaVersion) { rows, _ ->
                rows.map { enrichMessage(account, it.domain()) }
            }
        }
    }

    override fun pagedMessages(peer: Peer): Flow<PagingData<ChatMessage>> = auth.account.flatMapLatest { account ->
        if (account == null) flowOf(PagingData.empty()) else Pager(PagingConfig(pageSize = 50, enablePlaceholders = false, maxSize = 300)) {
            dao.pagedMessages(account.id, peer.key)
        }.flow.map { page -> page.map { enrichMessage(account, it.domain()) } }
    }

    override fun typingUsers(peer: Peer): Flow<Set<Int>> = combine(auth.account, typing) { account, values ->
        values["${account?.id}:${peer.key}"].orEmpty().keys
    }.distinctUntilChanged()

    // --- Preferences & avatars ---
    override suspend fun updatePreferences(transform: (ClientPreferences) -> ClientPreferences) {
        preferencesStore.updatePreferences(transform)
    }

    override suspend fun ensureAvatar(avatar: AvatarRef?) {
        if (avatar == null || avatar.fileId == 0L) return
        if (avatar.fileId in mutableAvatarPaths.value || !inFlightAvatars.add(avatar.fileId)) return
        try {
            val cached = avatarCacheFile(avatar.fileId)
            if (cached.isFile && cached.length() > 0) {
                mutableAvatarPaths.update { it + (avatar.fileId to cached.absolutePath) }
                return
            }
            val session = activeSession() ?: return
            val bytes = session.files.download(
                Attachment(
                    fileId = avatar.fileId,
                    accessHash = avatar.accessHash,
                    name = "avatar_${avatar.fileId}.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 0,
                    kind = AttachmentKind.Photo,
                ),
                session.token,
            )
            if (bytes.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    cached.parentFile?.mkdirs()
                    cached.writeBytes(bytes)
                }
                mutableAvatarPaths.update { it + (avatar.fileId to cached.absolutePath) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Avatar failures degrade to deterministic initials instead of failing the screen.
        } finally {
            inFlightAvatars.remove(avatar.fileId)
        }
    }

    private fun avatarCacheFile(fileId: Long): File {
        val root = mediaRoot ?: File(System.getProperty("java.io.tmpdir"), "bale-media")
        return File(File(root, "avatars"), "$fileId.bin")
    }

    // --- Conversation list, folders and dialog flags ---
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
        // A peer can be "present" in the inline lists as a bare {id} stub; treat blank display
        // names as missing so dialog rows never keep numeric placeholders after hydration.
        val missingUsers = response.dialogsList
            .filter { it.peer.type == 1 && users[it.peer.id]?.name.isNullOrBlank() && userNames["${account.id}:${it.peer.id}"].isNullOrBlank() }
            .map { UserPeers.newBuilder().setUid(it.peer.id).setAccessHash(userHashes[it.peer.id] ?: 0).build() }
            .distinctBy { it.uid }
        val missingGroups = response.dialogsList
            .filter { it.peer.type == 2 && groups[it.peer.id]?.title.isNullOrBlank() }
            .map { GroupPeer.newBuilder().setGroupId(it.peer.id).setAccessHash(groupHashes[it.peer.id] ?: 0).build() }
            .distinctBy { it.groupId }
        missingUsers.chunked(METADATA_PAGE_SIZE).forEach { chunk ->
            if (chunk.isNotEmpty()) safely { users.putAll(api.users(chunk).usersList.associateBy { it.id }) }
        }
        missingGroups.chunked(METADATA_PAGE_SIZE).forEach { chunk ->
            if (chunk.isNotEmpty()) safely { groups.putAll(api.groups(chunk).groupsList.associateBy { it.id }) }
        }
        users.values.forEach { recordUserMeta(account.id, it, userHashes[it.id] ?: 0L) }
        groups.values.forEach { recordGroupMeta(account.id, it) }

        val serverMuted = mutableSetOf<String>()
        val serverUnread = mutableSetOf<String>()
        val serverMentions = mutableMapOf<String, Int>()
        // Authoritative pinned snapshot; null means the fetch failed and local state stays untouched.
        val serverPinned = runCatching { api.pinnedDialogs() }.getOrNull()
            ?.filter { it.peer.type in 1..2 }
            ?.map { "${it.peer.type}:${it.peer.id}" }
            ?.toSet()
        gate.mutex.withLock {
            if (auth.account.value != account) return@withLock
            val rows = response.dialogsList.filter { it.peer.type in 1..2 }.map { dialog ->
                val p = dialog.peer
                val key = "${p.type}:${p.id}"
                if (dialog.isMute) serverMuted += key
                if (dialog.markedAsUnread) serverUnread += key
                if (dialog.unreadMentionsCount > 0) serverMentions[key] = dialog.unreadMentionsCount
                val previous = dao.conversation(account.id, key)
                val hash = if (p.type == 1) userHashes[p.id] ?: users[p.id]?.accessHash else groupHashes[p.id] ?: groups[p.id]?.accessHash
                val name = if (p.type == 1) users[p.id]?.name else groups[p.id]?.title
                val directText = contentText(dialog.message)
                val preview = when {
                    directText.isNotBlank() -> directText
                    dialog.isMessageForwarded -> dao.message(account.id, key, dialog.rid)?.domain()?.text?.ifBlank { null }
                        ?: previous?.preview?.takeIf(String::isNotBlank)
                        ?: "↪"
                    else -> ""
                }
                ConversationEntity(
                    account.id, key, p.id, p.type, hash ?: previous?.accessHash ?: 0,
                    name ?: previous?.title ?: p.id.toString(), preview,
                    dialog.sortDate.takeIf { date -> date != 0L } ?: dialog.date, dialog.unreadCount,
                )
            }
            dao.conversations(rows)
            if (serverMuted.isNotEmpty() || serverUnread.isNotEmpty() || serverMentions.isNotEmpty() || serverPinned != null) {
                preferencesStore.updateOverlay(account.id) { current ->
                    current.copy(
                        mutedPeers = current.mutedPeers + serverMuted,
                        markedUnreadPeers = current.markedUnreadPeers + serverUnread,
                        pinnedPeers = serverPinned ?: current.pinnedPeers,
                        unreadMentions = current.unreadMentions + serverMentions,
                    )
                }
            }
        }
        // Presence for the peers on this page: dialog rows show online/last seen next to the preview.
        val userPeerIds = response.dialogsList.filter { it.peer.type == 1 }.map { it.peer.id }
        if (userPeerIds.isNotEmpty()) activeSession()?.let { fresh ->
            refreshPresence(fresh, userPeerIds) { uid -> userHashes[uid] ?: users[uid]?.accessHash ?: 0L }
        }
        response.dialogsCount
    }

    override suspend fun refreshFolders() {
        val session = activeSession() ?: return
        val account = session.account
        // Archived dialogs come from LoadDialogs with the documented archiveFilter enum (1 = archived
        // only). LoadFolderDialogs targets custom folders and must never be used to rebuild the
        // archived set: an empty/ignored folder id returns every dialog and hides the whole list.
        val archived = runCatching { session.messaging.dialogs(before = -1, archiveFilter = 1) }
            .getOrNull()
            ?.dialogsList
            ?.filter { it.peer.type in 1..2 }
            ?.map { "${it.peer.type}:${it.peer.id}" }
            ?.toSet()
        if (archived != null) {
            // Safety guard: a server that ignores the filter would answer with the *entire* visible
            // list; applying that would make every chat vanish from "All". Accept the snapshot only
            // when it leaves at least one active dialog behind.
            val activeKeys = dao.conversations(account.id).first().map { it.peerKey }.toSet()
            if (activeKeys.isEmpty() || archived.intersect(activeKeys).size < activeKeys.size) {
                preferencesStore.updateOverlay(account.id) { it.copy(archivedPeers = archived) }
            }
        }
        val response = runCatching { session.messaging.loadFolders() }.getOrNull() ?: return
        mutableFolders.update { response.foldersList.map { folder ->
            ChatFolder(
                id = folder.id,
                name = folder.name,
                peerKeys = folder.peersList.map { peerKey(it) }.toSet(),
                unreadCount = folder.unreadPeersCount,
                reserved = folder.isReserved,
            )
        } }
    }

    private fun peerKey(peer: ExPeer): String {
        val kind = if (peer.type == ExPeerKind.PRIVATE || peer.type == ExPeerKind.BOT) 1 else 2
        return "$kind:${peer.id}"
    }

    override suspend fun createFolder(name: String, peers: List<Peer>): Int {
        require(name.isNotBlank()) { "Folder name required" }
        val session = activeSession() ?: error("No account")
        val folderId = session.messaging.createFolder(name.trim())
        require(folderId != 0) { "Folder was not created" }
        peers.forEach { peer ->
            val exType = resolveExPeerType(session, peer)
            safely { session.messaging.addPeerToFolder(folderId, name.trim(), peer.exWire(exType)) }
        }
        safely { refreshFolders() }
        return folderId
    }

    override suspend fun renameFolder(folderId: Int, name: String) {
        val session = activeSession() ?: error("No account")
        session.messaging.removePeersFromFolder(folderId, name.trim(), emptyList())
        safely { refreshFolders() }
    }

    override suspend fun addChatToFolder(peer: Peer, folderId: Int): Boolean {
        val session = activeSession() ?: return false
        val folder = mutableFolders.value.firstOrNull { it.id == folderId } ?: return false
        session.messaging.addPeerToFolder(folderId, folder.name, peer.exWire(resolveExPeerType(session, peer)))
        safely { refreshFolders() }
        return true
    }

    override suspend fun removeChatFromFolder(peer: Peer, folderId: Int) {
        val session = activeSession() ?: return
        val folder = mutableFolders.value.firstOrNull { it.id == folderId } ?: return
        session.messaging.removePeersFromFolder(folderId, folder.name, listOf(peer.exWire(resolveExPeerType(session, peer))))
        safely { refreshFolders() }
    }

    override suspend fun deleteFolder(folderId: Int) {
        val session = activeSession() ?: error("No account")
        session.messaging.deleteFolder(folderId)
        mutableFolders.update { list -> list.filterNot { it.id == folderId } }
    }

    override suspend fun setDialogPinned(peer: Peer, pinned: Boolean) {
        val session = activeSession() ?: error("No account")
        val exType = resolveExPeerType(session, peer)
        preferencesStore.updateOverlay(session.account.id) {
            it.copy(pinnedPeers = if (pinned) it.pinnedPeers + peer.key else it.pinnedPeers - peer.key)
        }
        safely { session.messaging.setDialogPinned(peer, pinned, exType) }
        dirtyDialogs.tryEmit(Unit)
    }

    override suspend fun setDialogArchived(peer: Peer, archived: Boolean) {
        val session = activeSession() ?: error("No account")
        val exType = resolveExPeerType(session, peer)
        preferencesStore.updateOverlay(session.account.id) {
            it.copy(archivedPeers = if (archived) it.archivedPeers + peer.key else it.archivedPeers - peer.key)
        }
        safely { session.messaging.setDialogArchived(peer, archived, exType) }
        dirtyDialogs.tryEmit(Unit)
    }

    override suspend fun setDialogMuted(peer: Peer, muted: Boolean) {
        val account = auth.account.value ?: return
        preferencesStore.updateOverlay(account.id) {
            it.copy(mutedPeers = if (muted) it.mutedPeers + peer.key else it.mutedPeers - peer.key)
        }
    }

    override suspend fun setDialogMarkedUnread(peer: Peer, unread: Boolean) {
        val session = activeSession() ?: error("No account")
        val exType = resolveExPeerType(session, peer)
        preferencesStore.updateOverlay(session.account.id) {
            it.copy(markedUnreadPeers = if (unread) it.markedUnreadPeers + peer.key else it.markedUnreadPeers - peer.key)
        }
        safely { session.messaging.markDialogs(listOf(peer to exType), unread) }
        if (!unread) dao.read(session.account.id, peer.key)
        dirtyDialogs.tryEmit(Unit)
    }

    override suspend fun saveDraft(peer: Peer, draft: String) {
        val account = auth.account.value ?: return
        preferencesStore.updateOverlay(account.id) { current ->
            current.copy(drafts = if (draft.isBlank()) current.drafts - peer.key else current.drafts + (peer.key to draft))
        }
    }

    override suspend fun openSavedMessages(): Peer {
        val session = activeSession() ?: error("No account")
        val account = session.account
        val ownPeer = Peer(account.id, 1, ownAccessHashes[account.id] ?: 0L)
        gate.mutex.withLock {
            if (auth.account.value != account) return@withLock
            if (dao.conversation(account.id, ownPeer.key) == null) {
                dao.conversations(
                    listOf(
                        ConversationEntity(
                            account.id, ownPeer.key, account.id, 1, ownPeer.accessHash,
                            "Saved Messages", "", System.currentTimeMillis(), 0,
                        ),
                    ),
                )
            }
        }
        return ownPeer
    }

    // --- History, polls and outgoing delivery ---
    override suspend fun loadOlderHistory(peer: Peer): Int = loadHistory(peer, OLDER)

    override suspend fun loadHistory(peer: Peer, before: Long): Int {
        val session = activeSession() ?: return 0
        val account = session.account
        val cursor = if (before == OLDER) dao.oldestMessage(account.id, peer.key) ?: -1 else before
        val response = session.messaging.history(peer, cursor)
        val userHashes = response.userPeersList.associate { it.uid to it.accessHash }
        response.usersList.forEach { recordUserMeta(account.id, it, userHashes[it.id] ?: 0L) }
        response.groupsList.forEach { recordGroupMeta(account.id, it) }
        // Hydrate names/avatars for senders the history page did not describe, then refresh presence
        // for them plus the direct peer itself.
        val unknownSenders = response.historyList
            .map { it.senderUid }
            .filter { uid -> uid != 0 && uid != account.id && !userNames.containsKey("${account.id}:$uid") }
            .distinct()
        if (unknownSenders.isNotEmpty()) safely { hydrateUsers(session, unknownSenders) }
        val presenceIds = (if (peer.kind == 1) listOf(peer.id) else emptyList()) + unknownSenders
        if (presenceIds.isNotEmpty()) safely { refreshPresence(session, presenceIds) { uid -> userHashes[uid] ?: 0L } }
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
            fetched.forEachIndexed { index, result ->
                val resolvedId = result.pollId.takeIf { it != 0L } ?: batch.getOrNull(index) ?: return@forEachIndexed
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
                row.copy(metadata = history.withPollResult(result).toByteArray())
            }
            if (updatedRows.isNotEmpty()) dao.messages(updatedRows)
        }
    }

    override suspend fun queue(peer: Peer, text: String, reply: ChatMessage?): Long {
        val rid = queueMessageContent(peer, MessagingApi.text(text.trim().also { require(it.isNotBlank() && it.length <= 4096) }), reply)
        saveDraft(peer, "")
        return rid
    }

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

    override suspend fun sendMedia(peer: Peer, payload: UploadPayload, caption: String, reply: ChatMessage?) =
        sendMediaWithProgress(peer, payload, caption, reply)

    override suspend fun sendMediaWithProgress(
        peer: Peer,
        payload: UploadPayload,
        caption: String,
        reply: ChatMessage?,
        onProgress: (Float) -> Unit,
    ) {
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
        val uploaded = session.files.upload(session.account.id, enrichedPayload, peer, session.token, exPeerType, onProgress)
        val content = FilesApi.documentMessage(
            uploaded = uploaded,
            payload = enrichedPayload,
            caption = caption,
            thumbnail = thumbInfo.bytes,
            thumbWidth = thumbInfo.thumbWidth,
            thumbHeight = thumbInfo.thumbHeight,
        )
        val rid = queueMessageContent(peer, content, reply)
        cacheLocalBytes(session.account.id, uploaded.fileId, uploaded.name, payload.bytes)
        retry(peer, rid)
    }

    override suspend fun downloadMedia(attachment: Attachment): String = downloadMedia(attachment) {}

    override suspend fun downloadMedia(attachment: Attachment, onProgress: (Float) -> Unit): String {
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
        val bytes = session.files.download(attachment, session.token, onProgress)
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
        preferencesStore.updateOverlay(session.account.id) {
            it.copy(markedUnreadPeers = it.markedUnreadPeers - peer.key, unreadMentions = it.unreadMentions - peer.key)
        }
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

    // --- Polls, voters and reaction voters ---
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
            if (alreadyVoted) session.messaging.votePoll(poll.pollId, emptyList(), retract = true, now = 0L)
            session.messaging.votePoll(poll.pollId, optionIds, retract = false, now = 0L)
        }
        gate.mutex.withLock {
            if (auth.account.value == account) dao.message(account.id, peer.key, message.rid)?.let { row ->
                dao.messages(listOf(row.copy(metadata = row.history().withPollResult(result).toByteArray())))
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
                dao.messages(listOf(row.copy(metadata = history.withPollResult(baseResult.toBuilder().setIsClosed(true).build()).toByteArray())))
            }
        }
    }

    override suspend fun fullPollVoters(peer: Peer, message: ChatMessage): List<PollOptionVoters> {
        val poll = message.poll ?: return emptyList()
        val session = activeSession() ?: return emptyList()
        val results = runCatching { session.messaging.fullPollResult(poll.pollId) }.getOrDefault(emptyList())
        val byOption = results.associateBy { it.optionId }
        val voterIds = results.flatMap { result -> result.votersList.map { it.userId } }.distinct()
        hydrateUsers(session, voterIds)
        return poll.options.map { option ->
            val entry = byOption[option.id]
            PollOptionVoters(
                optionId = option.id,
                optionText = option.text,
                votesCount = entry?.votesCount ?: option.votes,
                voters = entry?.votersList.orEmpty().map { voter ->
                    PollVoter(
                        userId = voter.userId,
                        name = userNames["${session.account.id}:${voter.userId}"] ?: "User ${voter.userId}",
                        votedAtMillis = voter.votedAt,
                    )
                },
            )
        }
    }

    override suspend fun reactionVoters(peer: Peer, message: ChatMessage): List<ReactionVoter> {
        val session = activeSession() ?: return emptyList()
        val raw = runCatching { session.messaging.reactionsFor(peer, message.reference) }.getOrDefault(emptyList())
        hydrateUsers(session, raw.map { it.userId }.distinct())
        return raw.map { entry ->
            ReactionVoter(
                userId = entry.userId,
                name = userNames["${session.account.id}:${entry.userId}"] ?: "User ${entry.userId}",
                emoji = entry.code,
                reactedAtMillis = entry.reactionTime,
            )
        }
    }

    // --- Threads, scheduled messages and shared media ---
    override suspend fun loadThreadReplies(peer: Peer, rootMessage: ChatMessage): List<ChatMessage> {
        val session = activeSession() ?: return emptyList()
        val account = session.account
        val exType = resolveExPeerType(session, peer)
        val response = session.messaging.threadReplies(peer, rootMessage.reference, exPeerType = exType)
        val hashes = response.userPeersList.associate { it.uid to it.accessHash }
        response.usersList.forEach { recordUserMeta(account.id, it, hashes[it.id] ?: 0L) }
        gate.mutex.withLock {
            if (auth.account.value == account) database.withTransaction {
                response.historyList.forEach { merge(it.entity(account.id, peer.key)) }
            }
        }
        return response.historyList.map { enrichMessage(account, it.entity(account.id, peer.key).domain()) }.sortedBy { it.date }
    }

    override suspend fun sendThreadReply(peer: Peer, rootMessage: ChatMessage, text: String) {
        val clean = text.trim()
        require(clean.isNotBlank() && clean.length <= 4096)
        val session = activeSession() ?: error("No account")
        val exType = resolveExPeerType(session, peer)
        session.messaging.send(
            peer = peer,
            rid = random.nextLong().ushr(2).coerceAtLeast(1),
            content = MessagingApi.text(clean),
            reply = rootMessage.reference,
            exPeerType = exType,
            threadId = rootMessage.reference,
        )
        dirtyDialogs.tryEmit(Unit)
    }

    override suspend fun listScheduledMessages(peer: Peer): List<ScheduledMessageItem> {
        val session = activeSession() ?: return emptyList()
        val exType = resolveExPeerType(session, peer)
        return session.rich.scheduledTasks(peer, exType).map { task ->
            ScheduledMessageItem(
                taskId = task.taskId,
                peerKey = peer.key,
                text = contentText(task.payload.taskSendMessage.sendMessage.message).ifBlank { "Scheduled message" },
                status = task.status,
            )
        }
    }

    override suspend fun scheduleMessage(peer: Peer, text: String, sendAtMillis: Long) {
        val clean = text.trim()
        require(clean.isNotBlank() && clean.length <= 4096)
        require(sendAtMillis > System.currentTimeMillis() + 30_000L) { "Pick a time at least a minute ahead" }
        val session = activeSession() ?: error("No account")
        val exType = resolveExPeerType(session, peer)
        val rid = random.nextLong().ushr(2).coerceAtLeast(1)
        session.rich.scheduleMessage(peer, rid, MessagingApi.text(clean), sendAtMillis, exType)
    }

    override suspend fun sendScheduledNow(taskId: Long) {
        val session = activeSession() ?: error("No account")
        session.rich.executeTaskNow(taskId)
        dirtyDialogs.tryEmit(Unit)
    }

    override suspend fun cancelScheduledMessage(taskId: Long) {
        val session = activeSession() ?: error("No account")
        session.rich.cancelTasks(listOf(taskId))
    }

    override suspend fun loadSharedMedia(peer: Peer, tab: SharedMediaTab): List<ChatMessage> {
        val session = activeSession() ?: return emptyList()
        val account = session.account
        val exType = resolveExPeerType(session, peer)
        val results = runCatching { session.rich.sharedMedia(peer, exType, tab.wireType) }.getOrDefault(emptyList())
        gate.mutex.withLock {
            if (auth.account.value == account) database.withTransaction {
                results.forEach { merge(it.toHistory().entity(account.id, peer.key)) }
            }
        }
        return results.map { enrichMessage(account, it.toHistory().entity(account.id, peer.key).domain()) }.sortedByDescending { it.date }
    }

    // --- Stickers, GIFs, previews and transcripts ---
    private fun mapStickerPack(collection: ai.bale.client.protocol.Collections, owned: Boolean): StickerPack {
        val items = collection.stickersList.map { entry ->
            val location = when {
                entry.hasImage256() && entry.image256.hasFileLocation() -> entry.image256
                entry.hasImage512() && entry.image512.hasFileLocation() -> entry.image512
                entry.hasImage128() && entry.image128.hasFileLocation() -> entry.image128
                else -> null
            }
            StickerItem(
                id = entry.id,
                emoji = entry.emoji.value,
                collectionId = collection.id,
                collectionAccessHash = collection.accessHash,
                fileId = location?.fileLocation?.fileId ?: 0L,
                accessHash = location?.fileLocation?.accessHash ?: 0L,
                width = location?.width ?: 0,
                height = location?.height ?: 0,
                animated = collection.animated.value || entry.format == 2,
                format = entry.format,
            )
        } + collection.stickersV2List.map { entry ->
            StickerItem(
                id = entry.id,
                emoji = entry.emoji.value,
                collectionId = collection.id,
                collectionAccessHash = collection.accessHash,
                fileId = if (entry.hasFileLocation()) entry.fileLocation.fileLocation.fileId else 0L,
                accessHash = if (entry.hasFileLocation()) entry.fileLocation.fileLocation.accessHash else 0L,
                width = if (entry.hasFileLocation()) entry.fileLocation.width else 0,
                height = if (entry.hasFileLocation()) entry.fileLocation.height else 0,
                animated = collection.animated.value,
            )
        }
        return StickerPack(
            id = collection.id,
            accessHash = collection.accessHash,
            name = collection.name.value.ifBlank { "Stickers ${collection.id}" },
            animated = collection.animated.value,
            owned = owned,
            stickers = items.filter { it.fileId != 0L || it.id != 0 },
        )
    }

    override suspend fun loadStickerPacks(): List<StickerPack> {
        val session = activeSession() ?: return emptyList()
        val packs = runCatching { session.rich.ownStickers() }.getOrDefault(emptyList()).map { mapStickerPack(it, true) }
        return packs.map { pack ->
            if (pack.stickers.isNotEmpty()) pack else runCatching {
                mapStickerPack(session.rich.stickerCollection(pack.id, pack.accessHash), true)
            }.getOrDefault(pack)
        }.filter { it.stickers.isNotEmpty() }
    }

    override suspend fun sendSticker(peer: Peer, sticker: StickerItem) {
        val rid = queueMessageContent(peer, RichFeaturesApi.stickerMessage(sticker), null)
        retry(peer, rid)
    }

    override suspend fun addStickerPack(collectionId: Int, accessHash: Long) {
        val session = activeSession() ?: error("No account")
        session.rich.addStickerCollection(collectionId, accessHash)
    }

    override suspend fun removeStickerPack(collectionId: Int, accessHash: Long) {
        val session = activeSession() ?: error("No account")
        session.rich.removeStickerCollection(collectionId, accessHash)
    }

    override suspend fun loadSavedGifs(): List<SavedGif> {
        val session = activeSession() ?: return emptyList()
        return runCatching { session.rich.savedGifs() }.getOrDefault(emptyList()).map { entry ->
            SavedGif(
                fileId = entry.gifLocation.fileId,
                accessHash = entry.gifLocation.accessHash,
                storageVersion = if (entry.gifLocation.hasFileStorageVersion()) entry.gifLocation.fileStorageVersion.value else 0,
                mimeType = entry.mimeType.value.ifBlank { "video/mp4" },
                sizeBytes = entry.fileSize,
                width = entry.width,
                height = entry.height,
                durationSeconds = entry.duration,
                usedAt = entry.usedAt,
                thumbnail = entry.thumb.toByteArray(),
            )
        }
    }

    override suspend fun sendSavedGif(peer: Peer, gif: SavedGif) {
        val session = activeSession() ?: error("No account")
        safely { session.rich.useGif(RichFeaturesApi.location(gif.fileId, gif.accessHash, gif.storageVersion)) }
        val rid = queueMessageContent(peer, RichFeaturesApi.gifMessage(gif), null)
        retry(peer, rid)
    }

    override suspend fun saveGif(attachment: Attachment) {
        val session = activeSession() ?: error("No account")
        require(attachment.fileId != 0L) { "Only uploaded media can be saved" }
        session.rich.addGif(
            RichFeaturesApi.location(attachment.fileId, attachment.accessHash, attachment.storageVersion),
            attachment.mimeType,
            attachment.thumbnail,
        )
    }

    override suspend fun removeGif(gif: SavedGif) {
        val session = activeSession() ?: error("No account")
        session.rich.removeGif(RichFeaturesApi.location(gif.fileId, gif.accessHash, gif.storageVersion))
    }

    override suspend fun fetchLinkPreview(url: String, withSummary: Boolean): LinkPreviewInfo? {
        val session = activeSession() ?: return null
        val preview = runCatching { session.rich.linkPreview(url) }.getOrNull()
        val summary = if (withSummary) runCatching { session.rich.linkSummary(url) }.getOrDefault("") else ""
        if (preview == null && summary.isBlank()) return null
        return (preview ?: LinkPreviewInfo(url = url.trim())).copy(summary = summary)
    }

    override suspend fun transcribeVoice(peer: Peer, message: ChatMessage): String {
        val session = activeSession() ?: error("No account")
        val account = session.account
        val row = dao.message(account.id, peer.key, message.rid) ?: error("Message is not available locally")
        val voice = row.history().documentMessageOrNull() ?: error("No voice note attached")
        val exType = resolveExPeerType(session, peer)
        val transcript = session.rich.voiceTranscript(peer, message.reference, voice, exType)
            ?: error("Transcription is still running; try again in a moment")
        val bytes = session.files.download(
            Attachment(
                fileId = transcript.fileId,
                accessHash = transcript.accessHash,
                name = transcript.name.ifBlank { "transcript.txt" },
                mimeType = transcript.mimeType.ifBlank { "text/plain" },
                sizeBytes = transcript.fileSize,
                kind = AttachmentKind.Document,
            ),
            session.token,
        )
        return bytes.toString(Charsets.UTF_8).trim()
    }

    // --- Bots, inline bots and Mini-Apps ---
    override suspend fun pressBotButton(peer: Peer, button: BotButton) {
        when (button.kind) {
            BotButtonKind.Callback -> {
                val rid = queueMessageContent(
                    peer,
                    MessagingApi.templateResponse(button.value.ifBlank { button.label }, button.templateId),
                    null,
                )
                retry(peer, rid)
            }
            BotButtonKind.ReplyText -> {
                val rid = queueMessageContent(peer, MessagingApi.text(button.value.ifBlank { button.label }), null)
                retry(peer, rid)
            }
            BotButtonKind.OpenUrl, BotButtonKind.CopyText, BotButtonKind.MiniApp -> return
        }
    }

    override suspend fun openMiniApp(botPeer: Peer, startParam: String): MiniAppSession? {
        val session = activeSession() ?: return null
        val exType = resolveExPeerType(session, botPeer)
        val button = runCatching { session.rich.menuButton(botPeer.id) }.getOrNull()
        val fallback = if (button != null && button.hasMiniApp()) button.miniApp.url else ""
        val (url, queryId) = runCatching {
            session.rich.miniAppUrl(botPeer.id, botPeer, exType, startParam, fallback)
        }.getOrElse {
            if (fallback.isBlank()) return null else fallback to ""
        }
        if (url.isBlank()) return null
        return MiniAppSession(url = url, queryId = queryId)
    }

    override suspend fun submitMiniAppQuery(botPeer: Peer, queryId: String, data: String, buttonText: String) {
        val session = activeSession() ?: error("No account")
        session.rich.sendMiniAppData(botPeer.id, queryId, data, buttonText)
    }

    override suspend fun loadRecommendedBots(): List<RecommendedBot> {
        val session = activeSession() ?: return emptyList()
        val bots = runCatching { session.rich.recommendedBots() }.getOrDefault(emptyList())
        if (bots.isEmpty()) return emptyList()
        val byId = runCatching {
            session.messaging.users(bots.map { UserPeers.newBuilder().setUid(it.botUserId).build() })
                .usersList.onEach { recordUserMeta(session.account.id, it) }
                .associateBy { it.id }
        }.getOrDefault(emptyMap())
        return bots.map { bot ->
            val user = byId[bot.botUserId]
            RecommendedBot(
                userId = bot.botUserId,
                accessHash = user?.accessHash ?: 0L,
                title = user?.name?.ifBlank { null } ?: "Bot ${bot.botUserId}",
                handle = user?.nick?.value.orEmpty(),
                description = bot.description,
                avatar = user?.avatarRefOrNull(),
            )
        }
    }

    override suspend fun queryInlineBot(botHandle: String, peer: Peer, query: String): List<InlineBotResultItem> {
        val cleanHandle = botHandle.trim().removePrefix("@")
        if (cleanHandle.isBlank() || query.isBlank()) return emptyList()
        val session = activeSession() ?: return emptyList()
        val bot = searchDirectory(cleanHandle).firstOrNull { !it.isGroup && (it.isBot || it.handle.equals(cleanHandle, ignoreCase = true)) } ?: return emptyList()
        val response = runCatching { session.rich.inlineBotResults(peer, bot.peer.id, query.trim()) }.getOrNull() ?: return emptyList()
        val queryId = if (response.hasQueryId()) response.queryId.value else 0L
        return response.resultsList.map { result ->
            val sendText = when {
                result.hasBotInlineMessage() && result.botInlineMessage.hasBotInlineMessageText() ->
                    result.botInlineMessage.botInlineMessageText.text
                result.hasBotInlineMessage() && result.botInlineMessage.hasBotInlineMessageMediaAuto() ->
                    result.botInlineMessage.botInlineMessageMediaAuto.caption
                result.hasBotInlineResult() -> result.botInlineResult.url
                else -> ""
            }
            InlineBotResultItem(
                queryId = queryId,
                resultId = "${result.type}:${result.title.text}",
                title = result.title.text.ifBlank { "Result" },
                description = result.description.text,
                sendText = sendText.ifBlank { result.title.text },
                previewUrl = if (result.hasBotInlineResult()) result.botInlineResult.url else "",
            )
        }
    }

    // --- Directory, profiles and communities ---
    override suspend fun contacts(): List<ContactEntry> {
        val session = activeSession() ?: return emptyList()
        val response = session.directory.contacts()
        val hashes = response.userPeersList.associate { it.uid to it.accessHash }
        response.usersList.forEach { recordUserMeta(session.account.id, it, hashes[it.id] ?: 0L) }
        return response.usersList.map { user ->
            ContactEntry(
                peer = Peer(user.id, 1, hashes[user.id] ?: user.accessHash),
                name = user.name.ifBlank { user.nick.value.ifBlank { user.id.toString() } },
                handle = user.nick.value,
                isBot = user.isBot.value,
                isGroup = false,
                avatar = user.avatarRefOrNull(),
            )
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun searchDirectory(query: String): List<ContactEntry> {
        if (query.isBlank()) return emptyList()
        val session = activeSession() ?: return emptyList()
        val response = session.directory.searchContacts(query)
        val userHashes = response.userPeersList.associate { it.uid to it.accessHash }
        val groupHashes = response.groupPeersList.associate { it.groupId to it.accessHash }
        response.usersList.forEach { recordUserMeta(session.account.id, it, userHashes[it.id] ?: 0L) }
        response.groupsList.forEach { recordGroupMeta(session.account.id, it) }
        val users = response.usersList.map { user ->
            ContactEntry(
                peer = Peer(user.id, 1, userHashes[user.id] ?: user.accessHash),
                name = user.name.ifBlank { user.nick.value.ifBlank { user.id.toString() } },
                handle = user.nick.value,
                isBot = user.isBot.value,
                isGroup = false,
                avatar = user.avatarRefOrNull(),
            )
        }
        val groups = response.groupsList.map { group ->
            ContactEntry(
                peer = Peer(group.id, 2, groupHashes[group.id] ?: group.accessHash),
                name = group.title.ifBlank { group.nick.value.ifBlank { group.id.toString() } },
                handle = group.nick.value,
                isBot = false,
                isGroup = true,
                avatar = if (group.hasAvatar()) group.avatar.toRefOrNull() else null,
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
            session.messaging.pinnedMessages(peer).map { enrichMessage(account, it.entity(account.id, peer.key).domain()) }
        }.getOrDefault(emptyList())
        return if (peer.kind == 1) {
            val full = session.directory.fullUser(peer)
            val resolvedHash = full.accessHash.takeIf { it != 0L } ?: peer.accessHash
            val resolvedPeer = Peer(peer.id, 1, resolvedHash)
            if (peer.id == account.id && resolvedHash != 0L) ownAccessHashes[account.id] = resolvedHash
            exPeerTypes["${account.id}:${peer.key}"] = if (full.isBot.value) ExPeerKind.BOT else ExPeerKind.PRIVATE
            val avatarRef = if (full.hasAvatar()) full.avatar.toRefOrNull() else null
            avatarRef?.let { peerAvatars["${account.id}:${peer.key}"] = it }
            val menu = if (full.isBot.value) runCatching { session.rich.menuButton(peer.id) }.getOrNull() else null
            PeerProfile(
                peer = resolvedPeer,
                title = full.name.ifBlank { peer.id.toString() },
                handle = full.nick.value,
                about = full.about.value,
                isGroup = false,
                isChannel = false,
                isBot = full.isBot.value,
                isBlocked = full.isBlocked.value,
                pinnedMessages = pins,
                avatar = avatarRef,
                botCommands = full.botCommandsList.map { command ->
                    BotCommandInfo(command.slashCommand.removePrefix("/"), command.description)
                },
                botMenuButton = menu?.let { button ->
                    if (button.hasMiniApp() && button.miniApp.url.isNotBlank()) {
                        BotMenuAction(button.miniApp.text.ifBlank { "Open app" }, button.miniApp.url)
                    } else {
                        BotMenuAction("Commands", isCommandsMenu = true)
                    }
                },
            )
        } else {
            val full = session.directory.fullGroup(peer)
            if (full.id != 0) recordGroupType(account.id, full.id, full.groupType)
            val avatarRef = if (full.hasAvatar()) full.avatar.toRefOrNull() else null
            avatarRef?.let { peerAvatars["${account.id}:${peer.key}"] = it }
            val rawMembers = runCatching { session.directory.members(peer) }.getOrDefault(full.members17List)
            val banned = runCatching { session.directory.bannedUsers(peer) }.getOrDefault(emptyList())
            val userIds = (rawMembers.map { it.uid } + banned.map { it.bannedUser.uid }).distinct()
            val userMap = if (userIds.isEmpty()) emptyMap() else runCatching {
                session.messaging.users(userIds.map { UserPeers.newBuilder().setUid(it).build() })
                    .usersList.onEach { recordUserMeta(account.id, it) }.associateBy { it.id }
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
                members = rawMembers.map { member ->
                    val user = userMap[member.uid]
                    GroupMember(
                        userId = member.uid,
                        accessHash = user?.accessHash ?: 0L,
                        name = user?.name?.ifBlank { null } ?: member.uid.toString(),
                        isAdmin = member.isAdmin.value || member.uid == full.ownerUid,
                        title = member.title.value,
                        avatar = user?.avatarRefOrNull(),
                    )
                },
                pinnedMessages = pins,
                avatar = avatarRef,
                bannedMembers = banned.mapNotNull { entry ->
                    val user = userMap[entry.bannedUser.uid]
                    GroupMember(
                        userId = entry.bannedUser.uid,
                        accessHash = entry.bannedUser.accessHash,
                        name = user?.name?.ifBlank { null } ?: "User ${entry.bannedUser.uid}",
                        avatar = user?.avatarRefOrNull(),
                    )
                },
            )
        }
    }

    override suspend fun checkUsernameAvailable(nick: String): Boolean {
        val clean = nick.trim().removePrefix("@")
        if (clean.length < 4) return false
        val session = activeSession() ?: return false
        return runCatching { session.directory.checkNickName(clean) }.getOrDefault(true)
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
        storeAvatarLocally(uploaded.fileId, session.account.id.toLong(), payload.bytes)
    }

    override suspend fun uploadGroupAvatar(group: Peer, payload: UploadPayload) {
        val session = activeSession() ?: error("No account")
        val exType = resolveExPeerType(session, group)
        val uploaded = session.files.upload(session.account.id, payload, group, session.token, exType)
        val ownerHash = if (exType == ExPeerKind.CHANNEL) group.id.toLong() else session.account.id.toLong()
        session.directory.editGroupAvatar(group, random.nextLong().ushr(2).coerceAtLeast(1), uploaded.fileId, ownerHash)
        storeAvatarLocally(uploaded.fileId, ownerHash, payload.bytes, group.key)
        dirtyDialogs.tryEmit(Unit)
    }

    private suspend fun storeAvatarLocally(fileId: Long, accessHash: Long, bytes: ByteArray, peerKey: String? = null) {
        val account = auth.account.value ?: return
        val ref = AvatarRef(fileId, accessHash)
        val cacheKey = if (peerKey != null) "${account.id}:$peerKey" else "${account.id}:1:${account.id}"
        peerAvatars[cacheKey] = ref
        peerMetaVersion.update { it + 1 }
        val target = avatarCacheFile(fileId)
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        mutableAvatarPaths.update { it + (fileId to target.absolutePath) }
    }

    override suspend fun updateChannelHandle(group: Peer, handle: String) {
        val session = activeSession() ?: error("No account")
        session.directory.editChannelNick(group, random.nextLong().ushr(2).coerceAtLeast(1), handle)
        dirtyDialogs.tryEmit(Unit)
    }

    override suspend fun reportPeerOrMessage(peer: Peer, message: ChatMessage?, reason: String) {
        val session = activeSession() ?: error("No account")
        session.rich.reportContent(peer, resolveExPeerType(session, peer), message?.reference, reason)
    }

    override suspend fun createCommunity(title: String, isChannel: Boolean, handle: String, members: List<Peer>): Peer {
        require(title.isNotBlank())
        val session = activeSession() ?: error("No account")
        val group = session.directory.createCommunity(
            random.nextLong().ushr(2).coerceAtLeast(1), title, isChannel, handle, members,
        )
        recordGroupMeta(session.account.id, group)
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
        recordGroupMeta(session.account.id, group)
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

    override suspend fun loadSecurityAndPrivacy(): SecurityAndPrivacyState {
        val session = activeSession() ?: return SecurityAndPrivacyState()
        val account = session.account
        val privacy = runCatching { session.directory.userPrivacy(account.id) }.getOrDefault(Privacy.getDefaultInstance())
        val blocked = runCatching { session.directory.blockedUsers() }.getOrDefault(emptyList())
        val byId = if (blocked.isEmpty()) emptyMap() else runCatching {
            session.messaging.users(blocked).usersList.onEach { recordUserMeta(account.id, it) }.associateBy { it.id }
        }.getOrDefault(emptyMap())
        return SecurityAndPrivacyState(
            twoFactorEnabled = runCatching { auth.isTwoFactorEnabled() }.getOrDefault(false),
            invitePrivacy = privacy.invitePrivacy,
            presencePrivacy = privacy.presencePrivacy,
            blockedUsers = blocked.map { entry ->
                val user = byId[entry.uid]
                ContactEntry(
                    peer = Peer(entry.uid, 1, entry.accessHash),
                    name = user?.name?.ifBlank { null } ?: "User ${entry.uid}",
                    handle = user?.nick?.value.orEmpty(),
                    isBot = user?.isBot?.value ?: false,
                    avatar = user?.avatarRefOrNull(),
                )
            },
        )
    }

    // --- Stories ---
    override suspend fun loadStories(): List<StoryItem> {
        val session = activeSession() ?: return emptyList()
        val account = session.account
        val response = session.directory.allStories()
        val userIds = response.userStoriesList.map { it.ownerUserId }.distinct()
        hydrateUsers(session, userIds)
        val channelIds = response.channelStoriesList
            .map { it.exPeer.id.takeIf { id -> id != 0 } ?: it.ownerUserId }
            .filter { id -> id != 0 && !groupTitles.containsKey("${account.id}:$id") }
            .distinct()
        if (channelIds.isNotEmpty()) {
            safely {
                channelIds.chunked(METADATA_PAGE_SIZE).forEach { chunk ->
                    val peers = chunk.map { GroupPeer.newBuilder().setGroupId(it).build() }
                    session.messaging.groups(peers).groupsList.forEach { recordGroupMeta(account.id, it) }
                }
            }
        }
        val stories = response.userStoriesList.filter { !it.isHidden }.map { story ->
            val media = if (story.storyContent.hasMedia() && story.storyContent.media.hasFileLocation()) {
                val location = story.storyContent.media.fileLocation
                Attachment(
                    fileId = location.fileId,
                    accessHash = location.accessHash,
                    storageVersion = if (location.hasFileStorageVersion()) location.fileStorageVersion.value else 0,
                    name = "story_${story.id}",
                    mimeType = if (story.storyContent.media.hasVideo()) "video/mp4" else "image/jpeg",
                    sizeBytes = story.storyContent.media.fileSize.toInt(),
                    kind = if (story.storyContent.media.hasVideo()) AttachmentKind.Video else AttachmentKind.Photo,
                    thumbnail = if (story.storyContent.media.hasFastThumb()) story.storyContent.media.fastThumb.thumb.toByteArray() else byteArrayOf(),
                )
            } else null
            StoryItem(
                id = story.id,
                ownerId = story.ownerUserId,
                ownerTitle = userNames["${account.id}:${story.ownerUserId}"] ?: "User ${story.ownerUserId}",
                text = if (story.storyContent.hasText()) story.storyContent.text.text else "",
                createdAt = story.createdAt,
                myReaction = story.reaction.value,
                isChannel = false,
                media = media,
                avatar = peerAvatars["${account.id}:1:${story.ownerUserId}"],
            )
        }
        val channelStories = response.channelStoriesList.filter { !it.isHidden }.map { story ->
            val media = if (story.hasStoryContent() && story.storyContent.hasFileLocation()) {
                val location = story.storyContent.fileLocation
                Attachment(
                    fileId = location.fileId,
                    accessHash = location.accessHash,
                    storageVersion = if (location.hasFileStorageVersion()) location.fileStorageVersion.value else 0,
                    name = "story_${story.id}",
                    mimeType = if (story.storyContent.hasVideo()) "video/mp4" else "image/jpeg",
                    sizeBytes = story.storyContent.fileSize.toInt(),
                    kind = if (story.storyContent.hasVideo()) AttachmentKind.Video else AttachmentKind.Photo,
                    thumbnail = if (story.storyContent.hasFastThumb()) story.storyContent.fastThumb.thumb.toByteArray() else byteArrayOf(),
                )
            } else null
            val channelId = story.exPeer.id.takeIf { it != 0 } ?: story.ownerUserId
            StoryItem(
                id = story.id,
                ownerId = channelId,
                ownerTitle = groupTitles["${account.id}:$channelId"]
                    ?: userNames["${account.id}:$channelId"]
                    ?: "Channel $channelId",
                text = "",
                createdAt = story.createdAt,
                myReaction = story.reaction.value,
                isChannel = true,
                media = media,
                avatar = peerAvatars["${account.id}:2:$channelId"],
            )
        }
        return (stories + channelStories).sortedByDescending { it.createdAt }
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

    // --- Search and updates ---
    override suspend fun searchMessages(query: String, peer: Peer?): SearchPage {
        val session = activeSession() ?: error("No account")
        val account = session.account
        require(query.isNotBlank() && query.length <= 256)
        val response = session.messaging.search(query.trim(), peer)
        if (auth.account.value != account) return SearchPage(emptyList(), 0)
        val users = response.usersList.associateBy { it.id }
        val groups = response.groupsList.associateBy { it.id }
        users.values.forEach { recordUserMeta(account.id, it) }
        groups.values.forEach { recordGroupMeta(account.id, it) }
        val hits = response.searchResultsList.mapNotNull { entry ->
            val result = entry.result
            if (result.peer.type !in 1..2) return@mapNotNull null
            val p = result.peer
            val cached = dao.conversation(account.id, "${p.type}:${p.id}")
            val hash = if (p.type == 1) users[p.id]?.accessHash else groups[p.id]?.accessHash
            val title = if (p.type == 1) users[p.id]?.name else groups[p.id]?.title
            val history = if (result.hasMessageContainer()) result.messageContainer else History.newBuilder()
                .setRid(result.rid).setDate(result.date).setSenderUid(result.senderId).setMessage(result.content).build()
            SearchHit(
                Peer(p.id, p.type, hash ?: cached?.accessHash ?: 0),
                title ?: cached?.title ?: p.id.toString(),
                enrichMessage(account, history.entity(account.id, "${p.type}:${p.id}").domain()),
            )
        }
        return SearchPage(hits, response.resultCount)
    }

    override suspend fun setTyping(peer: Peer, active: Boolean) {
        val session = activeSession() ?: return
        if (connection.value == Connection.Connected) session.messaging.typing(peer, active)
    }

    private suspend fun applyUpdate(update: Update, session: Long) {
        mutableLastPushAt.value = System.currentTimeMillis()
        val account = auth.account.value ?: return
        val mutedPeers = preferencesStore.overlayFor(account.id).mutedPeers
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
                    if (m.senderUid != 0 && m.senderUid != account.id && !userNames.containsKey("${account.id}:${m.senderUid}")) {
                        pendingSenderIds += m.senderUid
                        pendingSenders.tryEmit(m.senderUid)
                    }
                    val history = History.newBuilder().setRid(m.rid).setDate(m.date).setSenderUid(m.senderUid).setMessage(m.message)
                    if (m.hasQuotedMessage()) history.setQuotedMessage(m.quotedMessage)
                    val built = history.build()
                    merge(built.entity(account.id, key))
                    bumpConversation(account, m.peer.type, m.peer.id, m.date, built, incoming = m.senderUid != 0 && m.senderUid != account.id)
                    if (built.pollMessageOrNull()?.pollId?.let { it != 0L } == true && m.peer.type in 1..2) {
                        incomingPollPeer = Peer(m.peer.id, m.peer.type, dao.conversation(account.id, key)?.accessHash ?: 0L)
                    }
                    if (m.senderUid != account.id && !m.isSilent && key !in mutedPeers) {
                        notifyTitle = dao.conversation(account.id, key)?.title ?: key
                        notifyText = contentText(effectiveHistoryMessage(built)).ifBlank { "New message" }
                        notifyId = key.hashCode()
                    }
                }
                if (update.hasMessages()) {
                    val m = update.messages
                    val key = "${m.peer.type}:${m.peer.id}"
                    if (m.field18.senderUid != 0 && m.field18.senderUid != account.id && !userNames.containsKey("${account.id}:${m.field18.senderUid}")) {
                        pendingSenderIds += m.field18.senderUid
                        pendingSenders.tryEmit(m.field18.senderUid)
                    }
                    merge(m.field18.entity(account.id, key))
                    bumpConversation(account, m.peer.type, m.peer.id, m.field18.date, m.field18, incoming = m.field18.senderUid != 0 && m.field18.senderUid != account.id)
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
        if (update.hasUserOnline()) {
            val m = update.userOnline
            if (m.uid != 0) {
                mutableOnline.update { it + m.uid }
                mutablePresence.update { it + (m.uid to (it[m.uid] ?: PeerPresence()).copy(online = true, lastSeenUnknown = false)) }
            }
        }
        if (update.hasUserOffline()) {
            val m = update.userOffline
            if (m.uid != 0) {
                mutableOnline.update { it - m.uid }
                mutablePresence.update { it + (m.uid to (it[m.uid] ?: PeerPresence()).copy(online = false)) }
            }
        }
        if (update.hasUserLastSeen()) {
            val m = update.userLastSeen
            if (m.uid != 0) {
                mutableOnline.update { it - m.uid }
                mutablePresence.update {
                    it + (m.uid to (it[m.uid] ?: PeerPresence()).copy(
                        online = false,
                        lastSeenEpochSec = m.date,
                        lastSeenUnknown = false,
                        unknownLevel = 0,
                    ))
                }
            }
        }
        if (update.hasUserLastSeenUnknown()) {
            val m = update.userLastSeenUnknown
            if (m.uid != 0) {
                mutableOnline.update { it - m.uid }
                mutablePresence.update {
                    it + (m.uid to (it[m.uid] ?: PeerPresence()).copy(
                        online = false,
                        lastSeenUnknown = true,
                        unknownLevel = m.value,
                    ))
                }
            }
        }
        if (update.hasGroupOnline()) {
            val m = update.groupOnline
            if (m.groupId != 0) mutableGroupOnline.update { it + (m.groupId to m.count.coerceAtLeast(0)) }
        }
        if (update.hasUserNameChanged()) {
            val m = update.userNameChanged
            if (m.uid != 0 && m.name.isNotBlank()) {
                userNames.put("${account.id}:${m.uid}", m.name)
                peerMetaVersion.update { it + 1 }
                dao.renamePeer(account.id, "1:${m.uid}", m.name)
            }
        }
        if (update.hasUserAvatarChanged()) {
            val m = update.userAvatarChanged
            if (m.uid != 0 && m.hasAvatar()) {
                m.avatar.toRefOrNull()?.let { ref -> peerAvatars["${account.id}:1:${m.uid}"] = ref }
                    ?: peerAvatars.remove("${account.id}:1:${m.uid}")
                peerMetaVersion.update { it + 1 }
            }
        }
        if (update.hasPinnedDialogsChanged() && update.pinnedDialogsChanged.folderId == 0) {
            // The server pushes the complete pinned set of the default folder after every change.
            val pinned = update.pinnedDialogsChanged.pinnedPeersList
                .filter { it.type in 1..4 }
                .map { peerKey(it) }
                .toSet()
            preferencesStore.updateOverlay(account.id) { it.copy(pinnedPeers = pinned) }
        }
        if (update.hasDialogArchiveStatus()) {
            val m = update.dialogArchiveStatus
            val keys = m.exPeersList.map { peerKey(it) }.toSet()
            if (keys.isNotEmpty()) {
                preferencesStore.updateOverlay(account.id) { current ->
                    current.copy(
                        archivedPeers = if (m.isArchived) current.archivedPeers + keys else current.archivedPeers - keys,
                    )
                }
            }
        }
        if (update.hasChatArchive()) {
            val m = update.chatArchive
            if (m.peer.id != 0) {
                val key = "${if (m.peer.type == 1) 1 else 2}:${m.peer.id}"
                preferencesStore.updateOverlay(account.id) { current ->
                    current.copy(
                        archivedPeers = if (m.getIs()) current.archivedPeers + key else current.archivedPeers - key,
                    )
                }
            }
        }
        if (update.hasChatShow()) {
            // ChatShow(is=true) un-hides a chat in every folder: it can no longer be archived.
            val m = update.chatShow
            if (m.peer.id != 0 && m.getIs()) {
                val key = "${if (m.peer.type == 1) 1 else 2}:${m.peer.id}"
                preferencesStore.updateOverlay(account.id) { current ->
                    if (key in current.archivedPeers) current.copy(archivedPeers = current.archivedPeers - key) else current
                }
            }
        }
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
        dao.messages(listOf(incoming.copy(
            text = incoming.text.ifBlank { contentText(effective) },
            supported = incoming.supported || isSupportedMessage(effective),
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

    companion object { const val OLDER = -2L; const val METADATA_PAGE_SIZE = 100; const val PUSH_STALE_MS = 45_000L }
}
