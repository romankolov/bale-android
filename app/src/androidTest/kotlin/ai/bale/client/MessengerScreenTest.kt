package ai.bale.client

import ai.bale.client.designsystem.BaleTheme
import ai.bale.client.domain.*
import ai.bale.client.feature.messaging.MessengerScreen
import ai.bale.client.feature.messaging.MessengerViewModel
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.paging.PagingData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class MessengerScreenTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    @After fun teardown() { compose.runOnIdle { store.clear() } }
    private val peer = Peer(42, 1, 123)
    private val message = ChatMessage(peer.key, 99, 42, "question from fixture", 1761177600000, Delivery.Sent)
    private class FakeAuth : AuthRepository {
        override val account = MutableStateFlow<Account?>(Account(7, "fixture"))
        override suspend fun restore() = Unit
        override suspend fun start(phone: String): AuthChallenge = error("unused")
        override suspend fun verify(challenge: AuthChallenge, value: String): AuthChallenge? = error("unused")
        override suspend fun signOut() { account.value = null }
    }
    private class FakeMessenger(private val peer: Peer, private val message: ChatMessage) : MessengerRepository {
        override val connection = MutableStateFlow(Connection.Connected)
        private val items = MutableStateFlow(listOf(message))
        var queuedReply: ChatMessage? = null
        var queuedText: String? = null
        override fun conversations() = flowOf(listOf(Conversation(peer, "Test conversation", message.text, message.date, 0)))
        override fun messages(peer: Peer) = flowOf(listOf(message))
        override fun pagedMessages(peer: Peer) = items.map { PagingData.from(it) }
        override fun typingUsers(peer: Peer) = flowOf(emptySet<Int>())
        override suspend fun refreshConversations() = Unit
        override suspend fun loadMoreConversations() = 0
        override suspend fun loadHistory(peer: Peer, before: Long) = 1
        override suspend fun loadOlderHistory(peer: Peer) = 0
        override suspend fun queue(peer: Peer, text: String, reply: ChatMessage?): Long {
            queuedReply = reply; queuedText = text
            items.value = listOf(ChatMessage(peer.key, 123, 7, text, message.date + 1000, Delivery.Pending)) + items.value
            return 123
        }
        override suspend fun retry(peer: Peer, rid: Long) = Unit
        override suspend fun markRead(peer: Peer, date: Long) = Unit
        override suspend fun edit(peer: Peer, message: ChatMessage, text: String) = Unit
        override suspend fun delete(peer: Peer, message: ChatMessage, forEveryone: Boolean) = Unit
        override suspend fun forward(from: Peer, to: Peer, message: ChatMessage) = Unit
        override suspend fun react(peer: Peer, message: ChatMessage, code: String, remove: Boolean) = Unit
        override suspend fun searchMessages(query: String, peer: Peer?) = SearchPage(listOf(SearchHit(this.peer, "Test conversation", message)), 1)
        override suspend fun setTyping(peer: Peer, active: Boolean) = Unit
    }
    private fun show(): FakeMessenger {
        val repository = FakeMessenger(peer, message)
        val model = MessengerViewModel(repository, FakeAuth(), SavedStateHandle())
        compose.runOnUiThread { store.put("fixture", model); model.open(peer) }
        compose.setContent { BaleTheme { MessengerScreen(model) } }
        return repository
    }
    @Test fun replyActionPreservesQuoteAndClearsDraftAfterEnqueue() {
        val repository = show()
        compose.onNodeWithText("question from fixture").performClick()
        compose.onNodeWithText("Reply", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("message-composer").performTextInput("answer from UI")
        compose.onNodeWithText("Send", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals("answer from UI", repository.queuedText)
            assertNotNull(repository.queuedReply)
            assertEquals(99L, repository.queuedReply?.rid)
        }
        compose.onNodeWithTag("message-composer").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("answer from UI").assertIsDisplayed()
    }
    @Test fun serverSearchShowsMatchingMessagePreview() {
        show()
        compose.onNodeWithText("Search messages").performClick()
        compose.onNodeWithTag("server-search-query").performTextInput("question")
        compose.onNodeWithTag("server-search-submit").performClick()
        compose.onNodeWithText("Showing 1 matches · server total 1").assertIsDisplayed()
        compose.onNodeWithText("Open conversation").assertIsDisplayed()
    }
}
