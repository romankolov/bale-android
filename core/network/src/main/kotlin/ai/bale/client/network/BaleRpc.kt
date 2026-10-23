package ai.bale.client.network

import ai.bale.client.domain.Connection
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Authenticated RPC seam for deterministic protocol/repository tests. */
interface BaleRpc {
    val state: StateFlow<Connection>
    val updates: SharedFlow<ByteArray>
    suspend fun rpc(service: String, method: String, request: MessageLite): ByteArray
}
