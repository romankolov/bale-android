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

data class ComposerState(val reply: ChatMessage? = null, val edit: ChatMessage? = null)
data class SearchState(val open: Boolean = false, val busy: Boolean = false, val page: SearchPage? = null, val failed: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MessengerViewModel @Inject constructor(
    private val messenger: MessengerRepository,
    private val auth: AuthRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val account = auth.account
    val connection = messenger.connection
    val conversations = messenger.conversations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
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
    private var historyJob: Job? = null
    private var searchJob: Job? = null
    private var typingJob: Job? = null
    private var lastTyping = 0L
    private val visibleRead = MutableStateFlow<Long?>(null)
    private val acknowledgedReads = mutableMapOf<String, Long>()

    init {
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
                    historyJob?.cancel(); searchJob?.cancel(); typingJob?.cancel()
                    visibleRead.value = null
                    acknowledgedReads.clear()
                    mutableComposer.value = ComposerState()
                    mutableSearch.value = SearchState()
                    mutableQuery.value = ""
                    back()
                    previous = current?.id
                }
            }
        }
    }
    fun search(value: String) { mutableQuery.value = value }
    fun open(peer: Peer) {
        stopTyping()
        savedState["peerId"] = peer.id; savedState["peerKind"] = peer.kind; savedState["peerHash"] = peer.accessHash
        visibleRead.value = null
        mutableSelected.value = peer
        mutableComposer.value = ComposerState()
        mutableMoreHistory.value = true
        loadOlder(first = true)
    }
    fun back() {
        stopTyping()
        savedState.remove<Int>("peerId"); savedState.remove<Int>("peerKind"); savedState.remove<Long>("peerHash")
        visibleRead.value = null
        mutableSelected.value = null
        mutableComposer.value = ComposerState()
    }
    fun refresh() = operation {
        messenger.refreshConversations()
        selected.value?.let { messenger.loadHistory(it) }
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
                    onQueued()
                } else {
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
    companion object { private const val PAGE_SIZE = 50 }
}
