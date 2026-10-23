package ai.bale.client.network

import ai.bale.client.domain.Peer
import ai.bale.client.network.MessagingApi.Companion.GROUPS
import ai.bale.client.network.MessagingApi.Companion.USERS
import ai.bale.client.protocol.*
import com.google.protobuf.BoolValue
import com.google.protobuf.MessageLite
import com.google.protobuf.StringValue

/** Contacts, profiles, groups/channels, stories and active-session RPCs. */
class DirectoryApi(private val rpc: BaleRpc, private val session: Long = rpc.sessionId) {
    private suspend fun call(service: String, method: String, request: MessageLite): ByteArray =
        rpc.rpc(service, method, request, session)

    // --- Contacts & User Profiles ---
    suspend fun contacts(): Users_GetContacts_Response =
        Users_GetContacts_Response.parseFrom(call(USERS, "GetContacts", Users_GetContacts_Request.getDefaultInstance()))

    suspend fun searchContacts(query: String): Users_SearchContacts_Response =
        Users_SearchContacts_Response.parseFrom(call(USERS, "SearchContacts",
            Users_SearchContacts_Request.newBuilder().setRequest(query.trim().removePrefix("@")).build()))

    suspend fun addContact(peer: Peer) {
        call(USERS, "AddContact", Users_AddContact_Request.newBuilder().setUid(peer.id).setAccessHash(peer.accessHash).build())
    }

    suspend fun removeContact(peer: Peer) {
        call(USERS, "RemoveContact", Users_RemoveContact_Request.newBuilder().setUid(peer.id).setAccessHash(peer.accessHash).build())
    }

    suspend fun setBlocked(peer: Peer, blocked: Boolean) {
        val target = UserPeers.newBuilder().setUid(peer.id).setAccessHash(peer.accessHash).build()
        if (blocked) call(USERS, "BlockUser", Users_BlockUser_Request.newBuilder().setPeer(target).build())
        else call(USERS, "UnblockUser", Users_UnblockUser_Request.newBuilder().setPeer(target).build())
    }

    suspend fun fullUser(peer: Peer): FullUser =
        Users_GetFullUser_Response.parseFrom(call(USERS, "GetFullUser",
            Users_GetFullUser_Request.newBuilder().setPeer(UserPeers.newBuilder().setUid(peer.id).setAccessHash(peer.accessHash)).build())).fullUser

    suspend fun editProfile(name: String, handle: String, about: String) {
        if (name.isNotBlank()) call(USERS, "EditName", Users_EditName_Request.newBuilder().setName(name.trim()).build())
        call(USERS, "EditNickName", Users_EditNickName_Request.newBuilder().setNickname(StringValue.of(handle.trim().removePrefix("@"))).build())
        call(USERS, "EditAbout", Users_EditAbout_Request.newBuilder().setAbout(StringValue.of(about.trim())).build())
    }

    suspend fun editAvatar(fileId: Long, accessHash: Long = 0L) {
        call(USERS, "EditAvatar", Users_EditAvatar_Request.newBuilder()
            .setFileLocation(FileLocation.newBuilder().setFileId(fileId).setAccessHash(accessHash)).build())
    }

    // --- Groups & Channels ---
    suspend fun fullGroup(peer: Peer): FullGroup =
        Groups_GetFullGroup_Response.parseFrom(call(GROUPS, "GetFullGroup",
            Groups_GetFullGroup_Request.newBuilder().setPeer(groupPeer(peer)).build())).fullGroup

    suspend fun members(peer: Peer, limit: Int = 100): List<Members> =
        Groups_LoadMembers_Response.parseFrom(call(GROUPS, "LoadMembers",
            Groups_LoadMembers_Request.newBuilder().setGroup(groupPeer(peer)).setLimit(limit).build())).membersList

    suspend fun createCommunity(rid: Long, title: String, isChannel: Boolean, handle: String, members: List<Peer>): Groups2 {
        val req = Groups_CreateGroup_Request.newBuilder()
            .setRid(rid)
            .setTitle(title.trim())
            .setGroupType(if (isChannel) 1 else 0)
            .setRestriction(if (handle.isNotBlank()) 1 else 0)
            .addAllUsers(members.map { UserPeers.newBuilder().setUid(it.id).setAccessHash(it.accessHash).build() })
            .apply { if (handle.isNotBlank()) setNick(StringValue.of(handle.trim().removePrefix("@"))) }
            .build()
        return Groups_CreateGroup_Response.parseFrom(call(GROUPS, "CreateGroup", req)).group
    }

    suspend fun editCommunity(peer: Peer, rid: Long, title: String, about: String) {
        if (title.isNotBlank()) {
            call(GROUPS, "EditGroupTitle", Groups_EditGroupTitle_Request.newBuilder()
                .setGroupPeer(groupPeer(peer)).setTitle(title.trim()).setRid(rid).build())
        }
        call(GROUPS, "EditGroupAbout", Groups_EditGroupAbout_Request.newBuilder()
            .setGroupPeer(groupPeer(peer)).setRid(rid + 1).setAbout(StringValue.of(about.trim())).build())
    }

