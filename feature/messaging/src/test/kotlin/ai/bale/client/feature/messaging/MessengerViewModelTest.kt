package ai.bale.client.feature.messaging

import ai.bale.client.domain.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.paging.PagingData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessengerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val peer = Peer(42, 1, 123)
    private val message = ChatMessage(peer.key, 99, 7, "question", 1000, Delivery.Sent)
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }

    private class FakeAuth : AuthRepository {
        override val account = MutableStateFlow<Account?>(Account(7, "fixture"))
        override suspend fun restore() = Unit
        override suspend fun start(phone: String): AuthChallenge = error("unused")
        override suspend fun verify(challenge: AuthChallenge, value: String): AuthChallenge? = error("unused")
        override suspend fun signOut() { account.value = null }
        override suspend fun sessions() = listOf(AuthSessionInfo(9, "Android", "Pixel", "Tehran", 100, 200))
    }

    private class FakeMessenger : MessengerRepository {
        override val connection = MutableStateFlow(Connection.Connected)
        val events = mutableListOf<String>()
        val typingCalls = mutableListOf<Boolean>()
        var quoted: ChatMessage? = null
        var failQueue = false
        var searchPeer: Peer? = null
        override fun conversations() = flowOf(emptyList<Conversation>())
        override fun messages(peer: Peer) = flowOf(emptyList<ChatMessage>())
        override fun pagedMessages(peer: Peer) = flowOf(PagingData.empty<ChatMessage>())
        override fun typingUsers(peer: Peer) = flowOf(emptySet<Int>())
        override suspend fun loadMoreConversations() = 0
        override suspend fun refreshConversations() = Unit
        override suspend fun loadHistory(peer: Peer, before: Long) = 0
        override suspend fun loadOlderHistory(peer: Peer) = 0
        override suspend fun queue(peer: Peer, text: String, reply: ChatMessage?): Long {
            events += "queue"
            if (failQueue) error("fixture enqueue failure")
            quoted = reply
            return 111
        }
        override suspend fun retry(peer: Peer, rid: Long) { events += "retry:$rid" }
        override suspend fun markRead(peer: Peer, date: Long) { events += "read:$date" }
        override suspend fun edit(peer: Peer, message: ChatMessage, text: String) { events += "edit:$text" }
        override suspend fun delete(peer: Peer, message: ChatMessage, forEveryone: Boolean) = Unit
        override suspend fun forward(from: Peer, to: Peer, message: ChatMessage) = Unit
        override suspend fun react(peer: Peer, message: ChatMessage, code: String, remove: Boolean) = Unit
        override suspend fun searchMessages(query: String, peer: Peer?): SearchPage {
            if (query == "old") awaitCancellation()
            searchPeer = peer
            return SearchPage(emptyList(), 123)
        }
        override suspend fun setTyping(peer: Peer, active: Boolean) { typingCalls += active }
        override suspend fun votePoll(peer: Peer, message: ChatMessage, optionIds: List<Int>, retract: Boolean) {
            events += "vote:${optionIds.joinToString(",")}:$retract"
        }
        override suspend fun contacts() = listOf(ContactEntry(Peer(55, 1, 777), "Sara", "sara"))
        override suspend fun loadStories() = listOf(StoryItem("st1", 55, "Sara", "Hello world", 5000L))
        override suspend fun createCommunity(title: String, isChannel: Boolean, handle: String, members: List<Peer>) =
            Peer(888, 2, 999)
    }

    private fun model(repo: FakeMessenger, auth: FakeAuth = FakeAuth(), state: SavedStateHandle = SavedStateHandle()): MessengerViewModel =
        MessengerViewModel(repo, auth, state).also { store.put("fixture", it) }

    @Test fun `reply is queued once before clearing draft and attempting delivery`() = runTest(dispatcher) {
        val repo = FakeMessenger()
        val model = model(repo)
        model.open(peer); runCurrent()
        model.reply(message)
        var cleared = 0
        model.send("answer") { assertEquals(listOf("queue"), repo.events); cleared++ }
        model.send("duplicate") { cleared++ }
        runCurrent()
        assertEquals(1, cleared)
        assertEquals(message, repo.quoted)
        assertEquals(listOf("queue", "retry:111"), repo.events)
        assertNull(model.composer.value.reply)
    }

    @Test fun `enqueue failure retains draft callback and reply context`() = runTest(dispatcher) {
        val model = model(FakeMessenger().apply { failQueue = true })
        model.open(peer); runCurrent(); model.reply(message)
        var cleared = false
        model.send("answer") { cleared = true }; runCurrent()
        assertFalse(cleared)
        assertEquals(message, model.composer.value.reply)
        assertTrue(model.error.value)
        assertFalse(model.queuing.value)
    }

    @Test fun `edit replaces existing message without creating another outbox entry`() = runTest(dispatcher) {
        val repo = FakeMessenger()
        val model = model(repo)
        model.open(peer); runCurrent(); model.edit(message)
        model.send("replacement") {}; runCurrent()
        assertEquals(listOf("edit:replacement"), repo.events)
        assertNull(model.composer.value.edit)
    }

    @Test fun `logout clears saved peer query composer and search state`() = runTest(dispatcher) {
        val auth = FakeAuth()
        val state = SavedStateHandle()
        val model = model(FakeMessenger(), auth, state)
        model.open(peer); runCurrent(); model.reply(message); model.search("private"); model.openSearch()
        auth.account.value = null; runCurrent()
        assertNull(model.selected.value)
        assertNull(state.get<Int>("peerId"))
        assertEquals("", model.query.value)
        assertEquals(ComposerState(), model.composer.value)
        assertFalse(model.searchState.value.open)
    }

    @Test fun `new search cancels stale response and scopes to selected peer`() = runTest(dispatcher) {
        val repo = FakeMessenger()
        val model = model(repo)
        model.open(peer); runCurrent(); model.openSearch()
        model.searchServer("old", false); runCurrent()
        model.searchServer("new", true); runCurrent()
        assertEquals(123, model.searchState.value.page?.total)
        assertEquals(peer, repo.searchPeer)
        assertFalse(model.searchState.value.busy)
    }

    @Test fun `typing is throttled and automatically stopped`() = runTest(dispatcher) {
        val repo = FakeMessenger()
        val model = model(repo)
        model.open(peer); runCurrent()
        model.draftChanged("h"); model.draftChanged("hello"); runCurrent()
        assertEquals(listOf(true), repo.typingCalls)
        advanceTimeBy(4_001); runCurrent()
        assertEquals(listOf(true, false), repo.typingCalls)
    }

    @Test fun `read acknowledgements coalesce and never move backwards`() = runTest(dispatcher) {
        val repo = FakeMessenger()
        val model = model(repo)
        model.open(peer); runCurrent()
        model.read(100); model.read(200); model.read(150)
        advanceTimeBy(301); runCurrent()
        assertEquals(listOf("read:200"), repo.events)
        model.read(180); advanceTimeBy(301); runCurrent()
        assertEquals(listOf("read:200"), repo.events)
    }

    @Test fun `sections poll voting and community creation update state cleanly`() = runTest(dispatcher) {
        val repo = FakeMessenger()
        val model = model(repo)
        model.selectSection(AppSection.Contacts); runCurrent()
        assertEquals("Sara", model.contacts.value.single().name)
        model.selectSection(AppSection.Stories); runCurrent()
        assertEquals("Hello world", model.stories.value.single().text)
        model.selectSection(AppSection.Settings); runCurrent()
        assertEquals(9, model.sessions.value.single().id)

        model.createCommunity("Android Team", isChannel = false, handle = "", members = emptyList())
        runCurrent()
        assertEquals("2:888", model.selected.value?.key)

        val pollMessage = message.copy(poll = PollView(10, "Ready?", listOf(PollOptionView(0, "Yes", 1, true), PollOptionView(1, "No", 0, false)), true, false, false, 1))
        model.votePoll(pollMessage, 0); runCurrent()
        model.votePoll(pollMessage, 1); runCurrent()
        assertEquals(listOf("vote::true", "vote:1:false"), repo.events)

        repo.events.clear()
        model.submitPollSelection(pollMessage, listOf(0, 1)); runCurrent()
        model.retractPollVote(pollMessage); runCurrent()
        assertEquals(listOf("vote:0,1:false", "vote::true"), repo.events)
    }
}
