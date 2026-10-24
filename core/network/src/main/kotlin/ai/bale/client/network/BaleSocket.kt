package ai.bale.client.network

import ai.bale.client.domain.Connection
import ai.bale.client.domain.ProtocolException
import ai.bale.client.domain.RpcException
import ai.bale.client.protocol.transport.*
import ai.bale.client.protocol.transport.Handshake
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okio.ByteString as OkioBytes
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** One account/session owner. Never replays RPCs automatically after disconnect. */
class BaleSocket(private val client: OkHttpClient, private val scope: CoroutineScope, private val url: String = BaleEndpoint.SOCKET) : BaleRpc {
    private val mutableState = MutableStateFlow(Connection.Disconnected)
    override val state: StateFlow<Connection> = mutableState.asStateFlow()
    // Non-suspending fan-out: a busy update reducer (it serialises behind account-wide work) must
    // never block the reader or the socket gets torn by frame overflow. Stale frames may drop; the
    // repository's staleness refresh re-synchronises state, while a torn connection would delay everything.
    private val mutableUpdates = MutableSharedFlow<SessionUpdate>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val updates: SharedFlow<SessionUpdate> = mutableUpdates.asSharedFlow()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<ByteArray>>()
    private val counter = AtomicLong(0)
    private val lifecycle = Mutex()
    private val sessionEpoch = AtomicLong(0)
    override val sessionId: Long get() = sessionEpoch.get()
    private var job: Job? = null
    @Volatile private var socket: WebSocket? = null
    private val metadata = Metadata.newBuilder().apply {
        mapOf("app_version" to BaleEndpoint.VERSION, "browser_type" to "1", "browser_version" to "138.0.0.0", "os_type" to "3").forEach { (name, value) ->
            addEntries(MetadataEntry.newBuilder().setName(name).setValue(MetadataValue.newBuilder().setText(value)))
        }
    }.build()

