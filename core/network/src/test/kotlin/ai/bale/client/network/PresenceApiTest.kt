package ai.bale.client.network

import ai.bale.client.domain.Connection
import ai.bale.client.domain.Peer
import ai.bale.client.protocol.GroupPeer
import ai.bale.client.protocol.Presence_GetGroupOnlineCount_Request
import ai.bale.client.protocol.Presence_GetGroupOnlineCount_Response
import ai.bale.client.protocol.Presence_GetUsersPresence_Request
import ai.bale.client.protocol.Presence_GetUsersPresence_Response
import ai.bale.client.protocol.Presence_SetOnline_Request
import ai.bale.client.protocol.Presence_SubscribeFromOnline_Request
import ai.bale.client.protocol.Presences
import ai.bale.client.protocol.UserOffline2
import ai.bale.client.protocol.UserOnline2
import ai.bale.client.protocol.UserPeers
import com.google.protobuf.BoolValue
import com.google.protobuf.Int64Value
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceApiTest {
    private class StubRpc : BaleRpc {
        override val state = MutableStateFlow(Connection.Connected)
        override var sessionId = 1L
        override val updates = MutableSharedFlow<SessionUpdate>()
        val calls = mutableListOf<Triple<String, String, MessageLite>>()
        var responder: (String, MessageLite) -> ByteArray = { _, _ -> byteArrayOf() }
        override suspend fun rpc(service: String, method: String, request: MessageLite, expectedSession: Long): ByteArray {
            calls += Triple(service, method, request)
            return responder(method, request)
        }
    }

    @Test fun `setOnline announces the flag on the presence service`() = runTest {
        val rpc = StubRpc()
        PresenceApi(rpc).setOnline(true)
        val call = rpc.calls.single()
        assertEquals("bale.presence.v1.Presence", call.first)
        assertEquals("SetOnline", call.second)
        assertTrue((call.third as Presence_SetOnline_Request).isOnline)
    }

    @Test fun `usersPresence dedupes ids and carries the subscribe flag`() = runTest {
        val rpc = StubRpc()
        rpc.responder = {
            Presence_GetUsersPresence_Response.newBuilder()
                .addPresences(Presences.newBuilder().setUserOnline(UserOnline2.newBuilder().setPeer(UserPeers.newBuilder().setUid(42))).build())
                .addPresences(
                    Presences.newBuilder()
                        .setUserOffline(
                            UserOffline2.newBuilder()
                                .setPeer(UserPeers.newBuilder().setUid(43))
                                .setDate(Int64Value.of(1_700_000_000L))
                                .setUnknownLastSeenValue(0),
                        )
                        .build(),
                )
                .build().toByteArray()
        }
        val response = PresenceApi(rpc).usersPresence(listOf(42, 43, 42, 0))
        val request = rpc.calls.single().third as Presence_GetUsersPresence_Request
        assertEquals(listOf(42, 43), request.userIdsList)
        assertTrue(request.doSubscribe.value)
        assertEquals(2, response.presencesCount)
        assertEquals(42, response.presencesList.first().userOnline.peer.uid)
        assertEquals(1_700_000_000L, response.presencesList.last().userOffline.date.value)
    }

    @Test fun `subscribeFromOnline drops zero ids and sends the user list`() = runTest {
        val rpc = StubRpc()
        PresenceApi(rpc).subscribeFromOnline(listOf(UserPeers.newBuilder().setUid(7).build(), UserPeers.newBuilder().build()))
        val request = rpc.calls.single().third as Presence_SubscribeFromOnline_Request
        assertEquals(1, request.usersCount)
        assertEquals(7, request.usersList.single().uid)
    }

    @Test fun `groupOnlineCount queries the group peer and parses the count`() = runTest {
        val rpc = StubRpc()
        rpc.responder = { Presence_GetGroupOnlineCount_Response.newBuilder().setCount(12).build().toByteArray() }
        val api = PresenceApi(rpc)
        assertEquals(12, api.groupOnlineCount(99, 555L))
        val request = rpc.calls.single().third as Presence_GetGroupOnlineCount_Request
        assertEquals(GroupPeer.newBuilder().setGroupId(99).setAccessHash(555L).build(), request.peer)
    }

    @Test fun `userPeer builds a keyed user peer and skips account zero`() {
        val peer = PresenceApi.userPeer(Peer(21, 1, 77L))!!
        assertEquals(21, peer.uid)
        assertEquals(77L, peer.accessHash)
        assertEquals(null, PresenceApi.userPeer(Peer(0, 1, 0L)))
    }
}
