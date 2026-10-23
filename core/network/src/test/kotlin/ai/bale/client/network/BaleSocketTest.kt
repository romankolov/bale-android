package ai.bale.client.network

import ai.bale.client.domain.Connection
import ai.bale.client.protocol.Auth_GetJWTToken_Request
import ai.bale.client.protocol.transport.*
import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString as OkioBytes
import org.junit.Assert.*
import org.junit.Test

class BaleSocketTest {
    @Test fun `handshake then out of order RPC correlation`() = runBlocking {
        val server = MockWebServer()
        val requests = mutableListOf<RpcRequest>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: OkioBytes) {
                val frame = SocketRequest.parseFrom(bytes.toByteArray())
                if (frame.hasHandshakeRequest()) {
                    val response = SocketResponse.newBuilder().setHandshakeResponse(frame.handshakeRequest).build()
                    webSocket.send(OkioBytes.of(*response.toByteArray()))
                } else if (frame.hasRequest()) {
                    requests += frame.request
                    if (requests.size == 2) requests.asReversed().forEach {
                        val response = SocketResponse.newBuilder().setResponse(RpcResponse.newBuilder()
                            .setNumber(it.requestId).setResult(ByteString.copyFromUtf8(it.method))).build()
                        webSocket.send(OkioBytes.of(*response.toByteArray()))
                    }
                }
            }
        }))
        server.start()
        val client = OkHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val socket = BaleSocket(client, scope, server.url("/ws/").toString())
        try {
            socket.start("test-token-not-real")
            withTimeout(5_000) { socket.state.first { it == Connection.Connected } }
            val first = async { socket.rpc("test.Service", "First", Auth_GetJWTToken_Request.getDefaultInstance()) }
            val second = async { socket.rpc("test.Service", "Second", Auth_GetJWTToken_Request.getDefaultInstance()) }
            assertEquals("First", withTimeout(5_000) { first.await() }.toString(Charsets.UTF_8))
            assertEquals("Second", withTimeout(5_000) { second.await() }.toString(Charsets.UTF_8))
            val upgrade = server.takeRequest()
            assertEquals("access_token=test-token-not-real", upgrade.getHeader("Cookie"))
            assertEquals(BaleEndpoint.ORIGIN, upgrade.getHeader("Origin"))
        } finally {
            socket.stop(); scope.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown()
        }
    }
    @Test fun `empty terminate envelope still has presence`() {
        val bytes = byteArrayOf(26, 0)
        assertTrue(SocketResponse.parseFrom(bytes).hasTerminateSession())
    }
}
