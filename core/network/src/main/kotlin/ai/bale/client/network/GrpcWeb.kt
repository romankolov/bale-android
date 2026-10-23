package ai.bale.client.network

import ai.bale.client.domain.ProtocolException
import ai.bale.client.domain.RpcException
import com.google.protobuf.MessageLite
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import java.net.URLDecoder
import java.nio.ByteBuffer

object BaleEndpoint {
    const val HTTP = "https://next-ws.bale.ai/"
    const val SOCKET = "wss://next-ws.bale.ai/ws/"
    const val ORIGIN = "https://web.bale.ai"
    const val VERSION = "176652"
    // Public web-client identifier from the supplied archive, not a user secret.
    const val APP_KEY = "C28D46DC4C3A7A26564BFCC48B929086A95C93C98E789A19847BEE8627DE4E7D"
}
interface AuthHttpApi {
    @POST("bale.auth.v1.Auth/{method}")
    suspend fun call(@Path("method") method: String, @Body body: RequestBody): Response<ResponseBody>
}

/** Explicit framing, bounded parsing, and trailer errors even on HTTP 200. */
object GrpcWeb {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_BYTES)
        return ByteBuffer.allocate(payload.size + 5).put(0).putInt(payload.size).put(payload).array()
    }
    fun decode(bytes: ByteArray, headers: Map<String, String> = emptyMap()): ByteArray {
        if (bytes.size > MAX_BYTES) throw ProtocolException("Oversized gRPC-web response")
        val metadata = headers.mapKeys { it.key.lowercase() }.toMutableMap()
        val buffer = ByteBuffer.wrap(bytes)
        var payload: ByteArray? = null
        var trailersSeen = false
        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 5) throw ProtocolException("Truncated gRPC-web header")
            val flag = buffer.get().toInt() and 0xff
            val length = buffer.int
            if (length < 0 || length > buffer.remaining()) throw ProtocolException("Invalid gRPC-web frame length")
            val frame = ByteArray(length).also { buffer.get(it) }
            when (flag) {
                0 -> {
                    if (payload != null || trailersSeen) throw ProtocolException("Unexpected unary data frame")
                    payload = frame
                }
                128 -> {
                    if (trailersSeen) throw ProtocolException("Duplicate trailers")
                    trailersSeen = true
                    frame.toString(Charsets.US_ASCII).split("\r\n").filter { it.isNotBlank() }.forEach {
                        val parts = it.split(':', limit = 2)
                        if (parts.size != 2) throw ProtocolException("Malformed gRPC-web trailer")
                        metadata[parts[0].trim().lowercase()] = parts[1].trim()
                    }
                }
                else -> throw ProtocolException("Compressed/unknown gRPC-web flag")
            }
        }
        val status = metadata["grpc-status"]
        val message = metadata["grpc-message"].orEmpty()
        if ((status != null && status != "0") || (message.isNotEmpty() && status != "0")) {
            val decoded = try { URLDecoder.decode(message.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { "RPC error" }
            throw RpcException(status?.toLongOrNull() ?: -1, decoded)
        }
        return payload ?: throw ProtocolException("Missing unary response")
    }
}
class AuthTransport(private val api: AuthHttpApi) {
    suspend fun call(method: String, message: MessageLite): ByteArray {
        val body = GrpcWeb.encode(message.toByteArray()).toRequestBody("application/grpc-web+proto".toMediaType())
        val response = api.call(method, body)
        val responseBody = if (response.isSuccessful) response.body() else response.errorBody()
        return responseBody?.use {
            if (!response.isSuccessful) throw ProtocolException("HTTP ${response.code()}")
            val source = it.source()
            if (source.request(GrpcWeb.MAX_BYTES.toLong() + 1)) throw ProtocolException("Oversized HTTP response")
            GrpcWeb.decode(source.readByteArray(), response.headers().toMap())
        } ?: throw ProtocolException("Empty HTTP response")
    }
}
