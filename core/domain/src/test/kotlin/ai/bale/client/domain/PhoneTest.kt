package ai.bale.client.domain

import org.junit.Assert.*
import org.junit.Test

class PhoneTest {
    @Test fun `normalizes international formatting`() { assertEquals("989123456789", normalizePhone("+98 (912) 345-6789")) }
    @Test fun `accepts Persian digits`() { assertEquals("989123456789", normalizePhone("+۹۸۹۱۲۳۴۵۶۷۸۹")) }
    @Test fun `accepts Arabic digits`() { assertEquals("989123456789", normalizePhone("+٩٨٩١٢٣٤٥٦٧٨٩")) }
    @Test fun `rejects local number`() { assertThrows(IllegalArgumentException::class.java) { normalizePhone("09123456789") } }
    @Test fun `rejects embedded plus and letters`() {
        listOf("98+9123456789", "hello989123456789", "", "123", "1234567890123456").forEach {
            assertThrows(IllegalArgumentException::class.java) { normalizePhone(it) }
        }
    }
}
