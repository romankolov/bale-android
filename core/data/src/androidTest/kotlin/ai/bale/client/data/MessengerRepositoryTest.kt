package ai.bale.client.data

import ai.bale.client.domain.Account
import ai.bale.client.domain.Attachment
import ai.bale.client.domain.AttachmentKind
import ai.bale.client.domain.AuthChallenge
import ai.bale.client.domain.AuthRepository
import ai.bale.client.domain.Connection
import ai.bale.client.domain.Peer
import ai.bale.client.domain.UploadPayload
import ai.bale.client.network.BaleRpc
import ai.bale.client.network.MessagingApi
import ai.bale.client.network.SessionUpdate
import ai.bale.client.protocol.*
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.Int32Value
import com.google.protobuf.Int64Value
import com.google.protobuf.MessageLite
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Collections

class MessengerRepositoryTest {
    private lateinit var database: BaleDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var rpc: FakeRpc
    private lateinit var auth: FakeAuth
    private lateinit var mediaDir: File
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
        override val updates = MutableSharedFlow<SessionUpdate>()
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, MessageLite>>())
        var uploadUrl = ""
        private val serverClock = java.util.concurrent.atomic.AtomicLong(2000L)
        suspend fun push(update: Update, epoch: Long = sessionId) {
            updates.emit(SessionUpdate(epoch, update.toByteArray()))
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
                    Msg_index_fe3fa18088__83329_h.newBuilder().setDate(serverClock.incrementAndGet()).build().toByteArray()
                }
                "MessageSetReaction" -> Msg_4825_fafe26abbf__79002_p.newBuilder()
                    .addReactions(Field264.newBuilder().addUsers(7).setCode("👍").setCardinality(Int64Value.of(1))).build().toByteArray()
                "MessageRemoveReaction" -> Msg_4825_fafe26abbf__79002_p.getDefaultInstance().toByteArray()
                "GetNasimFileUploadUrl" -> Files_GetNasimFileUploadUrl_Response.newBuilder()
                    .setFileId(707L).setUrl(uploadUrl).setChunkSize(0).build().toByteArray()
                "CreatePoll" -> Poll_CreatePoll_Response.newBuilder().setPollId(404L).build().toByteArray()
                "Vote" -> {
                    val voteReq = request as Poll_Vote_Request
                    val chosen = voteReq.optionIdsList.map(Int::toLong)
                    Poll_Vote_Response.newBuilder().setPollResult(
                        PollResult.newBuilder()
                            .setPollId(voteReq.pollId)
                            .setVotersCount(if (voteReq.isRetract) 0 else 1)
                            .addAllChosenOptionIds(if (voteReq.isRetract) emptyList() else chosen)
                            .apply {
                                if (!voteReq.isRetract) {
                                    voteReq.optionIdsList.forEach { opt ->
                                        addOptionResults(OptionResults.newBuilder().setOptionId(opt).setVotesCount(1))
                                    }
                                }
                            }
                    ).build().toByteArray()
                }
                "GetPollResults" -> {
                    val req = request as Poll_GetPollResults_Request
                    Poll_GetPollResults_Response.newBuilder()
                        .addAllPollResults(req.pollIdsList.map { id ->
                            PollResult.newBuilder().setPollId(id).setVotersCount(5).addChosenOptionIds(0L)
                                .addOptionResults(OptionResults.newBuilder().setOptionId(0).setVotesCount(3))
                                .addOptionResults(OptionResults.newBuilder().setOptionId(1).setVotesCount(2))
                                .build()
                        }).build().toByteArray()
                }
                "GetFullGroup" -> {
                    val req = request as Groups_GetFullGroup_Request
                    Groups_GetFullGroup_Response.newBuilder()
                        .setFullGroup(FullGroup.newBuilder().setId(req.peer.groupId).setAccessHash(req.peer.accessHash)
                            .setGroupType(if (req.peer.groupId == 777) 1 else 0).setTitle("Channel 777"))
                        .build().toByteArray()
                }
                "JoinGroup" -> Groups_JoinGroup_Response.newBuilder()
                    .setGroup(Groups2.newBuilder().setId(515).setAccessHash(919L).setTitle("Joined")).build().toByteArray()
                else -> byteArrayOf()
            }
        }
    }

    @Before fun setup() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BaleDatabase::class.java).build()
        mediaDir = File(context.cacheDir, "repo-test-media-${System.nanoTime()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        rpc = FakeRpc(); auth = FakeAuth()
        repository = DefaultMessengerRepository(
            auth = auth,
            socket = rpc,
            database = database,
            scope = scope,
            gate = AccountGate(),
            http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("".toResponseBody()).build()
            }.build(),
            mediaRoot = mediaDir,
            tokenProvider = { "test-jwt" },
            allowLoopbackHttpForTests = true,
        )
        withTimeout(10_000) { rpc.updates.subscriptionCount.first { it > 0 } }
    }

    @After fun teardown() = runBlocking<Unit> {
        scope.coroutineContext[Job]?.cancelAndJoin()
        database.close()
        mediaDir.deleteRecursively()
    }

    private suspend fun seed() {
        rpc.history = listOf(original)
        repository.loadHistory(peer)
    }

    private suspend fun row(rid: Long) = database.messenger().message(7, peer.key, rid)!!

    @Test fun replyRetryPreservesQuoteAndStableRidAcrossAmbiguousFailure() = runBlocking<Unit> {
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

    @Test fun confirmedDeletionLeavesTombstoneThatStaleHistoryCannotResurrect() = runBlocking<Unit> {
        seed()
        repository.delete(peer, row(99).domain(), false)
        repository.loadHistory(peer)
        assertTrue(row(99).deleted)
        assertEquals("", row(99).text)
        assertTrue(row(99).metadata.isEmpty())
    }

    @Test fun remoteEditDeleteAndReactionUpdatesReduceCachedMessages() = runBlocking<Unit> {
        seed()
        val p = ai.bale.client.protocol.Peer.newBuilder().setType(1).setId(42).build()
        rpc.push(Update.newBuilder().setMessageContentChanged(MessageContentChanged.newBuilder()
            .setPeer(p).setRid(99).setMessage(MessagingApi.text("edited remotely"))).build())
        withTimeout(10_000) { database.messenger().messages(7, peer.key).first { it.single().text == "edited remotely" } }
        assertTrue(row(99).domain().edited)
        rpc.push(Update.newBuilder().setReactionsUpdate(ReactionsUpdate.newBuilder().setPeer(p).setRid(99)
            .addReactions(Field264.newBuilder().addUsers(42).setCode("👍").setCardinality(Int64Value.of(4)))).build())
        withTimeout(10_000) { database.messenger().messages(7, peer.key).first { it.single().domain().reactions.singleOrNull()?.count == 4L } }
        rpc.push(Update.newBuilder().setMessageDelete(MessageDelete.newBuilder().setPeer(p)
            .setDates(Dates2.newBuilder().addDates(1000))).build())
        withTimeout(10_000) { database.messenger().messages(7, peer.key).first { it.single().deleted } }
        assertEquals("", row(99).text)
    }

    @Test fun readReceiptsAndServerUnreadCounterStayAccountScoped() = runBlocking<Unit> {
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

    @Test fun typingUpdatesStopWithoutBeingPersistedAsChatMessages() = runBlocking<Unit> {
        val p = ai.bale.client.protocol.Peer.newBuilder().setType(1).setId(42).build()
        rpc.push(Update.newBuilder().setTyping(Typing.newBuilder().setPeer(p).setUid(42).setTypingType(1)).build())
        assertEquals(setOf(42), withTimeout(10_000) { repository.typingUsers(peer).first { it.isNotEmpty() } })
        rpc.push(Update.newBuilder().setTypingStop(TypingStop.newBuilder().setPeer(p).setUid(42)).build())
        withTimeout(10_000) { repository.typingUsers(peer).first { it.isEmpty() } }
        assertTrue(database.messenger().messages(7, peer.key).first().isEmpty())
    }

    @Test fun lateHistoryResponseCannotWriteIntoAnotherAccount() = runBlocking<Unit> {
        rpc.history = listOf(original)
        rpc.historyWait = CompletableDeferred()
        val fetch = async(start = CoroutineStart.UNDISPATCHED) { repository.loadHistory(peer) }
        auth.account.value = Account(8, "second fixture")
        rpc.historyWait!!.complete(Unit)
        fetch.await()
        assertTrue(database.messenger().messages(7, peer.key).first().isEmpty())
        assertTrue(database.messenger().messages(8, peer.key).first().isEmpty())
    }

    @Test fun bufferedUpdateFromOldSessionCannotEnterNewAccountCache() = runBlocking<Unit> {
        auth.account.value = Account(8, "second fixture")
        rpc.sessionId = 2
        val p = ai.bale.client.protocol.Peer.newBuilder().setType(1).setId(42).build()
        rpc.push(Update.newBuilder().setMessage(Message3.newBuilder().setPeer(p).setRid(999)
            .setDate(1000).setSenderUid(42).setMessage(MessagingApi.text("old private payload"))).build(), epoch = 1)
        rpc.push(Update.newBuilder().setTyping(Typing.newBuilder().setPeer(p).setUid(42)).build())
        withTimeout(10_000) { repository.typingUsers(peer).first { it.isNotEmpty() } }
        assertTrue(database.messenger().messages(8, peer.key).first().isEmpty())
    }

    @Test fun deleteBeforeHistoryIsLoadedStillBlocksStaleMessageResurrection() = runBlocking<Unit> {
        val p = ai.bale.client.protocol.Peer.newBuilder().setType(1).setId(42).build()
        rpc.push(Update.newBuilder().setMessageDelete(MessageDelete.newBuilder().setPeer(p)
            .setDates(Dates2.newBuilder().addDates(1000))).build())
        rpc.push(Update.newBuilder().setTyping(Typing.newBuilder().setPeer(p).setUid(42)).build())
        withTimeout(10_000) { repository.typingUsers(peer).first { it.isNotEmpty() } }
        assertTrue(database.messenger().isDeleted(7, peer.key, 1000, 99))
        seed()
        assertTrue(database.messenger().messages(7, peer.key).first().isEmpty())
    }

    @Test fun addAndRemoveReactionsPersistServerCounts() = runBlocking<Unit> {
        seed()
        val message = row(99).domain()
        repository.react(peer, message, "👍", false)
        assertEquals(1L, row(99).domain().reactions.single().count)
        assertTrue(row(99).domain().reactions.single().mine)
        repository.react(peer, message, "👍", true)
        assertTrue(row(99).domain().reactions.isEmpty())
    }

    @Test fun mediaUploadCachesAttachmentAndPollsAndJoinLinksPersistState() = runBlocking<Unit> {
        rpc.uploadUrl = "https://files.bale.ai/upload/707"
        val payload = UploadPayload("voice.m4a", "audio/mp4a-latm", byteArrayOf(7, 7, 7), AttachmentKind.Voice, durationSeconds = 4)
        repository.sendMedia(peer, payload, "Voice note")
        val cached = repository.downloadMedia(Attachment(707L, 0L, 0, "voice.m4a", "audio/mp4a-latm", 3, AttachmentKind.Voice))
        assertTrue(File(cached).isFile)

        repository.createPoll(peer, "Ship?", listOf("Yes", "Now"))
        val pollMsg = database.messenger().messages(7, peer.key).first().last().domain()
        assertEquals("Ship?", pollMsg.poll?.question)
        repository.votePoll(peer, pollMsg, listOf(0), retract = false)
        assertEquals(1, row(pollMsg.rid).domain().poll?.totalVoters)

        val joined = repository.joinInviteOrHandle("https://ble.ir/join/abc_TOKEN-12")
        assertEquals("2:515", joined?.key)
    }

    @Test fun channelUploadForwardedSocketUpdatesAndPollVoteSwitchingWorkEndToEnd() = runBlocking<Unit> {
        // 1. Channel file upload resolves ExPeerKind.CHANNEL (3) and the channel peer as uploader uid
        rpc.uploadUrl = "https://files.bale.ai/upload/707"
        val channelPeer = Peer(777, 2, 999L)
        repository.sendMedia(
            channelPeer,
            UploadPayload("slides.pdf", "application/pdf", byteArrayOf(1, 2, 3, 4), AttachmentKind.Document),
            "Deck",
        )
        val uploadReq = rpc.requests.last { it.first == "GetNasimFileUploadUrl" }.second as Files_GetNasimFileUploadUrl_Request
        assertEquals(7L, uploadReq.uid) // uploads attribute to the signer; exPeer places the file
        assertEquals(3, uploadReq.exPeer.type)
        assertFalse(uploadReq.hasSendType()) // documents omit sendType (web parity)
        val sentDocReq = rpc.requests.last { it.first == "SendMessage" }.second as Messaging_SendMessage_Request
        assertEquals(2, sentDocReq.peer.type)
        assertEquals(3, sentDocReq.exPeer.type)
        // Fresh uploads carry no access hash at all (the web client never invents one; a
        // fabricated hash is what made documents reject server-side).
        assertEquals(0L, sentDocReq.message.documentMessage.accessHash)

        // 2. Live socket forwarded message unwraps emptyMessage into real supported ChatMessage
        val p = ai.bale.client.protocol.Peer.newBuilder().setType(1).setId(42).build()
        rpc.push(Update.newBuilder().setMessage(
            Message3.newBuilder()
                .setPeer(p).setRid(600L).setDate(3000L).setSenderUid(42)
                .setMessage(Message2.newBuilder().setEmptyMessage(EmptyMessage.getDefaultInstance()))
                .setQuotedMessage(QuotedMessage.newBuilder().setSenderUserId(99).setQuotedMessageContent(MessagingApi.text("Forwarded live message")))
        ).build())
        val fwdMsg = withTimeout(10_000) {
            database.messenger().messages(7, peer.key).first { rows -> rows.any { it.rid == 600L } }
                .first { it.rid == 600L }.domain()
        }
        assertTrue(fwdMsg.supported)
        assertEquals("Forwarded live message", fwdMsg.text)
        assertEquals(99, fwdMsg.forwardedFrom?.senderUserId)
        assertNull(fwdMsg.quote)

        // 3. LoadHistory automatically hydrates poll counts via GetPollResults AND switching vote auto-retracts first
        rpc.history = listOf(
            History.newBuilder().setRid(700L).setDate(4000L).setSenderUid(42)
                .setMessage(Message2.newBuilder().setPollMessage(
                    PollMessage.newBuilder().setPollId(909L).setQuestion("Release day?")
                        .addOptions(Options.newBuilder().setId(0).setText("Mon"))
                        .addOptions(Options.newBuilder().setId(1).setText("Tue"))
                )).build()
        )
        repository.loadHistory(peer)
        val hydratedPoll = row(700L).domain()
        assertEquals(5, hydratedPoll.poll?.totalVoters)
        assertEquals(3, hydratedPoll.poll?.options?.get(0)?.votes)
        assertEquals(2, hydratedPoll.poll?.options?.get(1)?.votes)
        assertTrue(hydratedPoll.poll?.options?.get(0)?.chosen == true)

        rpc.requests.clear()
        repository.votePoll(peer, hydratedPoll, listOf(1), retract = false)
        val voteCalls = rpc.requests.filter { it.first == "Vote" }.map { it.second as Poll_Vote_Request }
        assertEquals(2, voteCalls.size)
        assertTrue(voteCalls[0].isRetract)
        assertFalse(voteCalls[1].isRetract)
        assertEquals(listOf(1), voteCalls[1].optionIdsList)
        assertTrue(row(700L).domain().poll?.options?.get(1)?.chosen == true)
    }
}
