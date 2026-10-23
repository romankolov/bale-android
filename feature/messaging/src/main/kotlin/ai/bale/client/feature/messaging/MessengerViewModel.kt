package ai.bale.client.feature.messaging

import ai.bale.client.domain.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    private val mutableSelected = MutableStateFlow(
        savedState.get<Int>("peerId")?.let { Peer(it, savedState.get<Int>("peerKind") ?: 1, savedState.get<Long>("peerHash") ?: 0) })
    val selected = mutableSelected.asStateFlow()
    val messages = selected.flatMapLatest { if (it == null) flowOf(emptyList()) else messenger.messages(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mutableQuery = MutableStateFlow("")
    val query = mutableQuery.asStateFlow()
    fun search(value: String) { mutableQuery.value = value }
    val filtered = combine(conversations, query) { items, text -> items.filter { it.title.contains(text, ignoreCase = true) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mutableError = MutableStateFlow(false)
    val error = mutableError.asStateFlow()
    private val mutableLoading = MutableStateFlow(false)
    val loading = mutableLoading.asStateFlow()
    init {
        viewModelScope.launch {
            connection.collectLatest {
                if (it == Connection.Connected) selected.value?.let { peer ->
                    try { messenger.loadHistory(peer) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutableError.value = true }
                }
            }
        }
    }
    fun open(peer: Peer) {
        savedState["peerId"] = peer.id; savedState["peerKind"] = peer.kind; savedState["peerHash"] = peer.accessHash
        mutableSelected.value = peer
        loadOlder(first = true)
    }
    fun back() {
        savedState.remove<Int>("peerId"); savedState.remove<Int>("peerKind"); savedState.remove<Long>("peerHash")
        mutableSelected.value = null
    }
    fun refresh() = operation { messenger.refreshConversations(); selected.value?.let { messenger.loadHistory(it) } }
    fun loadOlder(first: Boolean = false) {
        val peer = selected.value ?: return
        val before = if (first) -1 else messages.value.filter { it.peerKey == peer.key && it.delivery == Delivery.Sent }.minOfOrNull { it.date } ?: -1
        operation { messenger.loadHistory(peer, before) }
    }
    private val mutableQueuing = MutableStateFlow(false)
    val queuing = mutableQueuing.asStateFlow()
    fun send(text: String, onQueued: () -> Unit) {
        val peer = selected.value ?: return
        if (mutableQueuing.value) return
        mutableQueuing.value = true
        viewModelScope.launch {
            mutableError.value = false
            try {
                val rid = messenger.queue(peer, text)
                onQueued()
                mutableQueuing.value = false
                messenger.retry(peer, rid)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { mutableError.value = true } finally { mutableQueuing.value = false }
        }
    }
    fun retry(rid: Long) { val peer = selected.value ?: return; operation(showLoading = false) { messenger.retry(peer, rid) } }
    fun read(date: Long) { val peer = selected.value ?: return; operation(showLoading = false) { messenger.markRead(peer, date) } }
    fun signOut() = operation { auth.signOut() }
    private fun operation(showLoading: Boolean = true, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (showLoading) mutableLoading.value = true
            mutableError.value = false
            try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { mutableError.value = true } finally { if (showLoading) mutableLoading.value = false }
        }
    }
}