    suspend fun inviteUrl(peer: Peer): String =
        Msg_1730_20e6817242__61193_L.parseFrom(call(GROUPS, "GetGroupInviteURL",
            Groups_GetGroupInviteURL_Request.newBuilder().setGroupPeer(groupPeer(peer)).build())).url

    suspend fun revokeInviteUrl(peer: Peer): String =
        Msg_1730_20e6817242__61193_L.parseFrom(call(GROUPS, "RevokeInviteURL",
            Groups_RevokeInviteURL_Request.newBuilder().setGroupPeer(groupPeer(peer)).build())).url

    suspend fun joinByToken(token: String): Groups2 =
        Groups_JoinGroup_Response.parseFrom(call(GROUPS, "JoinGroup",
            Groups_JoinGroup_Request.newBuilder().setToken(token.trim()).build())).group

    suspend fun joinPublic(peer: Peer): Groups2 =
        Groups_JoinPublicGroup_Response.parseFrom(call(GROUPS, "JoinPublicGroup",
            Groups_JoinPublicGroup_Request.newBuilder().setPeer(
                ai.bale.client.protocol.Peer.newBuilder().setType(peer.kind).setId(peer.id)).build())).group

    suspend fun leaveGroup(peer: Peer, rid: Long) {
        call(GROUPS, "LeaveGroup", Groups_LeaveGroup_Request.newBuilder()
            .setGroupPeer(groupPeer(peer)).setRid(rid).setMakeOrphan(BoolValue.of(false)).build())
    }

    suspend fun inviteUser(group: Peer, user: Peer, rid: Long) {
        call(GROUPS, "InviteUser", Groups_InviteUser_Request.newBuilder()
            .setGroupPeer(groupPeer(group)).setRid(rid)
            .setUser(UserPeers.newBuilder().setUid(user.id).setAccessHash(user.accessHash)).build())
    }

    suspend fun kickUser(group: Peer, user: Peer, rid: Long) {
        call(GROUPS, "KickUser", Groups_KickUser_Request.newBuilder()
            .setGroupPeer(groupPeer(group)).setRid(rid)
            .setUser(UserPeers.newBuilder().setUid(user.id).setAccessHash(user.accessHash)).build())
    }

    suspend fun setMemberAdmin(group: Peer, user: Peer, admin: Boolean, title: String) {
        val target = UserPeers.newBuilder().setUid(user.id).setAccessHash(user.accessHash).build()
        if (admin) {
            call(GROUPS, "MakeUserAdmin", Groups_MakeUserAdmin_Request.newBuilder()
                .setGroupPeer(groupPeer(group)).setUserPeer(target)
                .setAdminTitle(StringValue.of(title.trim())).build())
        } else {
            call(GROUPS, "RemoveUserAdmin", Groups_RemoveUserAdmin_Request.newBuilder()
                .setGroupPeer(groupPeer(group)).setUserPeer(target).build())
        }
    }

    // --- Stories ---
    suspend fun allStories(): Story_GetAllStories_Response =
        Story_GetAllStories_Response.parseFrom(call(STORY, "GetAllStories", Story_GetAllStories_Request.getDefaultInstance()))

    suspend fun addTextStory(text: String): String =
        Msg_6806_ad481ac22d__48949_u.parseFrom(call(STORY, "AddStory",
            Story_AddStory_Request.newBuilder()
                .setTextStory(TextStory.newBuilder().setText(text.trim())
                    .setConfig(Config.newBuilder().setBackgroundColor("#0F5B42").setTextColor("#FFFFFF")))
                .build())).storyId

    suspend fun reactStory(storyId: String, emoji: String) {
        call(STORY, "ReactToStory", Story_ReactToStory_Request.newBuilder()
            .setStoryId(storyId).setReaction(emoji).setType(1).setReactionType(1).build())
    }

    suspend fun removeStory(storyId: String) {
        call(STORY, "RemoveStory", Story_RemoveStory_Request.newBuilder().setStoryId(storyId).build())
    }

    // --- Active Sessions & Remote Logout ---
    suspend fun authSessions(): List<UserAuths> =
        Auth_GetAuthSessions_Response.parseFrom(call(AUTH, "GetAuthSessions", Auth_GetAuthSessions_Request.getDefaultInstance())).userAuthsList

    suspend fun terminateSession(id: Int) {
        call(AUTH, "TerminateSession", Auth_TerminateSession_Request.newBuilder().setId(id).build())
    }

    suspend fun terminateAllSessions() {
        call(AUTH, "TerminateAllSessions", Auth_TerminateAllSessions_Request.getDefaultInstance())
    }

    suspend fun remoteSignOut() {
        call(AUTH, "SignOut", Auth_SignOut_Request.getDefaultInstance())
    }

    companion object {
        const val STORY = "bale.story.v1.Story"
        const val AUTH = "bale.auth.v1.Auth"
        fun groupPeer(peer: Peer): GroupPeer = GroupPeer.newBuilder().setGroupId(peer.id).setAccessHash(peer.accessHash).build()
    }
}
