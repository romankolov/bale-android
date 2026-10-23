package ai.bale.client.data

import ai.bale.client.domain.Account
import ai.bale.client.domain.AuthChallenge
import ai.bale.client.domain.AuthRepository
import ai.bale.client.domain.ChatMessage
import ai.bale.client.domain.Connection
import ai.bale.client.domain.Delivery
import ai.bale.client.domain.Peer
import ai.bale.client.network.BaleRpc
import ai.bale.client.network.MessagingApi
import ai.bale.client.protocol.*
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.Int32Value
import com.google.protobuf.Int64Value
import com.google.protobuf.MessageLite
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Collections

class MessengerRepositoryTest {
    private lateinit var database: BaleDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var rpc: FakeRpc
    private lateinit var auth: FakeAuth
    private lateinit var repository: DefaultMessengerRepository
    private val peer = Peer(42, 1, 123)
    private val original = History.newBuilder().setRid(99).setDate(1000).setSenderUid(42)
        .setSeq(Int64Value.of(8)).setMessage(MessagingApi.text("question")).build()
    private class FakeAuth : AuthRepository {
        override val account = MutableStateFlow<Account?>(Account(7, "fixture"))
        override suspend fun restore() = Unit
        override suspend fun start(phone: String): AuthChallenge = error("unused")
        override suspend fun verify(challenge: AuthChallenge, value: String): AuthChallenge? = error("unused")
        override suspend fun signOut() { account.value = null }
    }
    private class FakeRpc : BaleRpc {
        override val state = MutableStateFlow(Connection.Disconnected)
        override var sessionId = 0L
        override val updates = MutableSharedFlow<ai.bale.client.network.SessionUpdate>()
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, MessageLite>>())
        suspend fun push(update: Update, epoch: Long = sessionId) {
            updates.emit(ai.bale.client.network.SessionUpdate(epoch, update.toByteArray()))
        }
        var history = emptyList<History>()
        var failSend = false
        var historyWait: CompletableDeferred<Unit>? = null
        override suspend fun rpc(service: String, method: String, request: MessageLite, expectedSession: Long): ByteArray {
            requests += method to request
            return when (method) {
                "LoadHistory" -> { historyWait?.await(); Messaging_LoadHistory_Response.newBuilder().addAllHistory(history).build().toByteArray() }
                "SendMessage" -> {
                    if (failSend) throw IOException("fixture disconnect")
                    Msg_index_fe3fa18088__83329_h.newBuilder().setDate(2000).build().toByteArray()
                }
                "MessageSetReaction", "MessageRemoveReaction" -> Msg_4825_fafe26abbf__79002_p.newBuilder()
                    .setField18(Field264.newBuilder().setCode("👍").setCardinality(Int64Value.of(if (method == "MessageSetReaction") 1 else 0))).build().toByteArray()
                else -> byteArrayOf()
            }
        }
    }
    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BaleDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        rpc = FakeRpc(); auth = FakeAuth()
        repository = DefaultMessengerRepository(auth, rpc, database, scope, AccountGate())
        withTimeout(10_000) { rpc.updates.subscriptionCount.first { it > 0 } }
    }
    @After fun teardown() = runBlocking { scope.coroutineContext[Job]?.cancelAndJoin(); database.close() }
    private suspend fun seed() {
        rpc.history = listOf(original)
        repository.loadHistory(peer)
    }
    private suspend fun row(rid: Long) = database.messenger().message(7, peer.key, rid)!!

    @Test fun replyRetryPreservesQuoteAndStableRidAcrossAmbiguousFailure() = runBlocking {
        seed()
        val rid = repository.queue(peer, "answer", row(99).domain())
        rpc.failSend = true
        try { repository.retry(peer, rid); fail("Expected disconnect") } catch (_: IOException) { }
        assertEquals("Failed", row(rid).delivery)
        rpc.failSend = false
        repository.retry(peer, rid)
        val sent = rpc.requests.filter { it.first == "SendMessage" }.map { it.second as Messaging_SendMessage_Request }
        assertEquals(listOf(rid, rid), sent.map { it.rid })
        assertEquals(99L, sent.last().quotedMessageReference.rid)
        assertEquals(8L, sent.last().quotedMessageReference.seq.value)
        assertEquals("answer", sent.last().message.textMessage.text)
        assertEquals("Sent", row(rid).delivery)
        assertEquals("question", row(rid).domain().quote?.text)
    }
    @Test fun confirmedDeletionLeavesTombstoneThatStaleHistoryCannotResurrect() = runBlocking {
        seed()
        repository.delete(peer, row(99).domain(), false)
        repository.loadHistory(peer)
        assertTrue(row(99).deleted)
        assertEquals("", row(99).text)
        assertTrue(row(99).metadata.isEmpty())
        val request = rpc.requests.first { it.first == "DeleteMessage" }.second as Messaging_DeleteMessage_Request
        assertTrue(request.justMine.value)
        assertEquals(listOf(1000L), request.dates.datesList)
    }
    @Test fun remoteEditDeleteAndReactionUpdatesReduceCachedMessages() = runBlocking {
        seed()
        val p = ai.bale.client.protocol.Peer.newBuilder().setType(1).setId(42).build()
        rpc.push(Update.newBuilder().setMessageContentChanged(MessageContentChanged.newBuilder()
            .setPeer(p).setRid(99).setMessage(MessagingApi.text("edited remotely"))).build())
        withTimeout(10_000) { database.messenger().messages(7, peer.key).first { it.single().text == "edited remotely" } }
        assertTrue(row(99).domain().edited)
        rpc.push(Update.newBuilder().setReactionsUpdate(ReactionsUpdate.newBuilder().setPeer(p).setRid(99)
            .setField26(Field264.newBuilder().setCode("👍").setCardinality(Int64Value.of(4)))).build())
        withTimeout(10_000) { database.messenger().messages(7, peer.key).first { it.single().domain().reactions.singleOrNull()?.count == 4L } }
        rpc.push(Update.newBuilder().setMessageDelete(MessageDelete.newBuilder().setPeer(p)
            .setDates(Dates2.newBuilder().addDates(1000))).build())
        withTimeout(10_000) { database.messenger().messages(7, peer.key).first { it.single().deleted } }
        assertEquals("", row(99).text)
    }
    @Test fun readReceiptsAndServerUnreadCounterStayAccountScoped() = runBlocking {
        val dao = database.messenger()
        val sent = MessageEntity(7, peer.key, 88, 7, "outgoing", 900, "Sent", true)
        dao.messages(listOf(sent, sent.copy(accountId = 8)))
        dao.conversations(listOf(ConversationEntity(7, peer.key, 42, 1, 123, "fixture", "", 1000, 5)))
        val p = ai.bale.client.protocol.Peer.newBuilder().setType(1).setId(42).build()
        rpc.push(Update.newBuilder().setMessageRead(MessageRead.newBuilder().setPeer(p).setReadDate(1000)).build())
        withTimeout(10_000) { dao.messages(7, peer.key).first { it.single().read } }
        assertFalse(dao.message(8, peer.key, 88)!!.read)
        rpc.push(Update.newBuilder().setMessageReadByMe(MessageReadByMe.newBuilder().setPeer(p)
            .setUnreadCounter(Int32Value.of(3))).build())
        withTimeout(10_000) { dao.conversations(7).first { it.single().unread == 3 } }
    }
    @Test fun typingUpdatesStopWithoutBeingPersistedAsChatMessages() = runBlocking {
        val p = ai.bale.client.protocol.Peer.newBuilder().setType(1).setId(42).build()
        rpc.push(Update.newBuilder().setTyping(Typing.newBuilder().setPeer(p).setUid(42).setTypingType(1)).build())
        assertEquals(setOf(42), withTimeout(10_000) { repository.typingUsers(peer).first { it.isNotEmpty() } })
        rpc.push(Update.newBuilder().setTypingStop(TypingStop.newBuilder().setPeer(p).setUid(42)).build())
        withTimeout(10_000) { repository.typingUsers(peer).first { it.isEmpty() } }
        assertTrue(database.messenger().messages(7, peer.key).first().isEmpty())
    }
    @Test fun lateHistoryResponseCannotWriteIntoAnotherAccount() = runBlocking {
        rpc.history = listOf(original)
        rpc.historyWait = CompletableDeferred()
        val fetch = async(start = CoroutineStart.UNDISPATCHED) { repository.loadHistory(peer) }
        // The deferred response guarantees account change occurs before persistence.
        auth.account.value = Account(8, "second fixture")
        rpc.historyWait!!.complete(Unit)
        fetch.await()
        assertTrue(database.messenger().messages(7, peer.key).first().isEmpty())
        assertTrue(database.messenger().messages(8, peer.key).first().isEmpty())
    }
    @Test fun bufferedUpdateFromOldSessionCannotEnterNewAccountCache() = runBlocking {
        auth.account.value = Account(8, "second fixture")
        rpc.sessionId = 2
        val p = ai.bale.client.protocol.Peer.newBuilder().setType(1).setId(42).build()
        rpc.push(Update.newBuilder().setMessage(Message3.newBuilder().setPeer(p).setRid(999)
            .setDate(1000).setSenderUid(42).setMessage(MessagingApi.text("old private payload"))).build(), epoch = 1)
        // A current-session event provides a deterministic barrier after the stale event.
        rpc.push(Update.newBuilder().setTyping(Typing.newBuilder().setPeer(p).setUid(42)).build())
        withTimeout(10_000) { repository.typingUsers(peer).first { it.isNotEmpty() } }
        assertTrue(database.messenger().messages(8, peer.key).first().isEmpty())
    }
    @Test fun addAndRemoveReactionsPersistServerCounts() = runBlocking {
        seed()
        val message = row(99).domain()
        repository.react(peer, message, "👍", false)
        assertEquals(1L, row(99).domain().reactions.single().count)
        repository.react(peer, message, "👍", true)
        assertTrue(row(99).domain().reactions.isEmpty())
    }
}
