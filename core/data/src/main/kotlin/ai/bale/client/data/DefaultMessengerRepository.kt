package ai.bale.client.data

import ai.bale.client.domain.*
import ai.bale.client.network.BaleSocket
import ai.bale.client.protocol.*
import com.google.protobuf.MessageLite
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultMessengerRepository(
    private val auth: AuthRepository,
    private val socket: BaleSocket,
    database: BaleDatabase,
    scope: CoroutineScope,
    private val gate: AccountGate,
) : MessengerRepository {
    private val dao = database.messenger()
    private val refreshLock = Mutex()
    private val sendLock = Mutex()
    private val random = SecureRandom()
    override val connection = socket.state
    init {
        scope.launch {
            connection.collectLatest {
                if (it == Connection.Connected && auth.account.value != null) {
                    try { refreshConversations() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* UI exposes manual refresh */ }
                }
            }
        }
        scope.launch {
            socket.updates.collect { bytes ->
                try {
                    val update = Update.parseFrom(bytes)
                    val account = auth.account.value ?: return@collect
                    if (update.hasMessage()) {
                        val message = update.message
                        gate.mutex.withLock {
                            if (auth.account.value == account) {
                                dao.messages(listOf(MessageEntity(account.id, "${message.peer.type}:${message.peer.id}", message.rid,
                                    message.senderUid, content(message.message), message.date, Delivery.Sent.name, message.message.hasTextMessage())))
                            }
                        }
                    }
                    if (update.hasMessageSent()) {
                        val sent = update.messageSent
                        dao.delivery(account.id, "${sent.peer.type}:${sent.peer.id}", sent.rid, Delivery.Sent.name, sent.date)
                    }
                    // Other update variants currently invalidate dialogs; targeted reducers are tracked in coverage.
                    refreshConversations()
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* reconnect/manual sync repairs */ }
            }
        }
    }
    override fun conversations(): Flow<List<Conversation>> = auth.account.flatMapLatest { account ->
        if (account == null) flowOf(emptyList()) else dao.conversations(account.id).map { it.map(ConversationEntity::domain) }
    }
    override fun messages(peer: ai.bale.client.domain.Peer): Flow<List<ChatMessage>> = auth.account.flatMapLatest { account ->
        if (account == null) flowOf(emptyList()) else dao.messages(account.id, peer.key).map { it.map(MessageEntity::domain) }
    }
    override suspend fun refreshConversations() = refreshLock.withLock {
        val account = auth.account.value ?: return@withLock
        val response = Messaging_LoadDialogs_Response.parseFrom(call("LoadDialogs", Messaging_LoadDialogs_Request.newBuilder().setMinDate(-1).setLimit(100).build()))
        if (auth.account.value != account) return@withLock
        val users = response.usersList.associateBy { it.id }
        val groups = response.groupsList.associateBy { it.id }
        val userHashes = response.userPeersList.associate { it.uid to it.accessHash }
        val groupHashes = response.groupPeersList.associate { it.groupId to it.accessHash }
        val entities = response.dialogsList.filter { it.peer.type in 1..2 }.map {
            val p = it.peer
            val hash = if (p.type == 1) userHashes[p.id] ?: users[p.id]?.accessHash else groupHashes[p.id] ?: groups[p.id]?.accessHash
            val name = if (p.type == 1) users[p.id]?.name else groups[p.id]?.title
            ConversationEntity(account.id, "${p.type}:${p.id}", p.id, p.type, hash ?: 0, name ?: p.id.toString(),
                content(it.message), it.sortDate.takeIf { date -> date != 0L } ?: it.date, it.unreadCount)
        }
        gate.mutex.withLock { if (auth.account.value == account) dao.conversations(entities) }
    }
    override suspend fun loadHistory(peer: ai.bale.client.domain.Peer, before: Long): Int {
        val account = auth.account.value ?: return 0
        val response = Messaging_LoadHistory_Response.parseFrom(call("LoadHistory", Messaging_LoadHistory_Request.newBuilder()
            .setPeer(peer.wire()).setDate(before).setLoadMode(2).setLimit(50).build()))
        if (auth.account.value != account) return 0
        gate.mutex.withLock {
            if (auth.account.value == account) dao.messages(response.historyList.map {
                MessageEntity(account.id, peer.key, it.rid, it.senderUid, content(it.message), it.date, Delivery.Sent.name, it.message.hasTextMessage())
            })
        }
        return response.historyCount
    }
    override suspend fun queue(peer: ai.bale.client.domain.Peer, text: String): Long = gate.mutex.withLock {
        require(text.isNotBlank() && text.length <= 4096)
        val account = auth.account.value ?: error("No active account")
        // Store before the first attempt; reuse rid for every explicit retry.
        val entity = MessageEntity(account.id, peer.key, random.nextLong().ushr(2).coerceAtLeast(1), account.id,
            text.trim(), System.currentTimeMillis(), Delivery.Pending.name, true)
        dao.messages(listOf(entity))
        entity.rid
    }
    override suspend fun retry(peer: ai.bale.client.domain.Peer, rid: Long) {
        val account = auth.account.value ?: return
        val message = dao.message(account.id, peer.key, rid) ?: return
        if (message.delivery == Delivery.Sent.name) return
        deliver(peer, message)
    }
    private suspend fun deliver(peer: ai.bale.client.domain.Peer, message: MessageEntity) = sendLock.withLock {
        val latest = dao.message(message.accountId, peer.key, message.rid) ?: return@withLock
        if (latest.delivery == Delivery.Sent.name || auth.account.value?.id != message.accountId) return@withLock
        dao.delivery(message.accountId, peer.key, message.rid, Delivery.Pending.name, message.date)
        try {
            val response = Msg_index_fe3fa18088__83329_h.parseFrom(call("SendMessage", Messaging_SendMessage_Request.newBuilder()
                .setPeer(peer.wire()).setRid(message.rid).setMessage(Message2.newBuilder().setTextMessage(TextMessage.newBuilder().setText(message.text))).build()))
            if (auth.account.value?.id == message.accountId) {
                dao.delivery(message.accountId, peer.key, message.rid, Delivery.Sent.name, response.date)
            }
        } catch (error: Exception) {
            // Cancellation/process restart must never silently report success or auto-resend.
            withContext(NonCancellable) {
                if (auth.account.value?.id == message.accountId) {
                    val current = dao.message(message.accountId, peer.key, message.rid)
                    if (current?.delivery != Delivery.Sent.name) dao.delivery(message.accountId, peer.key, message.rid, Delivery.Failed.name, message.date)
                }
            }
            throw error
        }
    }
    override suspend fun markRead(peer: ai.bale.client.domain.Peer, date: Long) {
        val account = auth.account.value ?: return
        call("MessageRead", Messaging_MessageRead_Request.newBuilder().setPeer(peer.wire()).setDate(date).build())
        if (auth.account.value == account) dao.read(account.id, peer.key)
    }
    private suspend fun call(method: String, request: MessageLite) = socket.rpc("bale.messaging.v2.Messaging", method, request)
    private fun ai.bale.client.domain.Peer.wire(): Peer2 = Peer2.newBuilder().setId(id).setType(kind).setAccessHash(accessHash).build()
    private fun content(message: Message2): String = if (message.hasTextMessage()) message.textMessage.text else ""
}
