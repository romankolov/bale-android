package ai.bale.client.domain

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Wire peer IDs are signed int32; request/message identifiers are int64. */
data class Peer(val id: Int, val kind: Int, val accessHash: Long) {
    init { require(kind == 1 || kind == 2) }
    val key: String get() = "$kind:$id"
}

/** Bale ExPeerType values used by Files, Poll, Pin, and SendMessage RPCs. */
object ExPeerKind {
    const val PRIVATE = 1
    const val GROUP = 2
    const val CHANNEL = 3
    const val BOT = 4
    const val SUPERGROUP = 5
}

data class Account(val id: Int, val name: String)
data class Conversation(val peer: Peer, val title: String, val preview: String, val date: Long, val unread: Int, val muted: Boolean = false)
enum class Delivery { Pending, Sent, Failed }

enum class AttachmentKind { Photo, Video, Voice, Audio, Document, Sticker }

data class Attachment(
    val fileId: Long,
    val accessHash: Long,
    val storageVersion: Int = 0,
    val name: String,
    val mimeType: String,
    val sizeBytes: Int,
    val kind: AttachmentKind,
    val durationSeconds: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val thumbnail: ByteArray = byteArrayOf(),
) {
    override fun equals(other: Any?): Boolean = other is Attachment &&
        fileId == other.fileId && accessHash == other.accessHash &&
        storageVersion == other.storageVersion && name == other.name &&
        mimeType == other.mimeType && sizeBytes == other.sizeBytes &&
        kind == other.kind && durationSeconds == other.durationSeconds &&
        width == other.width && height == other.height && thumbnail.contentEquals(other.thumbnail)

    override fun hashCode(): Int = (((fileId.hashCode() * 31 + accessHash.hashCode()) * 31 + name.hashCode()) * 31) + thumbnail.contentHashCode()
}

data class UploadPayload(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
    val kind: AttachmentKind = inferAttachmentKind(mimeType),
    val durationSeconds: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    override fun equals(other: Any?): Boolean = other is UploadPayload &&
        name == other.name && mimeType == other.mimeType && kind == other.kind &&
        durationSeconds == other.durationSeconds && width == other.width &&
        height == other.height && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = (name.hashCode() * 31 + mimeType.hashCode()) * 31 + bytes.contentHashCode()
}

fun inferAttachmentKind(mimeType: String, fileName: String = ""): AttachmentKind = when {
    mimeType.startsWith("image/") -> AttachmentKind.Photo
    mimeType.startsWith("video/") -> AttachmentKind.Video
    mimeType.startsWith("audio/ogg") || mimeType.contains("voice") || mimeType.endsWith("mp4a-latm") ||
        (mimeType.startsWith("audio/") && fileName.startsWith("voice_", ignoreCase = true)) -> AttachmentKind.Voice
    mimeType.startsWith("audio/") -> AttachmentKind.Audio
    else -> AttachmentKind.Document
}

data class ForwardedInfo(
    val senderUserId: Int = 0,
    val publicGroupId: Int = 0,
    val authorName: String = "",
)

data class PollOptionView(val id: Int, val text: String, val votes: Int, val chosen: Boolean)

data class PollView(
    val pollId: Long,
    val question: String,
    val options: List<PollOptionView>,
    val anonymous: Boolean,
    val multipleChoice: Boolean,
    val closed: Boolean,
    val totalVoters: Int,
)

enum class BotButtonKind { Callback, ReplyText, OpenUrl, CopyText }

data class BotButton(
    val label: String,
    val value: String,
    val kind: BotButtonKind,
    val templateId: Long = 0,
)

