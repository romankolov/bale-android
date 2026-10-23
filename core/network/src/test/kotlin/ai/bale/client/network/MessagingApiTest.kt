package ai.bale.client.network

import ai.bale.client.domain.Connection
import ai.bale.client.domain.MessageReference
import ai.bale.client.domain.Peer
import ai.bale.client.protocol.*
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MessagingApiTest {
    private class RecordingRpc : BaleRpc {
        override val state = MutableStateFlow(Connection.Connected)
        override val updates = MutableSharedFlow<ByteArray>()
        var service = ""
        var method = ""
        lateinit var request: MessageLite
        var response = byteArrayOf()
        override suspend fun rpc(service: String, method: String, request: MessageLite): ByteArray {
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
        MessagingApi(rpc).forward(peer, Peer(99, 2, 456), message)
        val request = rpc.request as Messaging_ForwardMessages_Request
        assertEquals("ForwardMessages", rpc.method)
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
    @Test fun `reaction add and remove use abacus and retain target date`() = runTest {
        val rpc = RecordingRpc()
        val api = MessagingApi(rpc)
        api.react(peer, message, "👍", false)
        assertEquals("bale.abacus.v1.Abacus", rpc.service)
        assertEquals("MessageSetReaction", rpc.method)
        assertEquals(message.date, (rpc.request as Abacus_MessageSetReaction_Request).date)
        api.react(peer, message, "👍", true)
        assertEquals("MessageRemoveReaction", rpc.method)
        assertEquals(message.rid, (rpc.request as Abacus_MessageRemoveReaction_Request).rid)
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
    @Test fun `history pagination preserves mode limit and exact cursor`() = runTest {
        val rpc = RecordingRpc()
        MessagingApi(rpc).history(peer, message.date)
        val request = rpc.request as Messaging_LoadHistory_Request
        assertEquals(2, request.loadMode)
        assertEquals(50, request.limit)
        assertEquals(message.date, request.date)
        MessagingApi(rpc).dialogs(message.date)
        assertEquals(message.date, (rpc.request as Messaging_LoadDialogs_Request).minDate)
    }
}
