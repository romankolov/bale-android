package ai.bale.client.network

import ai.bale.client.domain.MessageReference
import ai.bale.client.domain.Peer
import ai.bale.client.protocol.*
import com.google.protobuf.BoolValue
import com.google.protobuf.Int64Value
import com.google.protobuf.MessageLite

/** Typed service routing. No retries of non-idempotent operations at this layer. */
class MessagingApi(private val rpc: BaleRpc, private val session: Long = rpc.sessionId) {
    private suspend fun call(service: String, method: String, request: MessageLite): ByteArray =
        rpc.rpc(service, method, request, session)

    suspend fun dialogs(before: Long = -1): Messaging_LoadDialogs_Response =
        Messaging_LoadDialogs_Response.parseFrom(call(MESSAGING, "LoadDialogs",
            Messaging_LoadDialogs_Request.newBuilder().setMinDate(before).setLimit(PAGE_SIZE).build()))

    suspend fun history(peer: Peer, before: Long): Messaging_LoadHistory_Response =
        Messaging_LoadHistory_Response.parseFrom(call(MESSAGING, "LoadHistory",
            Messaging_LoadHistory_Request.newBuilder().setPeer(peer.wire()).setDate(before)
                .setLoadMode(2).setLimit(PAGE_SIZE).build()))

    suspend fun send(peer: Peer, rid: Long, content: Message2, reply: MessageReference?): Msg_index_fe3fa18088__83329_h {
        val request = Messaging_SendMessage_Request.newBuilder().setPeer(peer.wire()).setRid(rid).setMessage(content)
        if (reply != null) request.setQuotedMessageReference(reference(peer, reply))
        return Msg_index_fe3fa18088__83329_h.parseFrom(call(MESSAGING, "SendMessage", request.build()))
    }

    suspend fun edit(peer: Peer, rid: Long, text: String) {
        call(MESSAGING, "UpdateMessage", Messaging_UpdateMessage_Request.newBuilder()
            .setPeer(peer.wire()).setRid(rid).setUpdatedMessage(text(text)).build())
    }

    suspend fun delete(peer: Peer, date: Long, forEveryone: Boolean) {
        require(date > 0)
        call(MESSAGING, "DeleteMessage", Messaging_DeleteMessage_Request.newBuilder().setPeer(peer.wire())
            .setDates(Dates.newBuilder().addDates(date)).setJustMine(BoolValue.of(!forEveryone)).build())
    }

    suspend fun forward(from: Peer, to: Peer, message: MessageReference, destinationRid: Long) {
        call(MESSAGING, "ForwardMessages", Messaging_ForwardMessages_Request.newBuilder()
            .setPeer(Peer3.newBuilder().setType(to.kind).setId(to.id).setAccessHash(to.accessHash))
            .addMessageIds(destinationRid).addForwardedMessages(reference(from, message)).build())
    }

    suspend fun react(peer: Peer, message: MessageReference, code: String, remove: Boolean): List<Field264> {
        val response = if (remove) {
            call(ABACUS, "MessageRemoveReaction", Abacus_MessageRemoveReaction_Request.newBuilder()
                .setPeer(peer.wire()).setRid(message.rid).setDate(message.date).setCode(code).build())
        } else {
            call(ABACUS, "MessageSetReaction", Abacus_MessageSetReaction_Request.newBuilder()
                .setPeer(peer.wire()).setRid(message.rid).setDate(message.date).setCode(code).build())
        }
        return Msg_4825_fafe26abbf__79002_p.parseFrom(response).reactionsList
    }

    suspend fun search(query: String, peer: Peer?): Msg_modulesBuilder_e7b98d256c__62663_sr {
        val piece = Query2.newBuilder().setSearchPieceText(Query.newBuilder().setQuery(query)).build()
        val condition = if (peer == null) piece else Query2.newBuilder().setSearchAndCondition(
            SearchAndCondition.newBuilder().addAndQuery(piece).addAndQuery(
                Query2.newBuilder().setSearchPeerCondition(SearchPeerCondition.newBuilder().setPeer(peer.wire())))).build()
        return Msg_modulesBuilder_e7b98d256c__62663_sr.parseFrom(call(SEARCH, "SearchMessages",
            Search_SearchMessages_Request.newBuilder().setQuery(condition).build()))
    }

    suspend fun typing(peer: Peer, active: Boolean) {
        if (active) call(PRESENCE, "Typing", Presence_Typing_Request.newBuilder().setPeer(peer.wire()).setTypingType(1).build())
        else call(PRESENCE, "StopTyping", Presence_StopTyping_Request.newBuilder().setPeer(peer.wire()).setTypingType(1).build())
    }

    suspend fun read(peer: Peer, date: Long) {
        call(MESSAGING, "MessageRead", Messaging_MessageRead_Request.newBuilder().setPeer(peer.wire()).setDate(date).build())
    }

    suspend fun pinMessage(peer: Peer, message: MessageReference, pin: Boolean) {
        val id = MessageId.newBuilder().setRid(message.rid).setDate(message.date).setSeq(message.sequence).build()
        if (pin) {
            call(MESSAGING, "PinMessage", Messaging_PinMessage_Request.newBuilder()
                .setPeer(peer.exWire()).setMessageId(id).setJustMine(false).build())
        } else {
            call(MESSAGING, "UnPinMessages", Messaging_UnPinMessages_Request.newBuilder()
                .setPeer(peer.exWire()).addMessages(id).setAll(false).build())
        }
    }

