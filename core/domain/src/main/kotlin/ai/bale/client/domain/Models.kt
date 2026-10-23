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

/** Bale ExPeerType values used by Files, Poll, Pin, Folders, Scheduler and SendMessage RPCs. */
object ExPeerKind {
    const val PRIVATE = 1
    const val GROUP = 2
    const val CHANNEL = 3
    const val BOT = 4
    const val SUPERGROUP = 5
}

/** Reference to a stored image (avatar, sticker, thumbnail) addressable through Files. */
data class AvatarRef(val fileId: Long, val accessHash: Long)

data class Account(val id: Int, val name: String)

data class Conversation(
    val peer: Peer,
    val title: String,
    val preview: String,
    val date: Long,
    val unread: Int,
    val muted: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val markedUnread: Boolean = false,
    val unreadMentions: Int = 0,
    val draft: String = "",
    val isChannel: Boolean = false,
    val isBot: Boolean = false,
    val isSavedMessages: Boolean = false,
    val avatar: AvatarRef? = null,
)

/** Presence snapshot for one user, fed by `GetUsersPresence`, live updates and `userLastSeen`. */
data class PeerPresence(
    val online: Boolean = false,
    /** Epoch seconds of the last observed activity; 0 when unknown. */
    val lastSeenEpochSec: Long = 0,
    /** The peer hides an exact timestamp; `unknownLevel` carries the coarse bucket instead. */
    val lastSeenUnknown: Boolean = false,
    val unknownLevel: Int = 0,
)

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

enum class BotButtonKind { Callback, ReplyText, OpenUrl, CopyText, MiniApp }

data class BotButton(
    val label: String,
    val value: String,
    val kind: BotButtonKind,
    val templateId: Long = 0,
)

data class LinkPreviewInfo(
    val url: String,
    val title: String = "",
    val description: String = "",
    val imageUrl: String = "",
    val summary: String = "",
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
    val senderName: String = "",
    val senderAvatar: AvatarRef? = null,
    val repliesCount: Int = 0,
    val stickerCollectionId: Int = 0,
    val stickerCollectionAccessHash: Long = 0,
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
    val avatar: AvatarRef? = null,
)

data class GroupMember(
    val userId: Int,
    val accessHash: Long = 0,
    val name: String,
    val isAdmin: Boolean = false,
    val title: String = "",
    val avatar: AvatarRef? = null,
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
    val avatar: AvatarRef? = null,
    val botCommands: List<BotCommandInfo> = emptyList(),
    val botMenuButton: BotMenuAction? = null,
    val bannedMembers: List<GroupMember> = emptyList(),
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
    val avatar: AvatarRef? = null,
)

data class AuthSessionInfo(
    val id: Int,
    val appTitle: String,
    val deviceTitle: String,
    val location: String,
    val authTimeSeconds: Long,
    val lastActiveAt: Long,
)

// ---------------------------------------------------------------------------
// Release-candidate feature surfaces (folder, stickers, scheduling, bots).
// ---------------------------------------------------------------------------

data class ChatFolder(
    val id: Int,
    val name: String,
    val peerKeys: Set<String>,
    val unreadCount: Int = 0,
    val reserved: Boolean = false,
)

data class StickerItem(
    val id: Int,
    val emoji: String,
    val collectionId: Int,
    val collectionAccessHash: Long,
    val fileId: Long,
    val accessHash: Long,
    val width: Int = 0,
    val height: Int = 0,
    val animated: Boolean = false,
    /** Wire format stored by the server (`Collections.Field265.format`); 0 lets the server default apply. */
    val format: Int = 0,
    val previewBytes: ByteArray = byteArrayOf(),
) {
    override fun equals(other: Any?): Boolean = other is StickerItem &&
        id == other.id && collectionId == other.collectionId &&
        collectionAccessHash == other.collectionAccessHash && fileId == other.fileId &&
        accessHash == other.accessHash && emoji == other.emoji && animated == other.animated &&
        previewBytes.contentEquals(other.previewBytes)

    override fun hashCode(): Int = ((id * 31 + collectionId) * 31 + collectionAccessHash.hashCode()) * 31 + previewBytes.contentHashCode()
}

