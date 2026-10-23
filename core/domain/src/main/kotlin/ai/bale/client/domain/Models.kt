package ai.bale.client.domain

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Wire peer IDs are signed int32; request/message identifiers are int64. */
data class Peer(val id: Int, val kind: Int, val accessHash: Long) {
    init { require(kind == 1 || kind == 2) }
    val key: String get() = "$kind:$id"
}
data class Account(val id: Int, val name: String)
data class Conversation(val peer: Peer, val title: String, val preview: String, val date: Long, val unread: Int)
enum class Delivery { Pending, Sent, Failed }
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
) {
    val reference: MessageReference get() = MessageReference(rid, date, sequence)
}
data class MessageReference(val rid: Long, val date: Long, val sequence: Long = 0)
data class QuotePreview(val rid: Long, val senderId: Int, val text: String)
data class Reaction(val code: String, val count: Long)
data class SearchHit(val peer: Peer, val title: String, val message: ChatMessage)
data class SearchPage(val hits: List<SearchHit>, val total: Int)
enum class Connection { Disconnected, Connecting, Connected, Reconnecting, Unauthorized }
enum class AuthStep { Phone, Code, Password, Registration }
data class AuthChallenge(val transaction: String, val step: AuthStep)

interface AuthRepository {
    val account: StateFlow<Account?>
    suspend fun restore()
    suspend fun start(phone: String): AuthChallenge
    suspend fun verify(challenge: AuthChallenge, value: String): AuthChallenge?
    suspend fun signOut()
}
interface MessengerRepository {
    val connection: StateFlow<Connection>
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
}

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
