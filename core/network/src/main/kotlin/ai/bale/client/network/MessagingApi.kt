package ai.bale.client.network

import ai.bale.client.domain.MessageReference
import ai.bale.client.domain.Peer
import ai.bale.client.protocol.*
import com.google.protobuf.BoolValue
import com.google.protobuf.Int64Value

/** Typed service routing. No retries of non-idempotent operations at this layer. */
class MessagingApi(private val rpc: BaleRpc) {
    suspend fun dialogs(before: Long = -1): Messaging_LoadDialogs_Response =
        Messaging_LoadDialogs_Response.parseFrom(rpc.rpc(MESSAGING, "LoadDialogs",
            Messaging_LoadDialogs_Request.newBuilder().setMinDate(before).setLimit(PAGE_SIZE).build()))

    suspend fun history(peer: Peer, before: Long): Messaging_LoadHistory_Response =
        Messaging_LoadHistory_Response.parseFrom(rpc.rpc(MESSAGING, "LoadHistory",
            Messaging_LoadHistory_Request.newBuilder().setPeer(peer.wire()).setDate(before)
                .setLoadMode(2).setLimit(PAGE_SIZE).build()))

    suspend fun send(peer: Peer, rid: Long, content: Message2, reply: MessageReference?): Msg_index_fe3fa18088__83329_h {
        val request = Messaging_SendMessage_Request.newBuilder().setPeer(peer.wire()).setRid(rid).setMessage(content)
        if (reply != null) request.setQuotedMessageReference(reference(peer, reply))
        return Msg_index_fe3fa18088__83329_h.parseFrom(rpc.rpc(MESSAGING, "SendMessage", request.build()))
    }

    suspend fun edit(peer: Peer, rid: Long, text: String) {
        rpc.rpc(MESSAGING, "UpdateMessage", Messaging_UpdateMessage_Request.newBuilder()
            .setPeer(peer.wire()).setRid(rid).setUpdatedMessage(text(text)).build())
    }

    suspend fun delete(peer: Peer, date: Long, forEveryone: Boolean) {
        require(date > 0)
        rpc.rpc(MESSAGING, "DeleteMessage", Messaging_DeleteMessage_Request.newBuilder().setPeer(peer.wire())
            .setDates(Dates.newBuilder().addDates(date)).setJustMine(BoolValue.of(!forEveryone)).build())
    }

    suspend fun forward(from: Peer, to: Peer, message: MessageReference) {
        rpc.rpc(MESSAGING, "ForwardMessages", Messaging_ForwardMessages_Request.newBuilder()
            .setPeer(Peer3.newBuilder().setType(to.kind).setId(to.id).setAccessHash(to.accessHash))
            .addForwardedMessages(reference(from, message)).build())
    }

    suspend fun react(peer: Peer, message: MessageReference, code: String, remove: Boolean): Field264? {
        val response = if (remove) {
            rpc.rpc(ABACUS, "MessageRemoveReaction", Abacus_MessageRemoveReaction_Request.newBuilder()
                .setPeer(peer.wire()).setRid(message.rid).setDate(message.date).setCode(code).build())
        } else {
            rpc.rpc(ABACUS, "MessageSetReaction", Abacus_MessageSetReaction_Request.newBuilder()
                .setPeer(peer.wire()).setRid(message.rid).setDate(message.date).setCode(code).build())
        }
        val parsed = Msg_4825_fafe26abbf__79002_p.parseFrom(response)
        return if (parsed.hasField18()) parsed.field18 else null
    }

    suspend fun search(query: String, peer: Peer?): Msg_modulesBuilder_e7b98d256c__62663_sr {
        val piece = Query2.newBuilder().setSearchPieceText(Query.newBuilder().setQuery(query)).build()
        val condition = if (peer == null) piece else Query2.newBuilder().setSearchAndCondition(
            SearchAndCondition.newBuilder().addAndQuery(piece).addAndQuery(
                Query2.newBuilder().setSearchPeerCondition(SearchPeerCondition.newBuilder().setPeer(peer.wire())))).build()
        return Msg_modulesBuilder_e7b98d256c__62663_sr.parseFrom(rpc.rpc(SEARCH, "SearchMessages",
            Search_SearchMessages_Request.newBuilder().setQuery(condition).build()))
    }

    suspend fun typing(peer: Peer, active: Boolean) {
        if (active) rpc.rpc(PRESENCE, "Typing", Presence_Typing_Request.newBuilder().setPeer(peer.wire()).setTypingType(1).build())
        else rpc.rpc(PRESENCE, "StopTyping", Presence_StopTyping_Request.newBuilder().setPeer(peer.wire()).setTypingType(1).build())
    }

    suspend fun read(peer: Peer, date: Long) {
        rpc.rpc(MESSAGING, "MessageRead", Messaging_MessageRead_Request.newBuilder().setPeer(peer.wire()).setDate(date).build())
    }

    suspend fun users(peers: List<UserPeers>): Users_LoadUsers_Response =
        Users_LoadUsers_Response.parseFrom(rpc.rpc(USERS, "LoadUsers", Users_LoadUsers_Request.newBuilder().addAllPeers(peers).build()))

    suspend fun groups(peers: List<GroupPeer>): Groups_LoadGroups_Response =
        Groups_LoadGroups_Response.parseFrom(rpc.rpc(GROUPS, "LoadGroups", Groups_LoadGroups_Request.newBuilder().addAllPeers(peers).build()))

    companion object {
        const val PAGE_SIZE = 50
        const val MESSAGING = "bale.messaging.v2.Messaging"
        const val ABACUS = "bale.abacus.v1.Abacus"
        const val SEARCH = "bale.search.v1.Search"
        const val PRESENCE = "bale.presence.v1.Presence"
        const val USERS = "bale.users.v1.Users"
        const val GROUPS = "bale.groups.v1.Groups"
        fun text(value: String): Message2 = Message2.newBuilder().setTextMessage(TextMessage.newBuilder().setText(value)).build()
        fun reference(peer: Peer, message: MessageReference): QuotedMessageReference =
            QuotedMessageReference.newBuilder().setPeer(peer.wire()).setRid(message.rid)
                .setDate(Int64Value.of(message.date)).apply {
                    if (message.sequence != 0L) setSeq(Int64Value.of(message.sequence))
                }.build()
        fun Peer.wire(): Peer2 = Peer2.newBuilder().setType(kind).setId(id).setAccessHash(accessHash).build()
    }
}