data class StickerPack(
    val id: Int,
    val accessHash: Long,
    val name: String,
    val animated: Boolean,
    val owned: Boolean,
    val stickers: List<StickerItem>,
)

data class SavedGif(
    val fileId: Long,
    val accessHash: Long,
    val storageVersion: Int = 0,
    val mimeType: String = "video/mp4",
    val sizeBytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val durationSeconds: Int = 0,
    val usedAt: Long = 0,
    val thumbnail: ByteArray = byteArrayOf(),
) {
    override fun equals(other: Any?): Boolean = other is SavedGif &&
        fileId == other.fileId && accessHash == other.accessHash && mimeType == other.mimeType &&
        sizeBytes == other.sizeBytes && width == other.width && height == other.height &&
        durationSeconds == other.durationSeconds && thumbnail.contentEquals(other.thumbnail)

    override fun hashCode(): Int = (fileId.hashCode() * 31 + accessHash.hashCode()) * 31 + thumbnail.contentHashCode()
}

data class ScheduledMessageItem(
    val taskId: Long,
    val peerKey: String,
    val text: String,
    val status: Int = 0,
)

enum class SharedMediaTab(val wireType: Int) {
    Media(1),
    Files(2),
    Voice(3),
    Links(4),
}

data class PollVoter(val userId: Int, val name: String, val votedAtMillis: Long)

data class PollOptionVoters(
    val optionId: Int,
    val optionText: String,
    val votesCount: Int,
    val voters: List<PollVoter>,
)

data class ReactionVoter(
    val userId: Int,
    val name: String,
    val emoji: String,
    val reactedAtMillis: Long,
)

data class BotCommandInfo(val command: String, val description: String)

data class BotMenuAction(
    val text: String,
    val miniAppUrl: String = "",
    val isCommandsMenu: Boolean = false,
)

data class MiniAppSession(val url: String, val queryId: String)

data class RecommendedBot(
    val userId: Int,
    val accessHash: Long = 0,
    val title: String = "",
    val handle: String = "",
    val about: String = "",
    val description: String = "",
    val avatar: AvatarRef? = null,
)

data class InlineBotResultItem(
    val queryId: Long,
    val resultId: String,
    val title: String,
    val description: String,
    val sendText: String,
    val previewUrl: String = "",
)

enum class AppThemeMode { System, Light, Dark }

enum class AppLanguage(val tag: String) {
    System(""),
    English("en"),
    Persian("fa"),
}

data class ClientPreferences(
    val themeMode: AppThemeMode = AppThemeMode.System,
    val dynamicColor: Boolean = true,
    val language: AppLanguage = AppLanguage.System,
)

/** Privacy is surfaced read-only: values come from `Users/GetUserFullPrivacy`. */
data class SecurityAndPrivacyState(
    val twoFactorEnabled: Boolean = false,
    val invitePrivacy: Int = 0,
    val presencePrivacy: Int = 0,
    val blockedUsers: List<ContactEntry> = emptyList(),
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
    suspend fun isTwoFactorEnabled(): Boolean = false
    suspend fun enableTwoFactor(email: String, password: String) = Unit
    suspend fun disableTwoFactor() = Unit
}

