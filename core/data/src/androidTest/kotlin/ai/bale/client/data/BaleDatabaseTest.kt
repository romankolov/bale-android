package ai.bale.client.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BaleDatabaseTest {
    private lateinit var database: BaleDatabase
    private lateinit var dao: MessengerDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BaleDatabase::class.java,
        ).build()
        dao = database.messenger()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun conversationQueriesAreAccountScoped() = runBlocking {
        dao.conversations(listOf(
            ConversationEntity(1, "1:42", 42, 1, 123, "First account", "hello", 10, 1),
            ConversationEntity(2, "1:42", 42, 1, 456, "Second account", "private", 20, 2),
        ))
        assertEquals(listOf("First account"), dao.conversations(1).first().map { it.title })
        assertEquals(listOf("Second account"), dao.conversations(2).first().map { it.title })
    }

    @Test
    fun acknowledgementReconcilesStableRidWithoutDuplicatingMessages() = runBlocking {
        val pending = MessageEntity(1, "1:42", 1234L, 1, "hello", 10, "Pending", true)
        dao.messages(listOf(pending))
        dao.delivery(1, "1:42", 1234L, "Sent", 20)
        dao.messages(listOf(pending.copy(delivery = "Sent", date = 20)))
        val messages = dao.messages(1, "1:42").first()
        assertEquals(1, messages.size)
        assertEquals("Sent", messages.single().delivery)
        assertEquals(20L, messages.single().date)
    }

    @Test
    fun samePeerAndRidRemainSeparateAcrossAccounts() = runBlocking {
        val first = MessageEntity(1, "1:42", 999L, 1, "first", 10, "Pending", true)
        dao.messages(listOf(first, first.copy(accountId = 2, text = "second")))
        dao.delivery(1, "1:42", 999L, "Sent", 20)
        assertEquals("Sent", dao.message(1, "1:42", 999L)?.delivery)
        assertEquals("Pending", dao.message(2, "1:42", 999L)?.delivery)
        assertEquals("second", dao.messages(2, "1:42").first().single().text)
    }

    @Test
    fun interruptedOutboxBecomesRetryableAndLogoutClearsRows() = runBlocking {
        dao.messages(listOf(
            MessageEntity(1, "1:42", 1, 1, "pending", 10, "Pending", true),
            MessageEntity(1, "1:42", 2, 1, "sent", 20, "Sent", true),
        ))
        dao.recoverInterruptedOutbox()
        assertEquals("Failed", dao.message(1, "1:42", 1)?.delivery)
        assertEquals("Sent", dao.message(1, "1:42", 2)?.delivery)
        database.clearAllTables()
        assertEquals(emptyList<MessageEntity>(), dao.messages(1, "1:42").first())
    }
}