    suspend fun pinnedMessages(peer: Peer): List<History> =
        Messaging_LoadPinnedMessages_Response.parseFrom(call(MESSAGING, "LoadPinnedMessages",
            Messaging_LoadPinnedMessages_Request.newBuilder().setPeer(peer.exWire()).build())).pinnedMessagesList

    suspend fun clearChat(peer: Peer) {
        call(MESSAGING, "ClearChat", Messaging_ClearChat_Request.newBuilder().setPeer(peer.wire()).build())
    }

    suspend fun deleteChat(peer: Peer) {
        call(MESSAGING, "DeleteChat", Messaging_DeleteChat_Request.newBuilder().setPeer(peer.wire()).build())
    }

    suspend fun setDialogPinned(peer: Peer, pinned: Boolean) {
        if (pinned) call(MESSAGING, "PinDialogs", Messaging_PinDialogs_Request.newBuilder().addPeers(peer.exWire()).build())
        else call(MESSAGING, "UnpinDialogs", Messaging_UnpinDialogs_Request.newBuilder().addPeers(peer.exWire()).build())
    }

    suspend fun setDialogArchived(peer: Peer, archived: Boolean) {
        if (archived) call(MESSAGING, "ArchiveDialogs", Messaging_ArchiveDialogs_Request.newBuilder().addExPeers(peer.exWire()).build())
        else call(MESSAGING, "UnArchiveDialogs", Messaging_UnArchiveDialogs_Request.newBuilder().addExPeers(peer.exWire()).build())
    }

    suspend fun createPoll(peer: Peer, question: String, options: List<String>, anonymous: Boolean, multiple: Boolean, now: Long): Long {
        val message = pollMessage(0, question, options, anonymous, multiple)
        return Poll_CreatePoll_Response.parseFrom(call(POLL, "CreatePoll", Poll_CreatePoll_Request.newBuilder()
            .setPollMessage(message).setCreateAt(now).setExpeer(peer.exWire()).build())).pollId
    }

    suspend fun votePoll(pollId: Long, optionIds: List<Int>, retract: Boolean, now: Long): PollResult =
        Poll_Vote_Response.parseFrom(call(POLL, "Vote", Poll_Vote_Request.newBuilder()
            .setPollId(pollId).setIsRetract(retract).setVoteAt(now)
            .addAllOptionIds(optionIds.map(Int::toLong)).build())).pollResult

    suspend fun closePoll(pollId: Long) {
        call(POLL, "ClosePoll", Poll_ClosePoll_Request.newBuilder().setPollId(pollId).build())
    }

    suspend fun pollResults(pollIds: List<Long>): List<PollResult> =
        Poll_GetPollResults_Response.parseFrom(call(POLL, "GetPollResults",
            Poll_GetPollResults_Request.newBuilder().addAllPollIds(pollIds).build())).pollResultsList

    suspend fun users(peers: List<UserPeers>): Users_LoadUsers_Response =
        Users_LoadUsers_Response.parseFrom(call(USERS, "LoadUsers", Users_LoadUsers_Request.newBuilder().addAllPeers(peers).build()))

    suspend fun groups(peers: List<GroupPeer>): Groups_LoadGroups_Response =
        Groups_LoadGroups_Response.parseFrom(call(GROUPS, "LoadGroups", Groups_LoadGroups_Request.newBuilder().addAllPeers(peers).build()))

    companion object {
        const val PAGE_SIZE = 50
        const val MESSAGING = "bale.messaging.v2.Messaging"
        const val ABACUS = "bale.abacus.v1.Abacus"
        const val SEARCH = "bale.search.v1.Search"
        const val PRESENCE = "bale.presence.v1.Presence"
        const val POLL = "bale.poll.v1.Poll"
        const val USERS = "bale.users.v1.Users"
        const val GROUPS = "bale.groups.v1.Groups"
        fun text(value: String): Message2 = Message2.newBuilder().setTextMessage(TextMessage.newBuilder().setText(value)).build()
        fun templateResponse(value: String, templateId: Long): Message2 = Message2.newBuilder().setTemplateMessageResponse(
            TemplateMessageResponse.newBuilder().setTmrMessage(value).setTemplateMessageResponseId(templateId)).build()
        fun pollMessage(pollId: Long, question: String, options: List<String>, anonymous: Boolean, multiple: Boolean): PollMessage =
            PollMessage.newBuilder().setPollId(pollId).setQuestion(question).setIsAnonymous(anonymous)
                .setType(if (multiple) 1 else 0)
                .addAllOptions(options.mapIndexed { index, text -> Options.newBuilder().setId(index).setText(text).build() })
                .build()
        fun reference(peer: Peer, message: MessageReference): QuotedMessageReference =
            QuotedMessageReference.newBuilder().setPeer(peer.wire()).setRid(message.rid)
                .setDate(Int64Value.of(message.date)).apply {
                    if (message.sequence != 0L) setSeq(Int64Value.of(message.sequence))
                }.build()
        fun Peer.wire(): Peer2 = Peer2.newBuilder().setType(kind).setId(id).setAccessHash(accessHash).build()
        fun Peer.exWire(): ExPeer = ExPeer.newBuilder().setType(kind).setId(id).setAccessHash(accessHash).build()
    }
}
