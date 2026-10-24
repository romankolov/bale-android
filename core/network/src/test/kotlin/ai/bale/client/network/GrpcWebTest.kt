package ai.bale.client.network

import ai.bale.client.domain.ProtocolException
import ai.bale.client.domain.RpcException
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class GrpcWebTest {
    private fun trailer(text: String): ByteArray = text.toByteArray().let { ByteBuffer.allocate(5 + it.size).put(0x80.toByte()).putInt(it.size).put(it).array() }
    @Test fun `unary roundtrip including empty protobuf`() {
        listOf(byteArrayOf(), byteArrayOf(8, 42), "سلام".toByteArray()).forEach {
            assertArrayEquals(it, GrpcWeb.decode(GrpcWeb.encode(it) + trailer("grpc-status: 0\r\n")))
        }
    }
    @Test fun `wire prefix is flag then big endian length`() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 2, 8, 42), GrpcWeb.encode(byteArrayOf(8, 42)))
    }
    @Test fun `trailer error wins over successful HTTP payload`() {
        val error = assertThrows(RpcException::class.java) {
            GrpcWeb.decode(GrpcWeb.encode(byteArrayOf()) + trailer("grpc-status: 7\r\ngrpc-message: PHONE_CODE_INVALID\r\n"))
        }
        assertEquals(7L, error.code)
        assertEquals("PHONE_CODE_INVALID", error.reason)
    }
    @Test fun `headers can contain Bale errors with no payload`() {
        val error = assertThrows(RpcException::class.java) { GrpcWeb.decode(byteArrayOf(), mapOf("Grpc-Message" to "password%20needed+for%20login")) }
        assertEquals("password needed+for login", error.reason)
    }
    @Test fun `rejects truncated header`() { assertThrows(ProtocolException::class.java) { GrpcWeb.decode(byteArrayOf(0, 0)) } }
    @Test fun `rejects truncated payload`() { assertThrows(ProtocolException::class.java) { GrpcWeb.decode(byteArrayOf(0, 0, 0, 0, 2, 8)) } }
    @Test fun `rejects negative length`() { assertThrows(ProtocolException::class.java) { GrpcWeb.decode(byteArrayOf(0, -1, -1, -1, -1)) } }
    @Test fun `rejects compression`() { assertThrows(ProtocolException::class.java) { GrpcWeb.decode(byteArrayOf(1, 0, 0, 0, 0)) } }
    @Test fun `rejects duplicate unary data`() { assertThrows(ProtocolException::class.java) { GrpcWeb.decode(GrpcWeb.encode(byteArrayOf()) + GrpcWeb.encode(byteArrayOf())) } }
    @Test fun `rejects data after trailers`() { assertThrows(ProtocolException::class.java) { GrpcWeb.decode(trailer("grpc-status: 0\r\n") + GrpcWeb.encode(byteArrayOf())) } }
    @Test fun `rejects malformed trailer`() { assertThrows(ProtocolException::class.java) { GrpcWeb.decode(trailer("no-colon")) } }
    @Test fun `rejects oversized responses`() { assertThrows(ProtocolException::class.java) { GrpcWeb.decode(ByteArray(GrpcWeb.MAX_BYTES + 1)) } }
    @Test fun `requires data on success`() { assertThrows(ProtocolException::class.java) { GrpcWeb.decode(trailer("grpc-status: 0\r\n")) } }
}