    suspend fun start(token: String) = lifecycle.withLock {
        val epoch = sessionEpoch.incrementAndGet()
        job?.cancelAndJoin()
        socket?.cancel()
        mutableState.value = Connection.Connecting
        job = scope.launch { reconnect(token, epoch) }
    }
    suspend fun stop() = lifecycle.withLock {
        sessionEpoch.incrementAndGet()
        job?.cancelAndJoin()
        job = null
        socket?.cancel()
        socket = null
        failPending(IOException("Session closed"))
        mutableState.value = Connection.Disconnected
    }
    override suspend fun rpc(service: String, method: String, request: MessageLite, expectedSession: Long): ByteArray {
        val epoch = expectedSession
        withTimeout(15_000) {
            state.first { it == Connection.Connected || it == Connection.Unauthorized }
        }
        if (state.value == Connection.Unauthorized) throw RpcException(4401, "Session expired")
        val id = counter.incrementAndGet()
        val result = CompletableDeferred<ByteArray>()
        try {
            val frame = SocketRequest.newBuilder().setRequest(
                RpcRequest.newBuilder().setService(service).setMethod(method)
                    .setPayload(ByteString.copyFrom(request.toByteArray())).setMetadata(metadata).setRequestId(id)
            ).build()
            lifecycle.withLock {
                if (epoch != sessionEpoch.get()) throw IOException("Session changed before request was sent")
                pending[id] = result
                if (socket?.send(OkioBytes.of(*frame.toByteArray())) != true) throw IOException("Socket unavailable")
            }
            return withTimeout(20_000) { result.await() }
        } finally { pending.remove(id); result.cancel() }
    }
    private suspend fun reconnect(token: String, epoch: Long) {
        var attempts = 0
        while (currentCoroutineContext().isActive) {
            try {
                runConnection(token, epoch) { attempts = 0 }
            } catch (_: TimeoutCancellationException) {
                // A connection/handshake deadline is recoverable, unlike owner cancellation.
                mutableState.value = Connection.Reconnecting
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expired: SessionExpired) {
                mutableState.value = Connection.Unauthorized
                return
            } catch (_: Exception) {
                mutableState.value = Connection.Reconnecting
            } finally {
                socket?.cancel()
                socket = null
                failPending(IOException("Connection lost; outcome may be unknown"))
            }
            val backoff = (100L shl attempts.coerceAtMost(5)).coerceAtMost(3_000)
            attempts++
            delay(backoff + Random.nextLong(0, 100))
        }
    }
    private suspend fun runConnection(token: String, epoch: Long, onReady: () -> Unit): Unit = coroutineScope {
        val frames = Channel<ByteArray>(512)
        val opened = CompletableDeferred<Unit>()
        val request = Request.Builder().url(url)
            .header("Origin", BaleEndpoint.ORIGIN).header("Cookie", "access_token=$token").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, bytes: OkioBytes) {
                if (bytes.size > GrpcWeb.MAX_BYTES || !frames.trySend(bytes.toByteArray()).isSuccess) {
                    frames.close(ProtocolException("Incoming frame limit exceeded")); webSocket.cancel()
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                frames.close(ProtocolException("Unexpected text frame")); webSocket.cancel()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = if (response?.code == 401) SessionExpired() else IOException("WebSocket failed", t)
                opened.completeExceptionally(error); frames.close(error)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                val error = if (code == 4401) SessionExpired() else IOException("WebSocket closed ($code)")
                opened.completeExceptionally(error); frames.close(error); webSocket.close(code, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                frames.close(if (code == 4401) SessionExpired() else IOException("WebSocket closed"))
            }
        })
        socket = ws
        try {
            withTimeout(10_000) { opened.await() }
            val hello = SocketRequest.newBuilder().setHandshakeRequest(
                Handshake.newBuilder().setMkprotoVersion(1).setApiVersion(BaleEndpoint.VERSION.toLong())
            ).build()
            check(ws.send(OkioBytes.of(*hello.toByteArray())))
            val handshake = withTimeout(10_000) { SocketResponse.parseFrom(frames.receive()) }
            if (handshake.hasTerminateSession()) throw SessionExpired()
            if (!handshake.hasHandshakeResponse() || handshake.handshakeResponse.mkprotoVersion != 1) {
                throw ProtocolException("Unsupported handshake")
            }
            mutableState.value = Connection.Connected
            onReady()
            var pong: CompletableDeferred<Long>? = null
            val heartbeat = launch {
                while (isActive) {
                    delay(10_000)
                    val id = counter.incrementAndGet()
                    val waiting = CompletableDeferred<Long>()
                    pong = waiting
                    val ping = SocketRequest.newBuilder().setPing(Heartbeat.newBuilder().setId(id)).build()
                    if (!ws.send(OkioBytes.of(*ping.toByteArray()))) throw IOException("Ping send failed")
                    val received = withTimeoutOrNull(5_000) { waiting.await() }
                        ?: throw IOException("Pong deadline exceeded")
                    if (received != id) throw ProtocolException("Invalid pong")
                }
            }
            try {
                for (bytes in frames) {
                    val frame = SocketResponse.parseFrom(bytes)
                    if (frame.hasTerminateSession()) throw SessionExpired()
                    when {
                        frame.hasResponse() -> {
                            val response = frame.response
                            pending.remove(response.number)?.let { result ->
                                if (response.hasError()) result.completeExceptionally(RpcException(response.error.topic, response.error.message))
                                else result.complete(response.result.toByteArray())
                            }
                        }
                        frame.hasPong() -> pong?.complete(frame.pong.id)
                        frame.hasUpdate() -> mutableUpdates.emit(SessionUpdate(epoch, frame.update.update.toByteArray()))
                    }
                }
                throw IOException("WebSocket ended")
            } finally { heartbeat.cancelAndJoin() }
        } finally { ws.cancel(); frames.cancel() }
    }
    private fun failPending(error: Exception) { pending.values.forEach { it.completeExceptionally(error) }; pending.clear() }
    private class SessionExpired : IOException()
}
