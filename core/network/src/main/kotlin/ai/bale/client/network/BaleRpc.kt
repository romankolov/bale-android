package ai.bale.client.network

import ai.bale.client.domain.Connection
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class SessionUpdate(val sessionId: Long, val payload: ByteArray)

/** Authenticated RPC seam for deterministic protocol/repository tests. */
interface BaleRpc {
    val state: StateFlow<Connection>
    val sessionId: Long
    val updates: SharedFlow<SessionUpdate>
    /** Count of update frames the socket itself received (0 = the server has never pushed to this session). */
    val pushFrames: Long get() = 0L
    suspend fun rpc(service: String, method: String, request: MessageLite, expectedSession: Long = sessionId): ByteArray
}
