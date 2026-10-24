package ai.bale.client.network

import ai.bale.client.domain.Peer
import ai.bale.client.network.MessagingApi.Companion.PRESENCE
import ai.bale.client.protocol.Presence_GetGroupOnlineCount_Request
import ai.bale.client.protocol.Presence_GetGroupOnlineCount_Response
import ai.bale.client.protocol.Presence_GetUsersPresence_Request
import ai.bale.client.protocol.Presence_GetUsersPresence_Response
import ai.bale.client.protocol.Presence_SetOnline_Request
import ai.bale.client.protocol.Presence_SubscribeFromOnline_Request
import ai.bale.client.protocol.GroupPeer
import ai.bale.client.protocol.UserPeers
import com.google.protobuf.BoolValue
import com.google.protobuf.MessageLite

/**
 * Presence RPCs (`bale.presence.v1.Presence`): announce this session as online, pull the current
 * online/last-seen snapshot for a set of users and subscribe to their live online/offline updates.
 */
class PresenceApi(private val rpc: BaleRpc, private val session: Long = rpc.sessionId) {
    private suspend fun call(method: String, request: MessageLite): ByteArray =
        rpc.rpc(PRESENCE, method, request, session)

    suspend fun setOnline(online: Boolean) {
        call("SetOnline", Presence_SetOnline_Request.newBuilder().setIsOnline(online).build())
    }

    suspend fun usersPresence(userIds: List<Int>, subscribe: Boolean = true): Presence_GetUsersPresence_Response =
        Presence_GetUsersPresence_Response.parseFrom(
            call(
                "GetUsersPresence",
                Presence_GetUsersPresence_Request.newBuilder()
                    .addAllUserIds(userIds.filter { it != 0 }.distinct())
                    .setDoSubscribe(BoolValue.of(subscribe))
                    .build(),
            ),
        )

    /** Ask the update stream to push online/offline changes for the given user peers. */
    suspend fun subscribeFromOnline(userPeers: List<UserPeers>) {
        val peers = userPeers.filter { it.uid != 0 }
        if (peers.isEmpty()) return
        call("SubscribeFromOnline", Presence_SubscribeFromOnline_Request.newBuilder().addAllUsers(peers).build())
    }

    /** Members currently online in a group (`Presence/GetGroupOnlineCount`). */
    suspend fun groupOnlineCount(groupId: Int, accessHash: Long): Int =
        Presence_GetGroupOnlineCount_Response.parseFrom(
            call(
                "GetGroupOnlineCount",
                Presence_GetGroupOnlineCount_Request.newBuilder()
                    .setPeer(GroupPeer.newBuilder().setGroupId(groupId).setAccessHash(accessHash).build())
                    .build(),
            ),
        ).count

    companion object {
        fun userPeer(peer: Peer): UserPeers? =
            peer.id.takeIf { it != 0 }?.let { UserPeers.newBuilder().setUid(it).setAccessHash(peer.accessHash).build() }
    }
}
