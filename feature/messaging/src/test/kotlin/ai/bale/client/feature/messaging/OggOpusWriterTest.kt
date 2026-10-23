package ai.bale.client.feature.messaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Opus/Ogg muxer runs on-device with no reference to compare against, so the container rules are pinned here. */
class OggOpusWriterTest {
    private fun intAt(page: ByteArray, offset: Int): Int =
        (page[offset].toInt() and 0xFF) or
            ((page[offset + 1].toInt() and 0xFF) shl 8) or
            ((page[offset + 2].toInt() and 0xFF) shl 16) or
            ((page[offset + 3].toInt() and 0xFF) shl 24)

    private fun longAt(page: ByteArray, offset: Int): Long {
        var value = 0L
        for (shift in 0 until 8) value = value or ((page[offset + shift].toLong() and 0xFF) shl (8 * shift))
        return value
    }

    @Test fun `crc of a completed page decodes to zero`() {
        val page = OggOpusWriter.page(1, 0, 960L, listOf(ByteArray(80)))
        assertEquals(0, OggOpusWriter.crc(page))
        val big = OggOpusWriter.page(7, 12, 48_000L, List(30) { ByteArray(127) { b -> (it + b).toByte() } })
        assertEquals(0, OggOpusWriter.crc(big))
    }

    @Test fun `page framing carries capture pattern serial sequence and granule`() {
        val page = OggOpusWriter.page(0x11223344, 5, 20_000L, listOf(byteArrayOf(9, 8, 7)))
        assertArrayEquals("OggS".toByteArray(), page.copyOfRange(0, 4))
        assertEquals(0, page[4].toInt())
        assertEquals(0, page[5].toInt())
        assertEquals(20_000L, longAt(page, 6))
        assertEquals(0x11223344, intAt(page, 14))
        assertEquals(5, intAt(page, 18))
        assertEquals(1, page[26].toInt())
        assertEquals(3, page[27].toInt())
        assertArrayEquals(byteArrayOf(9, 8, 7), page.copyOfRange(28, 31))
    }

    @Test fun `packets of exact multiple segment size terminate with a zero lacing value`() {
        val page = OggOpusWriter.page(1, 0, 0L, listOf(ByteArray(255), ByteArray(3)))
        assertEquals(3, page[26].toInt())
        assertEquals(-1, page[27].toInt()) // 255
        assertEquals(0, page[28].toInt()) // terminator of the first packet
        assertEquals(3, page[29].toInt())
        assertEquals(0, OggOpusWriter.crc(page))
    }

    @Test fun `header pages set bos once and embed a valid opus head`() {
        val headers = OggOpusWriter.headerPages(serial = 42)
        // First page: 27-byte header + 1 lacing byte + the 19-byte OpusHead packet.
        assertEquals(0, OggOpusWriter.crc(headers.copyOfRange(0, 27 + 1 + 19)))
        assertTrue(headers[5].toInt() and 0x02 != 0)
        assertArrayEquals("OpusHead".toByteArray(), headers.copyOfRange(28, 36))
        assertEquals(1, headers[36].toInt()) // version
        assertEquals(1, headers[37].toInt()) // channels
        assertEquals(312, intAt(headers, 38) and 0xFFFF) // pre-skip
        assertEquals(48_000, intAt(headers, 40))
        val secondPageStart = 27 + 1 + 19
        assertEquals(0, headers[secondPageStart + 5].toInt() and 0x02)
        assertArrayEquals("OpusTags".toByteArray(), headers.copyOfRange(secondPageStart + 28, secondPageStart + 36))
    }

    @Test fun `end-of-stream flag marks the final page`() {
        val page = OggOpusWriter.page(1, 9, 960L, emptyList(), endOfStream = true)
        assertEquals(0x04, page[5].toInt())
        assertEquals(27, page.size)
        assertEquals(0, OggOpusWriter.crc(page))
    }
}
