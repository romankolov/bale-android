package ai.bale.client.network

import ai.bale.client.domain.Connection
import ai.bale.client.domain.MessageReference
import ai.bale.client.domain.Peer
import ai.bale.client.protocol.*
import com.google.protobuf.Int64Value
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MessagingApiTest {
    private class RecordingRpc : BaleRpc {
        override val state = MutableStateFlow(Connection.Connected)
        override var sessionId = 0L
        override val updates = MutableSharedFlow<SessionUpdate>()
        var service = ""
        var method = ""
        lateinit var request: MessageLite
        var response = byteArrayOf()
        override suspend fun rpc(service: String, method: String, request: MessageLite, expectedSession: Long): ByteArray {
            this.service = service; this.method = method; this.request = request
            return response
        }
    }
    private val peer = Peer(42, 1, 1234567890123)
    private val message = MessageReference(9007199254740993, 1761177600000, 72)

    @Test fun `reply preserves 64 bit identity and nested reference`() = runTest {
        val rpc = RecordingRpc()
        MessagingApi(rpc).send(peer, 9007199254740995, MessagingApi.text("سلام 👋"), message)
        val request = Messaging_SendMessage_Request.parseFrom(rpc.request.toByteArray())
        assertEquals("bale.messaging.v2.Messaging", rpc.service)
        assertEquals("SendMessage", rpc.method)
        assertEquals(9007199254740995, request.rid)
        assertEquals(peer.accessHash, request.peer.accessHash)
        assertEquals("سلام 👋", request.message.textMessage.text)
        assertEquals(message.rid, request.quotedMessageReference.rid)
        assertEquals(message.date, request.quotedMessageReference.date.value)
        assertEquals(72L, request.quotedMessageReference.seq.value)
    }

    @Test fun `no reply or unknown sequence is not fabricated`() = runTest {
        val rpc = RecordingRpc()
        val api = MessagingApi(rpc)
        api.send(peer, 1, MessagingApi.text("text"), null)
        assertFalse((rpc.request as Messaging_SendMessage_Request).hasQuotedMessageReference())
        assertFalse(MessagingApi.reference(peer, message.copy(sequence = 0)).hasSeq())
    }

    @Test fun `delete uses packed dates and explicit local versus everyone presence`() = runTest {
        val rpc = RecordingRpc()
        val api = MessagingApi(rpc)
        api.delete(peer, message.date, false)
        var request = Messaging_DeleteMessage_Request.parseFrom(rpc.request.toByteArray())
        assertEquals("DeleteMessage", rpc.method)
        assertEquals(listOf(message.date), request.dates.datesList)
        assertArrayEquals(byteArrayOf(10, 6, -128, -128, -10, -14, -96, 51), request.dates.toByteArray())
        assertTrue(request.hasJustMine())
        assertTrue(request.justMine.value)
        api.delete(peer, message.date, true)
        request = rpc.request as Messaging_DeleteMessage_Request
        assertTrue(request.hasJustMine())
        assertFalse(request.justMine.value)
    }

    @Test fun `forward uses Peer3 destination and original peer reference`() = runTest {
        val rpc = RecordingRpc()
        MessagingApi(rpc).forward(peer, Peer(99, 2, 456), message, 9876543210123)
        val request = rpc.request as Messaging_ForwardMessages_Request
        assertEquals("ForwardMessages", rpc.method)
        assertEquals(listOf(9876543210123L), request.messageIdsList)
        assertEquals(2, request.peer.type)
        assertEquals(99, request.peer.id)
        assertEquals(456L, request.peer.accessHash)
        assertEquals(peer.id, request.forwardedMessagesList.single().peer.id)
        assertEquals(message.rid, request.forwardedMessagesList.single().rid)
    }

    @Test fun `editing sends only the replacement content and same rid`() = runTest {
        val rpc = RecordingRpc()
        MessagingApi(rpc).edit(peer, message.rid, "replacement")
        val request = rpc.request as Messaging_UpdateMessage_Request
        assertEquals("UpdateMessage", rpc.method)
        assertEquals(message.rid, request.rid)
        assertEquals("replacement", request.updatedMessage.textMessage.text)
    }

    @Test fun `search can be global or conjunctively scoped to one peer`() = runTest {
        val rpc = RecordingRpc()
        val api = MessagingApi(rpc)
        api.search("سلام", null)
        var request = rpc.request as Search_SearchMessages_Request
        assertEquals("bale.search.v1.Search", rpc.service)
        assertEquals("سلام", request.query.searchPieceText.query)
        api.search("سلام", peer)
        request = rpc.request as Search_SearchMessages_Request
        val conditions = request.query.searchAndCondition.andQueryList
        assertEquals(2, conditions.size)
        assertEquals("سلام", conditions[0].searchPieceText.query)
        assertEquals(peer.accessHash, conditions[1].searchPeerCondition.peer.accessHash)
    }

    @Test fun `reaction add and remove use abacus and parse repeated reaction lists`() = runTest {
        val rpc = RecordingRpc()
        rpc.response = Msg_4825_fafe26abbf__79002_p.newBuilder()
            .addReactions(Field264.newBuilder().addUsers(7).setCode("👍").setCardinality(Int64Value.of(1)))
            .addReactions(Field264.newBuilder().addUsers(9).setCode("❤️").setCardinality(Int64Value.of(2)))
            .build().toByteArray()
        val api = MessagingApi(rpc)
        val reactions = api.react(peer, message, "👍", false)
        assertEquals("bale.abacus.v1.Abacus", rpc.service)
        assertEquals("MessageSetReaction", rpc.method)
        assertEquals(listOf("👍", "❤️"), reactions.map { it.code })
        assertEquals(message.date, (rpc.request as Abacus_MessageSetReaction_Request).date)
        rpc.response = byteArrayOf()
        val cleared = api.react(peer, message, "👍", true)
        assertEquals("MessageRemoveReaction", rpc.method)
        assertTrue(cleared.isEmpty())
    }

    @Test fun `typing stops use separate presence endpoint`() = runTest {
        val rpc = RecordingRpc()
        val api = MessagingApi(rpc)
        api.typing(peer, true)
        assertEquals("bale.presence.v1.Presence", rpc.service)
        assertEquals("Typing", rpc.method)
        assertEquals(1, (rpc.request as Presence_Typing_Request).typingType)
        api.typing(peer, false)
        assertEquals("StopTyping", rpc.method)
        assertEquals(peer.id, (rpc.request as Presence_StopTyping_Request).peer.id)
    }

    @Test fun `pins dialogs polls and bot template responses encode expected wire payloads`() = runTest {
        val rpc = RecordingRpc()
        val api = MessagingApi(rpc)
        api.pinMessage(peer, message, true)
        assertEquals("PinMessage", rpc.method)
        assertEquals(message.rid, (rpc.request as Messaging_PinMessage_Request).messageId.rid)
        api.pinMessage(peer, message, false)
        assertEquals("UnPinMessages", rpc.method)
        assertEquals(message.rid, (rpc.request as Messaging_UnPinMessages_Request).messagesList.single().rid)

        rpc.response = Poll_CreatePoll_Response.newBuilder().setPollId(777L).build().toByteArray()
        assertEquals(777L, api.createPoll(peer, "Lunch?", listOf("Kebab", "Rice"), anonymous = true, multiple = true, now = 5000L, exPeerType = 3))
        assertEquals("bale.poll.v1.Poll", rpc.service)
        assertEquals("CreatePoll", rpc.method)
        assertEquals(3, (rpc.request as Poll_CreatePoll_Request).peer.type)

        rpc.response = Poll_Vote_Response.newBuilder().setPollResult(PollResult.newBuilder().setVotersCount(3)).build().toByteArray()
        val result = api.votePoll(777L, listOf(0, 1), retract = false, now = 6000L)
        assertEquals(3, result.votersCount)
        assertEquals(listOf(0, 1), (rpc.request as Poll_Vote_Request).optionIdsList)

        val callback = MessagingApi.templateResponse("cb:1", 42L)
        assertEquals("cb:1", callback.templateMessageResponse.tmrMessage)
        assertEquals(42L, callback.templateMessageResponse.templateMessageResponseId)
    }
}