interface MessengerRepository {
    val connection: StateFlow<Connection>
    val onlineUsers: StateFlow<Set<Int>> get() = EMPTY_ONLINE
    /** Live presence snapshot keyed by user id (online flag plus last-seen info). */
    val peerPresence: StateFlow<Map<Int, PeerPresence>> get() = EMPTY_PRESENCE
    /** Members online per group id, from `GroupOnline` updates and `GetGroupOnlineCount`. */
    val groupOnlineCounts: StateFlow<Map<Int, Int>> get() = EMPTY_GROUP_ONLINE
    val preferences: StateFlow<ClientPreferences> get() = DEFAULT_PREFERENCES
    /** Epoch millis of the last socket-pushed update; staleness drives the fallback poller. */
    val lastPushAt: StateFlow<Long> get() = NEVER_PUSHED
    val avatarPaths: StateFlow<Map<Long, String>> get() = EMPTY_AVATAR_PATHS
    val folders: StateFlow<List<ChatFolder>> get() = EMPTY_FOLDERS

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
    /** Upload with fractional progress callbacks (0f..1f); the no-callback form stays available. */
    suspend fun sendMediaWithProgress(
        peer: Peer,
        payload: UploadPayload,
        caption: String = "",
        reply: ChatMessage? = null,
        onProgress: (Float) -> Unit = {},
    ) = sendMedia(peer, payload, caption, reply)
    suspend fun downloadMedia(attachment: Attachment): String = ""
    /** Download with fractional progress callbacks (0f..1f) while bytes stream in. */
    suspend fun downloadMedia(attachment: Attachment, onProgress: (Float) -> Unit): String = downloadMedia(attachment)
    suspend fun ensureAvatar(avatar: AvatarRef?) = Unit
    suspend fun pinMessage(peer: Peer, message: ChatMessage, pin: Boolean) = Unit
    suspend fun clearChat(peer: Peer) = Unit
    suspend fun deleteChat(peer: Peer) = Unit
    suspend fun setDialogPinned(peer: Peer, pinned: Boolean) = Unit
    suspend fun setDialogArchived(peer: Peer, archived: Boolean) = Unit
    suspend fun setDialogMuted(peer: Peer, muted: Boolean) = Unit
    suspend fun setDialogMarkedUnread(peer: Peer, unread: Boolean) = Unit
    suspend fun saveDraft(peer: Peer, draft: String) = Unit
    suspend fun openSavedMessages(): Peer? = null

    // Chat folders (`Messaging/LoadFolders` family).
    suspend fun refreshFolders() = Unit
    suspend fun createFolder(name: String, peers: List<Peer>): Int = 0
    suspend fun renameFolder(folderId: Int, name: String) = Unit
    suspend fun deleteFolder(folderId: Int) = Unit
    suspend fun addChatToFolder(peer: Peer, folderId: Int): Boolean = false
    suspend fun removeChatFromFolder(peer: Peer, folderId: Int) = Unit

    // Polls and message inspection.
    suspend fun createPoll(peer: Peer, question: String, options: List<String>, anonymous: Boolean = true, multipleChoice: Boolean = false) = Unit
    suspend fun votePoll(peer: Peer, message: ChatMessage, optionIds: List<Int>, retract: Boolean = false) = Unit
    suspend fun closePoll(peer: Peer, message: ChatMessage) = Unit
    suspend fun refreshPolls(peer: Peer) = Unit
    suspend fun fullPollVoters(peer: Peer, message: ChatMessage): List<PollOptionVoters> = emptyList()
    suspend fun reactionVoters(peer: Peer, message: ChatMessage): List<ReactionVoter> = emptyList()

    // Stickers, GIFs, link previews and voice transcripts.
    suspend fun loadStickerPacks(): List<StickerPack> = emptyList()
    suspend fun sendSticker(peer: Peer, sticker: StickerItem) = Unit
    suspend fun addStickerPack(collectionId: Int, accessHash: Long) = Unit
    suspend fun removeStickerPack(collectionId: Int, accessHash: Long) = Unit
    suspend fun loadSavedGifs(): List<SavedGif> = emptyList()
    suspend fun sendSavedGif(peer: Peer, gif: SavedGif) = Unit
    suspend fun saveGif(attachment: Attachment) = Unit
    suspend fun removeGif(gif: SavedGif) = Unit
    suspend fun fetchLinkPreview(url: String, withSummary: Boolean = false): LinkPreviewInfo? = null
    suspend fun transcribeVoice(peer: Peer, message: ChatMessage): String = ""

