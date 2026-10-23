package ai.bale.client.feature.messaging

import ai.bale.client.domain.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

enum class AppSection { Chats, Contacts, Stories, Settings }
data class ComposerState(val reply: ChatMessage? = null, val edit: ChatMessage? = null)
data class SearchState(val open: Boolean = false, val busy: Boolean = false, val page: SearchPage? = null, val failed: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MessengerViewModel @Inject constructor(
    private val messenger: MessengerRepository,
    private val auth: AuthRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val account = auth.account
    val connection = messenger.connection
    val onlineUsers = messenger.onlineUsers
    val peerPresence = messenger.peerPresence
    val groupOnlineCounts = messenger.groupOnlineCounts
    val conversations = messenger.conversations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val mutableSection = MutableStateFlow(AppSection.Chats)
    val section = mutableSection.asStateFlow()

    private val mutableSelected = MutableStateFlow(savedState.get<Int>("peerId")?.let {
        Peer(it, savedState.get<Int>("peerKind") ?: 1, savedState.get<Long>("peerHash") ?: 0)
    })
    val selected = mutableSelected.asStateFlow()
    val pagedMessages = selected.flatMapLatest { peer ->
        if (peer == null) flowOf(PagingData.empty()) else messenger.pagedMessages(peer)
    }.cachedIn(viewModelScope)
    val typingUsers = selected.flatMapLatest { peer -> if (peer == null) flowOf(emptySet()) else messenger.typingUsers(peer) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val mutableQuery = MutableStateFlow("")
    val query = mutableQuery.asStateFlow()
    val filtered = combine(conversations, query) { items, text -> items.filter { it.title.contains(text, ignoreCase = true) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val mutableError = MutableStateFlow(false)
    val error = mutableError.asStateFlow()
    private val operations = MutableStateFlow(0)
    val loading = operations.map { it > 0 }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val mutableUploading = MutableStateFlow<String?>(null)
    val uploadingAttachment = mutableUploading.asStateFlow()
    private val mutableQueuing = MutableStateFlow(false)
    val queuing = mutableQueuing.asStateFlow()
    private val mutableComposer = MutableStateFlow(ComposerState())
    val composer = mutableComposer.asStateFlow()
    private val mutableSearch = MutableStateFlow(SearchState())
    val searchState = mutableSearch.asStateFlow()
    private val mutableMoreHistory = MutableStateFlow(true)
    val moreHistory = mutableMoreHistory.asStateFlow()
    private val mutableMoreDialogs = MutableStateFlow(true)
    val moreDialogs = mutableMoreDialogs.asStateFlow()

    // Media cache paths keyed by fileId for downloaded attachments
    private val mutableDownloaded = MutableStateFlow<Map<Long, String>>(emptyMap())
    val downloadedFiles = mutableDownloaded.asStateFlow()

    // Contacts, Stories, Profile Inspector & Sessions states
    private val mutableContacts = MutableStateFlow<List<ContactEntry>>(emptyList())
    val contacts = mutableContacts.asStateFlow()
    private val mutableDirectoryResults = MutableStateFlow<List<ContactEntry>>(emptyList())
    val directoryResults = mutableDirectoryResults.asStateFlow()
    private val mutableStories = MutableStateFlow<List<StoryItem>>(emptyList())
    val stories = mutableStories.asStateFlow()
    private val mutableOwnProfile = MutableStateFlow<PeerProfile?>(null)
    val ownProfile = mutableOwnProfile.asStateFlow()
    private val mutableSessions = MutableStateFlow<List<AuthSessionInfo>>(emptyList())
    val sessions = mutableSessions.asStateFlow()
    private val mutableInspectedProfile = MutableStateFlow<PeerProfile?>(null)
    val inspectedProfile = mutableInspectedProfile.asStateFlow()

    private var historyJob: Job? = null
    private var searchJob: Job? = null
    private var directoryJob: Job? = null
    private var typingJob: Job? = null
    private var lastTyping = 0L
    private val visibleRead = MutableStateFlow<Long?>(null)
    private val acknowledgedReads = mutableMapOf<String, Long>()

    init {
        // The repository's push-staleness watchdog reloads the chat that is currently on screen;
        // this just tells it which one that is.
        viewModelScope.launch { selected.collect { peer -> messenger.noteForegroundPeer(peer) } }
        viewModelScope.launch {
            combine(selected, visibleRead, connection, account) { peer, date, state, owner ->
                if (peer != null && date != null && state == Connection.Connected && owner != null) Triple(owner.id, peer, date) else null
            }.debounce(300).distinctUntilChanged().collectLatest { target ->
                if (target != null) {
                    val (owner, peer, date) = target
                    val key = "$owner:${peer.key}"
                    if (date > (acknowledgedReads[key] ?: 0)) quiet {
                        messenger.markRead(peer, date)
                        acknowledgedReads[key] = date
                    }
                }
            }
        }
        viewModelScope.launch {
            connection.collectLatest {
                if (it == Connection.Connected && selected.value != null) loadOlder(first = true)
            }
        }
        viewModelScope.launch {
            var previous = account.value?.id
            account.collect { current ->
                if (current?.id != previous) {
                    historyJob?.cancel(); searchJob?.cancel(); directoryJob?.cancel(); typingJob?.cancel()
                    visibleRead.value = null
                    acknowledgedReads.clear()
                    mutableUploading.value = null
                    mutableComposer.value = ComposerState()
                    mutableSearch.value = SearchState()
                    mutableDownloaded.value = emptyMap()
                    mutableContacts.value = emptyList()
                    mutableDirectoryResults.value = emptyList()
                    mutableStories.value = emptyList()
                    mutableOwnProfile.value = null
                    mutableSessions.value = emptyList()
                    mutableInspectedProfile.value = null
                    mutableViewer.value = null
                    mutableThread.value = null
                    mutablePicker.value = StickerPickerState()
                    mutableSchedule.value = ScheduleState()
                    mutableMiniApp.value = null
                    mutablePreview.value = null
                    mutableVoters.value = null
                    mutableReportTarget.value = null
                    mutableSecurity.value = null
                    mutableSharedMedia.value = SharedMediaState()
                    mutableHandleAvailable.value = null
                    mutableDialogFilter.value = DialogFilter.All
                    mutableFolderFilter.value = 0
                    mutableSection.value = AppSection.Chats
                    mutableQuery.value = ""
                    back()
                    previous = current?.id
                }
            }
        }
    }

    fun selectSection(target: AppSection) {
        mutableSection.value = target
        when (target) {
            AppSection.Chats -> refreshFolders()
            AppSection.Contacts -> refreshContacts()
            AppSection.Stories -> refreshStories()
            AppSection.Settings -> { refreshSettings(); refreshSecurity(); refreshFolders() }
        }
    }

    fun search(value: String) { mutableQuery.value = value }
    fun reportOperationError() { mutableError.value = true }

    fun open(peer: Peer) {
        stopTyping()
        mutableSection.value = AppSection.Chats
        savedState["peerId"] = peer.id; savedState["peerKind"] = peer.kind; savedState["peerHash"] = peer.accessHash
        visibleRead.value = null
        mutableDraftText.value = ""
        mutableSelected.value = peer
        mutableComposer.value = ComposerState()
        mutableInspectedProfile.value = null
        mutableViewer.value = null
        mutableThread.value = null
        mutableSchedule.value = ScheduleState()
        mutableMiniApp.value = null
        mutablePreview.value = null
        mutableMoreHistory.value = true
        loadOlder(first = true)
    }

    fun back() {
        stopTyping()
        savedState.remove<Int>("peerId"); savedState.remove<Int>("peerKind"); savedState.remove<Long>("peerHash")
        visibleRead.value = null
        mutableSelected.value = null
        mutableComposer.value = ComposerState()
        mutableInspectedProfile.value = null
    }

    fun refresh() = operation {
        messenger.refreshConversations()
        selected.value?.let { messenger.loadHistory(it) }
    }

    fun refreshSelectedPolls() {
        val peer = selected.value ?: return
        viewModelScope.launch { quiet { messenger.refreshPolls(peer) } }
    }

    fun moreConversations() = operation { mutableMoreDialogs.value = messenger.loadMoreConversations() >= PAGE_SIZE }

    fun loadOlder(first: Boolean = false) {
        val peer = selected.value ?: return
        if (historyJob?.isActive == true && !first) return
        historyJob?.cancel()
        historyJob = operation {
            val count = if (first) messenger.loadHistory(peer) else messenger.loadOlderHistory(peer)
            if (selected.value?.key == peer.key) mutableMoreHistory.value = count >= PAGE_SIZE
        }
    }

    fun reply(message: ChatMessage) { mutableComposer.value = ComposerState(reply = message) }
    fun edit(message: ChatMessage) { mutableComposer.value = ComposerState(edit = message) }
    fun cancelComposer() { mutableComposer.value = ComposerState() }

    fun send(text: String, onQueued: () -> Unit) {
        val peer = selected.value ?: return
        val origin = account.value?.id ?: return
        val composition = composer.value
        if (mutableQueuing.value) return
        mutableQueuing.value = true
        stopTyping()
        viewModelScope.launch {
            mutableError.value = false
            try {
                val editing = composition.edit
                if (editing != null) {
                    messenger.edit(peer, editing, text)
                    if (account.value?.id == origin && composer.value == composition) mutableComposer.value = ComposerState()
                    mutableDraftText.value = ""
                    onQueued()
                } else {
                    mutableDraftText.value = ""
                    val rid = messenger.queue(peer, text, composition.reply)
                    if (account.value?.id != origin) return@launch
                    onQueued()
                    if (composer.value == composition) mutableComposer.value = ComposerState()
                    mutableQueuing.value = false
                    messenger.retry(peer, rid)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableError.value = true }
            finally { mutableQueuing.value = false }
        }
    }

    private val mutableUploadProgress = MutableStateFlow<Float?>(null)
    val uploadProgress = mutableUploadProgress.asStateFlow()

    private val mutableDownloadProgress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val downloadProgress = mutableDownloadProgress.asStateFlow()

    fun sendAttachment(payload: UploadPayload, caption: String = "") {
        val peer = selected.value ?: return
        val reply = composer.value.reply
        mutableComposer.value = ComposerState()
        operation {
            mutableUploading.value = payload.name
            mutableUploadProgress.value = 0f
            try {
                messenger.sendMediaWithProgress(peer, payload, caption, reply) { progress ->
                    mutableUploadProgress.value = progress.coerceIn(0f, 1f)
                }
            } finally {
                mutableUploading.value = null
                mutableUploadProgress.value = null
            }
        }
    }

    fun downloadAttachment(attachment: Attachment, onReady: ((String) -> Unit)? = null) {
        operation {
            if (attachment.fileId != 0L) mutableDownloadProgress.update { it + (attachment.fileId to 0f) }
            val path = try {
                messenger.downloadMedia(attachment) { progress ->
                    if (attachment.fileId != 0L) {
                        mutableDownloadProgress.update { current -> current + (attachment.fileId to progress.coerceIn(0f, 1f)) }
                    }
                }
            } finally {
                if (attachment.fileId != 0L) mutableDownloadProgress.update { it - attachment.fileId }
            }
            if (path.isNotBlank()) {
                if (attachment.fileId != 0L) mutableDownloaded.update { it + (attachment.fileId to path) }
                onReady?.invoke(path)
            }
        }
    }

    fun retry(message: ChatMessage) { val peer = selected.value ?: return; operation(false) { messenger.retry(peer, message.rid) } }
    fun delete(message: ChatMessage, everyone: Boolean) { val peer = selected.value ?: return; operation { messenger.delete(peer, message, everyone) } }
    fun forward(message: ChatMessage, destination: Peer) {
        val source = selected.value ?: return
        operation { messenger.forward(source, destination, message) }
    }
    fun react(message: ChatMessage, code: String, remove: Boolean) {
        val peer = selected.value ?: return
        operation(false) { messenger.react(peer, message, code, remove) }
    }
    fun pinMessage(message: ChatMessage, pin: Boolean) {
        val peer = selected.value ?: return
        operation {
            messenger.pinMessage(peer, message, pin)
            if (mutableInspectedProfile.value?.peer?.key == peer.key) {
                mutableInspectedProfile.value = messenger.loadProfile(peer)
            }
        }
    }

    fun createPoll(question: String, options: List<String>, anonymous: Boolean, multipleChoice: Boolean) {
        val peer = selected.value ?: return
        operation { messenger.createPoll(peer, question, options, anonymous, multipleChoice) }
    }

    fun votePoll(message: ChatMessage, optionId: Int) {
        val peer = selected.value ?: return
        val poll = message.poll ?: return
        if (poll.closed) return
        val current = poll.options.filter { it.chosen }.map { it.id }
        val next = if (poll.multipleChoice) {
            if (optionId in current) current - optionId else current + optionId
        } else {
            if (optionId in current) emptyList() else listOf(optionId)
        }
        operation(false) { messenger.votePoll(peer, message, next, retract = next.isEmpty()) }
    }

    fun submitPollSelection(message: ChatMessage, optionIds: List<Int>) {
        val peer = selected.value ?: return
        val poll = message.poll ?: return
        if (poll.closed) return
        val clean = optionIds.distinct()
        operation(false) { messenger.votePoll(peer, message, clean, retract = clean.isEmpty()) }
    }

    fun retractPollVote(message: ChatMessage) {
        val peer = selected.value ?: return
        val poll = message.poll ?: return
        if (poll.closed) return
        operation(false) { messenger.votePoll(peer, message, emptyList(), retract = true) }
    }

    fun closePoll(message: ChatMessage) {
        val peer = selected.value ?: return
        operation { messenger.closePoll(peer, message) }
    }

    fun pressBotButton(button: BotButton) {
        val peer = selected.value ?: return
        operation { messenger.pressBotButton(peer, button) }
    }

    // --- Contacts, Communities & Inspector ---
    fun refreshContacts() = operation {
        mutableContacts.value = messenger.contacts()
    }

    fun searchDirectory(query: String) {
        directoryJob?.cancel()
        if (query.isBlank()) {
            mutableDirectoryResults.value = emptyList()
            return
        }
        directoryJob = viewModelScope.launch {
            delay(250)
            try { mutableDirectoryResults.value = messenger.searchDirectory(query) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableError.value = true }
        }
    }

    fun addContact(peer: Peer) = operation {
        messenger.addContact(peer)
        mutableContacts.value = messenger.contacts()
    }

    fun removeContact(peer: Peer) = operation {
        messenger.removeContact(peer)
        mutableContacts.value = messenger.contacts()
    }

    fun createCommunity(title: String, isChannel: Boolean, handle: String, members: List<Peer>) = operation {
        val created = messenger.createCommunity(title, isChannel, handle, members)
        open(created)
    }

    fun joinInviteOrHandle(input: String) = operation {
        val target = messenger.joinInviteOrHandle(input) ?: error("Not found")
        open(target)
    }

    fun inspectSelectedPeer() {
        val peer = selected.value ?: return
        operation {
            mutableContacts.value = runCatching { messenger.contacts() }.getOrDefault(mutableContacts.value)
            mutableInspectedProfile.value = messenger.loadProfile(peer)
        }
    }

    fun dismissInspector() { mutableInspectedProfile.value = null }

    fun setBlocked(peer: Peer, blocked: Boolean) = operation {
        messenger.setBlocked(peer, blocked)
        mutableInspectedProfile.value = messenger.loadProfile(peer)
    }

    fun clearSelectedChat() {
        val peer = selected.value ?: return
        operation {
            messenger.clearChat(peer)
            mutableInspectedProfile.value = null
        }
    }

    fun deleteSelectedChat() {
        val peer = selected.value ?: return
        operation {
            messenger.deleteChat(peer)
            back()
        }
    }

    fun setDialogPinned(peer: Peer, pinned: Boolean) = operation {
        messenger.setDialogPinned(peer, pinned)
        mutableInspectedProfile.value = null
    }

    fun setDialogArchived(peer: Peer, archived: Boolean) = operation {
        messenger.setDialogArchived(peer, archived)
        mutableInspectedProfile.value = null
    }

    fun editCommunity(peer: Peer, title: String, about: String) = operation {
        messenger.editCommunity(peer, title, about)
        mutableInspectedProfile.value = messenger.loadProfile(peer)
    }

    fun leaveCommunity(peer: Peer) = operation {
        messenger.leaveCommunity(peer)
        back()
    }

    fun inviteMember(group: Peer, user: Peer) = operation {
        messenger.inviteMember(group, user)
        mutableInspectedProfile.value = messenger.loadProfile(group)
    }

    fun kickMember(group: Peer, user: Peer) = operation {
        messenger.kickMember(group, user)
        mutableInspectedProfile.value = messenger.loadProfile(group)
    }

    fun setMemberAdmin(group: Peer, user: Peer, admin: Boolean) = operation {
        messenger.setMemberAdmin(group, user, admin)
        mutableInspectedProfile.value = messenger.loadProfile(group)
    }

    fun revokeInviteLink(group: Peer) = operation {
        messenger.revokeInviteLink(group)
        mutableInspectedProfile.value = messenger.loadProfile(group)
    }

    // --- Stories & Settings ---
    fun refreshStories() = operation {
        mutableStories.value = messenger.loadStories()
    }

    fun postTextStory(text: String) = operation {
        messenger.postTextStory(text)
        mutableStories.value = messenger.loadStories()
    }

    fun reactStory(storyId: String, emoji: String) = operation(false) {
        messenger.reactStory(storyId, emoji)
        mutableStories.value = messenger.loadStories()
    }

    fun deleteStory(storyId: String) = operation {
        messenger.deleteStory(storyId)
        mutableStories.value = messenger.loadStories()
    }

    fun refreshSettings() = operation {
        val me = account.value ?: return@operation
        mutableOwnProfile.value = runCatching { messenger.loadProfile(Peer(me.id, 1, 0)) }.getOrNull()
        mutableSessions.value = runCatching { auth.sessions() }.getOrDefault(emptyList())
    }

    fun saveOwnProfile(name: String, handle: String, about: String) = operation {
        messenger.updateOwnProfile(name, handle, about)
        val me = account.value ?: return@operation
        mutableOwnProfile.value = runCatching { messenger.loadProfile(Peer(me.id, 1, 0)) }.getOrNull()
    }

    fun uploadOwnAvatar(payload: UploadPayload) = operation {
        mutableUploading.value = payload.name
        try {
            messenger.uploadOwnAvatar(payload)
        } finally {
            mutableUploading.value = null
        }
    }

    fun terminateSession(id: Int) = operation {
        auth.terminateSession(id)
        mutableSessions.value = auth.sessions()
    }

    fun terminateOtherSessions() = operation {
        auth.terminateOtherSessions()
        mutableSessions.value = auth.sessions()
    }

    fun read(date: Long) { if (date > (visibleRead.value ?: 0)) visibleRead.value = date }
    fun signOut() = operation { auth.signOut() }
    fun openSearch() { mutableSearch.value = SearchState(open = true) }
    fun closeSearch() { searchJob?.cancel(); mutableSearch.value = SearchState() }
    fun searchServer(query: String, currentChat: Boolean) {
        if (query.isBlank()) return
        searchJob?.cancel()
        val peer = if (currentChat) selected.value else null
        searchJob = viewModelScope.launch {
            mutableSearch.value = SearchState(open = true, busy = true)
            try { mutableSearch.value = SearchState(open = true, page = messenger.searchMessages(query, peer)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableSearch.value = SearchState(open = true, failed = true) }
        }
    }
    fun openSearchHit(hit: SearchHit) { closeSearch(); open(hit.peer) }

    fun draftChanged(value: String) {
        mutableDraftText.value = value
        val peer = selected.value ?: return
        typingJob?.cancel()
        if (value.isBlank()) { stopTyping(); return }
        val now = System.currentTimeMillis()
        if (now - lastTyping > 3_000) {
            lastTyping = now
            viewModelScope.launch { quiet { messenger.setTyping(peer, true) } }
        }
        typingJob = viewModelScope.launch { delay(4_000); quiet { messenger.setTyping(peer, false) } }
    }

    fun stopTyping() {
        typingJob?.cancel()
        lastTyping = 0
        val peer = selected.value ?: return
        viewModelScope.launch { quiet { messenger.setTyping(peer, false) } }
    }

    // ---------------------------------------------------------------
    // Release-candidate surfaces: folders & filters, avatars, media
    // viewer, stickers & GIFs, threads, scheduling, Mini-Apps,
    // link previews, voters, moderation, security and appearance.
    // ---------------------------------------------------------------
    val preferences = messenger.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, ClientPreferences())
    val avatarPaths = messenger.avatarPaths
    val folders = messenger.folders

    private val mutableFolderFilter = MutableStateFlow(0)
    val folderFilter = mutableFolderFilter.asStateFlow()
    private val mutableDraftText = MutableStateFlow("")
    private val mutableDialogFilter = MutableStateFlow(DialogFilter.All)
    val dialogFilter = mutableDialogFilter.asStateFlow()

    val visibleChats = combine(conversations, query, folders, mutableFolderFilter, mutableDialogFilter) { items, text, packs, folderId, filter ->
        val scoped = if (folderId == 0) items else items.filter { chat ->
            packs.firstOrNull { it.id == folderId }?.peerKeys?.contains(chat.peer.key) == true
        }
        scoped.filter { it.matches(text, filter) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setDialogFilter(filter: DialogFilter) { mutableDialogFilter.value = filter }
    fun assignChatToFolder(peer: Peer, folderId: Int) = operation {
        if (folderId != 0) messenger.addChatToFolder(peer, folderId)
        messenger.refreshFolders()
    }

    fun removeFromFolder(peer: Peer, folderId: Int) = operation {
        messenger.removeChatFromFolder(peer, folderId)
        messenger.refreshFolders()
    }

    fun selectFolder(id: Int) {
        mutableFolderFilter.value = id
        if (id != 0) refreshFolders()
    }

    fun refreshFolders() = operation(false) { messenger.refreshFolders() }

    fun createFolder(name: String) = operation {
        val peers = visibleChats.value.map { it.peer }
        val folderId = messenger.createFolder(name, peers)
        messenger.refreshFolders()
        mutableFolderFilter.value = folderId
        mutableDialogFilter.value = DialogFilter.All
    }

    fun deleteFolder(id: Int) = operation {
        messenger.deleteFolder(id)
        if (mutableFolderFilter.value == id) mutableFolderFilter.value = 0
        messenger.refreshFolders()
    }

    fun openSavedMessages() = operation { messenger.openSavedMessages()?.let { open(it) } }

    fun toggleMute(peer: Peer) = operation(false) {
        val muted = conversations.value.firstOrNull { it.peer.key == peer.key }?.muted == true
        messenger.setDialogMuted(peer, !muted)
    }

    fun toggleMarkedUnread(peer: Peer) = operation(false) {
        val unread = conversations.value.firstOrNull { it.peer.key == peer.key }?.markedUnread == true
        messenger.setDialogMarkedUnread(peer, !unread)
    }

    private var draftJob: Job? = null

    /** Debounced so typing does not rewrite the preferences snapshot on every keystroke. */
    fun persistDraft(peer: Peer, draft: String) {
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(700)
            quiet { messenger.saveDraft(peer, draft) }
        }
    }

    fun openMiniAppFrom(url: String) {
        val peer = selected.value ?: return
        if (url.startsWith("https://")) {
            mutableMiniApp.value = MiniAppState(bot = peer, session = MiniAppSession(url = url, queryId = ""))
        } else {
            openMiniApp(url)
        }
    }

    /** Warms the on-disk avatar cache for the peers currently visible on screen. */
    fun ensureAvatars(refs: List<AvatarRef?>) {
        val targets = refs.filterNotNull().distinct().take(AVATAR_WARM_BATCH)
        if (targets.isEmpty()) return
        viewModelScope.launch { targets.forEach { avatar -> quiet { messenger.ensureAvatar(avatar) } } }
    }

    // --- Full-screen media viewer, transcripts and GIF saving ---
    private val mutableViewer = MutableStateFlow<ViewerState?>(null)
    val viewer = mutableViewer.asStateFlow()

    fun openViewer(message: ChatMessage) {
        val attachment = message.attachment ?: return
        mutableViewer.value = ViewerState(message = message)
        viewModelScope.launch {
            try {
                val path = messenger.downloadMedia(attachment) { progress ->
                    if (viewer.value?.message?.rid == message.rid) {
                        mutableViewer.update { current -> current?.copy(progress = progress.coerceIn(0f, 1f)) ?: current }
                    }
                }
                if (viewer.value?.message?.rid == message.rid) {
                    mutableViewer.value = ViewerState(message = message, path = path, busy = false, failed = path.isBlank(), progress = 1f)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (viewer.value?.message?.rid == message.rid) {
                    mutableViewer.value = ViewerState(message = message, busy = false, failed = true)
                }
            }
        }
    }

    fun closeViewer() { mutableViewer.value = null }

    fun transcribeVoiceInViewer() {
        val current = viewer.value ?: return
        val peer = selected.value ?: return
        mutableViewer.value = current.copy(transcribing = true, transcript = null)
        viewModelScope.launch {
            val text = runCatching { messenger.transcribeVoice(peer, current.message) }
            mutableViewer.value = viewer.value?.copy(
                transcribing = false,
                transcript = text.getOrNull().orEmpty(),
                transcriptFailed = text.isFailure,
            )
        }
    }

    fun saveViewerGif() {
        val attachment = viewer.value?.message?.attachment ?: return
        operation { messenger.saveGif(attachment) }
    }

    // --- Sticker and GIF picker ---
    private val mutablePicker = MutableStateFlow(StickerPickerState())
    val picker = mutablePicker.asStateFlow()

    fun openPicker(mode: PickerMode) {
        mutablePicker.value = StickerPickerState(mode = mode, busy = true)
        viewModelScope.launch {
            try {
                when (mode) {
                    PickerMode.Stickers -> {
                        val packs = messenger.loadStickerPacks()
                        mutablePicker.value = StickerPickerState(mode = mode, packs = packs)
                        // Warm the same on-disk cache used for avatars so the grid can render previews.
                        ensureAvatars(packs.asSequence().flatMap { it.stickers.asSequence() }
                            .filter { it.fileId != 0L }
                            .map { AvatarRef(it.fileId, it.accessHash) }
                            .toList())
                    }
                    PickerMode.Gifs -> mutablePicker.value = StickerPickerState(mode = mode, gifs = messenger.loadSavedGifs())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutablePicker.value = StickerPickerState(mode = mode, failed = true)
            }
        }
    }

    fun closePicker() { mutablePicker.value = StickerPickerState() }

    fun sendSticker(sticker: StickerItem) {
        val peer = selected.value ?: return
        closePicker()
        operation(false) { messenger.sendSticker(peer, sticker) }
    }

    fun sendGif(gif: SavedGif) {
        val peer = selected.value ?: return
        closePicker()
        operation(false) { messenger.sendSavedGif(peer, gif) }
    }

    fun removeGif(gif: SavedGif) {
        operation(false) {
            messenger.removeGif(gif)
            mutablePicker.value = mutablePicker.value.copy(gifs = messenger.loadSavedGifs())
        }
    }

    // --- Threads and channel comments ---
    private val mutableThread = MutableStateFlow<ThreadState?>(null)
    val thread = mutableThread.asStateFlow()

    fun openThread(root: ChatMessage) {
        val peer = selected.value ?: return
        mutableThread.value = ThreadState(root = root, busy = true)
        viewModelScope.launch {
            val replies = runCatching { messenger.loadThreadReplies(peer, root) }
            if (thread.value?.root?.rid == root.rid) {
                mutableThread.value = ThreadState(
                    root = root,
                    replies = replies.getOrDefault(emptyList()),
                    busy = false,
                    failed = replies.isFailure,
                )
            }
        }
    }

    fun closeThread() { mutableThread.value = null }

    fun sendThreadReply(text: String) {
        val peer = selected.value ?: return
        val root = thread.value?.root ?: return
        if (text.isBlank()) return
        operation(false) {
            messenger.sendThreadReply(peer, root, text)
            messenger.loadThreadReplies(peer, root).let { replies ->
                mutableThread.value = ThreadState(root = root, replies = replies)
            }
        }
    }

    // --- Scheduled messages ---
    private val mutableSchedule = MutableStateFlow(ScheduleState())
    val schedule = mutableSchedule.asStateFlow()

    fun openScheduleComposer() { mutableSchedule.update { it.copy(composing = true) } }
    fun closeScheduleComposer() { mutableSchedule.update { it.copy(composing = false) } }

    fun scheduleMessage(peer: Peer, minutesFromNow: Long) {
        val text = draftSnapshot()
        if (text.isBlank()) {
            mutableError.value = true
            return
        }
        operation {
            messenger.scheduleMessage(peer, text, System.currentTimeMillis() + minutesFromNow * 60_000L)
            mutableDraftText.value = ""
            mutableSchedule.value = ScheduleState(items = messenger.listScheduledMessages(peer), open = true)
        }
    }

    /** Latest composer text, captured so secondary actions (schedule, preview) never race the field. */
    fun draftSnapshot(): String = mutableDraftText.value

    fun openScheduledList() {
        val peer = selected.value ?: return
        mutableSchedule.value = ScheduleState(busy = true, open = true)
        viewModelScope.launch {
            try {
                mutableSchedule.value = ScheduleState(items = messenger.listScheduledMessages(peer), open = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableSchedule.value = ScheduleState(open = true, failed = true)
            }
        }
    }

    fun closeScheduledList() { mutableSchedule.value = ScheduleState() }

    fun sendScheduledNow(taskId: Long) = operation(false) {
        val peer = selected.value ?: return@operation
        messenger.sendScheduledNow(taskId)
        mutableSchedule.value = ScheduleState(items = messenger.listScheduledMessages(peer), open = true)
    }

    fun cancelScheduled(taskId: Long) = operation(false) {
        val peer = selected.value ?: return@operation
        messenger.cancelScheduledMessage(taskId)
        mutableSchedule.value = ScheduleState(items = messenger.listScheduledMessages(peer), open = true)
    }

    // --- Bot Mini-Apps ---
    private val mutableMiniApp = MutableStateFlow<MiniAppState?>(null)
    val miniApp = mutableMiniApp.asStateFlow()

    fun openMiniApp(startParam: String = "") {
        val peer = selected.value ?: return
        operation {
            val session = messenger.openMiniApp(peer, startParam)
            if (session == null) mutableError.value = true else mutableMiniApp.value = MiniAppState(bot = peer, session = session)
        }
    }

    fun closeMiniApp() { mutableMiniApp.value = null }

    fun submitMiniAppQuery(queryId: String, data: String, buttonText: String) {
        val peer = mutableMiniApp.value?.bot ?: return
        viewModelScope.launch { quiet { messenger.submitMiniAppQuery(peer, queryId, data, buttonText) } }
        closeMiniApp()
    }

    // --- Link previews ---
    private val mutablePreview = MutableStateFlow<LinkPreviewState?>(null)
    val linkPreview = mutablePreview.asStateFlow()

    fun previewLink(url: String, withSummary: Boolean = false) {
        val clean = url.trim()
        if (clean.isBlank()) return
        mutablePreview.value = LinkPreviewState(url = clean, busy = true, withSummary = withSummary)
        viewModelScope.launch {
            val info = runCatching { messenger.fetchLinkPreview(clean, withSummary) }.getOrNull()
            if (linkPreview.value?.url == clean) {
                mutablePreview.value = LinkPreviewState(url = clean, info = info, busy = false, withSummary = withSummary)
            }
        }
    }

    fun clearLinkPreview() { mutablePreview.value = null }

    // --- Voters for polls and reactions ---
    private val mutableVoters = MutableStateFlow<VotersState?>(null)
    val voters = mutableVoters.asStateFlow()

    fun showPollVoters(message: ChatMessage) {
        val peer = selected.value ?: return
        mutableVoters.value = VotersState(title = message.poll?.question ?: "Poll", busy = true)
        viewModelScope.launch {
            val options = runCatching { messenger.fullPollVoters(peer, message) }.getOrDefault(emptyList())
            mutableVoters.value = VotersState(
                title = message.poll?.question ?: "Poll",
                poll = options,
                busy = false,
                failed = options.isEmpty(),
            )
        }
    }

    fun showReactionVoters(message: ChatMessage) {
        val peer = selected.value ?: return
        mutableVoters.value = VotersState(title = message.text.ifBlank { "Reactions" }, busy = true)
        viewModelScope.launch {
            val entries = runCatching { messenger.reactionVoters(peer, message) }.getOrDefault(emptyList())
            mutableVoters.value = VotersState(
                title = message.text.ifBlank { "Reactions" },
                reactions = entries,
                busy = false,
                failed = entries.isEmpty(),
            )
        }
    }

    fun closeVoters() { mutableVoters.value = null }

    // --- Moderation ---
    private val mutableReportTarget = MutableStateFlow<ChatMessage?>(null)
    val reportTarget = mutableReportTarget.asStateFlow()

    fun reportMessage(message: ChatMessage?) { mutableReportTarget.value = message }

    fun reportMessageFor(message: ChatMessage, reason: String) {
        val peer = selected.value ?: return
        operation { messenger.reportPeerOrMessage(peer, message, reason) }
    }
    fun dismissReport() { mutableReportTarget.value = null }

    fun submitReport(reason: String) {
        val peer = selected.value ?: return
        val message = reportTarget.value
        dismissReport()
        operation { messenger.reportPeerOrMessage(peer, message, reason) }
    }

    // --- Appearance, security and privacy ---
    fun setThemeMode(mode: AppThemeMode) { viewModelScope.launch { quiet { messenger.updatePreferences { it.copy(themeMode = mode) } } } }
    fun setDynamicColor(enabled: Boolean) { viewModelScope.launch { quiet { messenger.updatePreferences { it.copy(dynamicColor = enabled) } } } }
    fun setLanguage(language: AppLanguage) { viewModelScope.launch { quiet { messenger.updatePreferences { it.copy(language = language) } } } }

    private val mutableSecurity = MutableStateFlow<SecurityAndPrivacyState?>(null)
    val security = mutableSecurity.asStateFlow()

    fun refreshSecurity() = operation(false) {
        mutableSecurity.value = messenger.loadSecurityAndPrivacy()
    }

    fun enableTwoFactor(email: String, password: String) = operation {
        auth.enableTwoFactor(email, password)
        mutableSecurity.value = messenger.loadSecurityAndPrivacy()
    }

    fun disableTwoFactor() = operation {
        auth.disableTwoFactor()
        mutableSecurity.value = messenger.loadSecurityAndPrivacy()
    }

    // --- Community administration, shared media gallery and drafts ---
    private val mutableSharedMedia = MutableStateFlow(SharedMediaState())
    val sharedMedia = mutableSharedMedia.asStateFlow()

    fun openSharedMedia(tab: SharedMediaTab = SharedMediaTab.Media) {
        val peer = selected.value ?: return
        mutableSharedMedia.value = SharedMediaState(tab = tab, busy = true, open = true)
        viewModelScope.launch {
            try {
                mutableSharedMedia.value = SharedMediaState(tab = tab, items = messenger.loadSharedMedia(peer, tab), open = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableSharedMedia.value = SharedMediaState(tab = tab, open = true, failed = true)
            }
        }
    }

    fun selectSharedMediaTab(tab: SharedMediaTab) = openSharedMedia(tab)
    fun closeSharedMedia() { mutableSharedMedia.value = SharedMediaState() }

    fun uploadCommunityAvatar(payload: UploadPayload) {
        val peer = mutableInspectedProfile.value?.peer ?: return
        operation {
            mutableUploading.value = payload.name
            try {
                messenger.uploadGroupAvatar(peer, payload)
                mutableInspectedProfile.value = messenger.loadProfile(peer)
            } finally {
                mutableUploading.value = null
            }
        }
    }

    fun updateCommunityHandle(handle: String) {
        val peer = mutableInspectedProfile.value?.peer ?: return
        operation {
            messenger.updateChannelHandle(peer, handle)
            mutableInspectedProfile.value = messenger.loadProfile(peer)
        }
    }

    fun checkCommunityHandle(handle: String) {
        viewModelScope.launch {
            mutableHandleAvailable.value = messenger.checkUsernameAvailable(handle)
        }
    }

    private val mutableHandleAvailable = MutableStateFlow<Boolean?>(null)
    val handleAvailable = mutableHandleAvailable.asStateFlow()

    fun reportConversation(reason: String) {
        val peer = selected.value ?: return
        operation { messenger.reportPeerOrMessage(peer, null, reason) }
    }

    fun draftFor(peer: Peer): String = conversations.value.firstOrNull { it.peer.key == peer.key }?.draft.orEmpty()

    private fun operation(showLoading: Boolean = true, block: suspend () -> Unit): Job = viewModelScope.launch {
        if (showLoading) operations.update { it + 1 }
        mutableError.value = false
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableError.value = true }
        finally { if (showLoading) operations.update { it - 1 } }
    }

    private suspend fun quiet(block: suspend () -> Unit) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* typing is best effort */ }
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val AVATAR_WARM_BATCH = 12
    }
}