data class ChatMessage(
    val peerKey: String,
    val rid: Long,
    val senderId: Int,
    val text: String,
    val date: Long,
    val delivery: Delivery,
    val supported: Boolean = true,
    val sequence: Long = 0,
    val quote: QuotePreview? = null,
    val edited: Boolean = false,
    val received: Boolean = false,
    val read: Boolean = false,
    val deleted: Boolean = false,
    val reactions: List<Reaction> = emptyList(),
    val attachment: Attachment? = null,
    val poll: PollView? = null,
    val buttons: List<List<BotButton>> = emptyList(),
    val serviceNotice: Boolean = false,
    val forwardedFrom: ForwardedInfo? = null,
) {
    val reference: MessageReference get() = MessageReference(rid, date, sequence)
}

data class MessageReference(val rid: Long, val date: Long, val sequence: Long = 0)
data class QuotePreview(val rid: Long, val senderId: Int, val text: String)
data class Reaction(val code: String, val count: Long, val mine: Boolean = false)
data class SearchHit(val peer: Peer, val title: String, val message: ChatMessage)
data class SearchPage(val hits: List<SearchHit>, val total: Int)

data class ContactEntry(
    val peer: Peer,
    val name: String,
    val handle: String = "",
    val isBot: Boolean = false,
    val isGroup: Boolean = false,
)

data class GroupMember(
    val userId: Int,
    val accessHash: Long = 0,
    val name: String,
    val isAdmin: Boolean = false,
    val title: String = "",
)

data class PeerProfile(
    val peer: Peer,
    val title: String,
    val handle: String = "",
    val about: String = "",
    val isGroup: Boolean = false,
    val isChannel: Boolean = false,
    val isBot: Boolean = false,
    val isBlocked: Boolean = false,
    val isMember: Boolean = true,
    val isOwner: Boolean = false,
    val membersCount: Int = 0,
    val inviteUrl: String = "",
    val members: List<GroupMember> = emptyList(),
    val pinnedMessages: List<ChatMessage> = emptyList(),
)

data class StoryItem(
    val id: String,
    val ownerId: Int,
    val ownerTitle: String,
    val text: String,
    val createdAt: Long,
    val myReaction: String = "",
    val isChannel: Boolean = false,
    val media: Attachment? = null,
)

data class AuthSessionInfo(
    val id: Int,
    val appTitle: String,
    val deviceTitle: String,
    val location: String,
    val authTimeSeconds: Long,
    val lastActiveAt: Long,
)

enum class Connection { Disconnected, Connecting, Connected, Reconnecting, Unauthorized }
enum class AuthStep { Phone, Code, Password, Registration }
data class AuthChallenge(val transaction: String, val step: AuthStep)

interface AuthRepository {
    val account: StateFlow<Account?>
    suspend fun restore()
    suspend fun start(phone: String): AuthChallenge
    suspend fun verify(challenge: AuthChallenge, value: String): AuthChallenge?
    suspend fun signOut()
    suspend fun sessions(): List<AuthSessionInfo> = emptyList()
    suspend fun terminateSession(id: Int) = Unit
    suspend fun terminateOtherSessions() = Unit
}