    // Threads / channel comments, scheduled sending and shared media.
    suspend fun loadThreadReplies(peer: Peer, rootMessage: ChatMessage): List<ChatMessage> = emptyList()
    suspend fun sendThreadReply(peer: Peer, rootMessage: ChatMessage, text: String) = Unit
    suspend fun listScheduledMessages(peer: Peer): List<ScheduledMessageItem> = emptyList()
    suspend fun scheduleMessage(peer: Peer, text: String, sendAtMillis: Long) = Unit
    suspend fun sendScheduledNow(taskId: Long) = Unit
    suspend fun cancelScheduledMessage(taskId: Long) = Unit
    suspend fun loadSharedMedia(peer: Peer, tab: SharedMediaTab): List<ChatMessage> = emptyList()

    // Bots, inline bots and Mini-Apps.
    suspend fun pressBotButton(peer: Peer, button: BotButton) = Unit
    suspend fun openMiniApp(botPeer: Peer, startParam: String = ""): MiniAppSession? = null
    suspend fun submitMiniAppQuery(botPeer: Peer, queryId: String, data: String, buttonText: String = "") = Unit
    suspend fun loadRecommendedBots(): List<RecommendedBot> = emptyList()
    suspend fun queryInlineBot(botHandle: String, peer: Peer, query: String): List<InlineBotResultItem> = emptyList()

    // Directory, communities, administration, moderation and account settings.
    suspend fun contacts(): List<ContactEntry> = emptyList()
    suspend fun searchDirectory(query: String): List<ContactEntry> = emptyList()
    suspend fun addContact(peer: Peer) = Unit
    suspend fun removeContact(peer: Peer) = Unit
    suspend fun setBlocked(peer: Peer, blocked: Boolean) = Unit
    suspend fun loadProfile(peer: Peer): PeerProfile = PeerProfile(peer, peer.id.toString())
    suspend fun updateOwnProfile(name: String, handle: String, about: String) = Unit
    suspend fun checkUsernameAvailable(nick: String): Boolean = true
    suspend fun uploadOwnAvatar(payload: UploadPayload) = Unit
    suspend fun uploadGroupAvatar(group: Peer, payload: UploadPayload) = Unit
    suspend fun updateChannelHandle(group: Peer, handle: String) = Unit
    suspend fun createCommunity(title: String, isChannel: Boolean, handle: String = "", members: List<Peer> = emptyList()): Peer = Peer(1, 2, 0)
    suspend fun joinInviteOrHandle(input: String): Peer? = null
    suspend fun leaveCommunity(peer: Peer) = Unit
    suspend fun editCommunity(peer: Peer, title: String, about: String) = Unit
    suspend fun inviteMember(group: Peer, user: Peer) = Unit
    suspend fun kickMember(group: Peer, user: Peer) = Unit
    suspend fun setMemberAdmin(group: Peer, user: Peer, admin: Boolean, title: String = "") = Unit
    suspend fun revokeInviteLink(group: Peer): String = ""
    suspend fun reportPeerOrMessage(peer: Peer, message: ChatMessage?, reason: String) = Unit
    suspend fun loadStories(): List<StoryItem> = emptyList()
    suspend fun postTextStory(text: String) = Unit
    suspend fun reactStory(storyId: String, emoji: String) = Unit
    suspend fun deleteStory(storyId: String) = Unit
    suspend fun loadSecurityAndPrivacy(): SecurityAndPrivacyState = SecurityAndPrivacyState()
    suspend fun updatePreferences(transform: (ClientPreferences) -> ClientPreferences) = Unit
}

private val EMPTY_ONLINE = MutableStateFlow(emptySet<Int>())
private val DEFAULT_PREFERENCES = MutableStateFlow(ClientPreferences())
private val EMPTY_AVATAR_PATHS = MutableStateFlow(emptyMap<Long, String>())
private val EMPTY_FOLDERS = MutableStateFlow(emptyList<ChatFolder>())
private val EMPTY_PRESENCE = MutableStateFlow(emptyMap<Int, PeerPresence>())
private val EMPTY_GROUP_ONLINE = MutableStateFlow(emptyMap<Int, Int>())
private val NEVER_PUSHED = MutableStateFlow(0L)

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