interface MessengerRepository {
    val connection: StateFlow<Connection>
    val onlineUsers: StateFlow<Set<Int>> get() = EMPTY_ONLINE
    fun conversations(): Flow<List<Conversation>>
    fun messages(peer: Peer): Flow<List<ChatMessage>>
    fun pagedMessages(peer: Peer): Flow<PagingData<ChatMessage>>
    fun typingUsers(peer: Peer): Flow<Set<Int>>
    suspend fun loadMoreConversations(): Int
    suspend fun refreshConversations()
    suspend fun loadHistory(peer: Peer, before: Long = -1): Int
    suspend fun loadOlderHistory(peer: Peer): Int
    /** Persist the stable rid before attempting network delivery. */
    suspend fun queue(peer: Peer, text: String, reply: ChatMessage? = null): Long
    suspend fun retry(peer: Peer, rid: Long)
    suspend fun markRead(peer: Peer, date: Long)
    suspend fun edit(peer: Peer, message: ChatMessage, text: String)
    suspend fun delete(peer: Peer, message: ChatMessage, forEveryone: Boolean)
    suspend fun forward(from: Peer, to: Peer, message: ChatMessage)
    suspend fun react(peer: Peer, message: ChatMessage, code: String, remove: Boolean)
    suspend fun searchMessages(query: String, peer: Peer? = null): SearchPage
    suspend fun setTyping(peer: Peer, active: Boolean)
    suspend fun sendMedia(peer: Peer, payload: UploadPayload, caption: String = "", reply: ChatMessage? = null) = Unit
    suspend fun downloadMedia(attachment: Attachment): String = ""
    suspend fun pinMessage(peer: Peer, message: ChatMessage, pin: Boolean) = Unit
    suspend fun clearChat(peer: Peer) = Unit
    suspend fun deleteChat(peer: Peer) = Unit
    suspend fun setDialogPinned(peer: Peer, pinned: Boolean) = Unit
    suspend fun setDialogArchived(peer: Peer, archived: Boolean) = Unit
    suspend fun createPoll(peer: Peer, question: String, options: List<String>, anonymous: Boolean = true, multipleChoice: Boolean = false) = Unit
    suspend fun votePoll(peer: Peer, message: ChatMessage, optionIds: List<Int>, retract: Boolean = false) = Unit
    suspend fun closePoll(peer: Peer, message: ChatMessage) = Unit
    suspend fun refreshPolls(peer: Peer) = Unit
    suspend fun pressBotButton(peer: Peer, button: BotButton) = Unit
    suspend fun contacts(): List<ContactEntry> = emptyList()
    suspend fun searchDirectory(query: String): List<ContactEntry> = emptyList()
    suspend fun addContact(peer: Peer) = Unit
    suspend fun removeContact(peer: Peer) = Unit
    suspend fun setBlocked(peer: Peer, blocked: Boolean) = Unit
    suspend fun loadProfile(peer: Peer): PeerProfile = PeerProfile(peer, peer.id.toString())
    suspend fun updateOwnProfile(name: String, handle: String, about: String) = Unit
    suspend fun uploadOwnAvatar(payload: UploadPayload) = Unit
    suspend fun createCommunity(title: String, isChannel: Boolean, handle: String = "", members: List<Peer> = emptyList()): Peer = Peer(1, 2, 0)
    suspend fun joinInviteOrHandle(input: String): Peer? = null
    suspend fun leaveCommunity(peer: Peer) = Unit
    suspend fun editCommunity(peer: Peer, title: String, about: String) = Unit
    suspend fun inviteMember(group: Peer, user: Peer) = Unit
    suspend fun kickMember(group: Peer, user: Peer) = Unit
    suspend fun setMemberAdmin(group: Peer, user: Peer, admin: Boolean, title: String = "") = Unit
    suspend fun revokeInviteLink(group: Peer): String = ""
    suspend fun loadStories(): List<StoryItem> = emptyList()
    suspend fun postTextStory(text: String) = Unit
    suspend fun reactStory(storyId: String, emoji: String) = Unit
    suspend fun deleteStory(storyId: String) = Unit
}

private val EMPTY_ONLINE = MutableStateFlow(emptySet<Int>())

class RpcException(val code: Long, val reason: String) : Exception("Bale RPC failed ($code)")
class ProtocolException(message: String) : Exception(message)

/** Accept localized digits; never silently strip arbitrary input into a new number. */
fun normalizePhone(input: String): String {
    val normalized = buildString {
        input.trim().forEachIndexed { index, char ->
            when {
                char == '+' && index == 0 -> Unit
                char in " -()" -> Unit
                char.digitToIntOrNull() != null -> append(char.digitToInt())
                else -> throw IllegalArgumentException("Invalid phone number")
            }
        }
    }
    require(normalized.length in 8..15 && normalized.first() != '0') { "Use international format" }
    return normalized
}
