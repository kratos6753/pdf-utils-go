package site.chirudev.pdfutils.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ApiErrorTest {
    @Test fun `parses the error envelope with details`() {
        val body = """{"error":{"code":"limit_reached","message":"Hourly task limit reached","details":{"reset_at":"2026-10-25T10:00:00Z","limit":10,"signed_in":false}}}"""
        val e = ApiError.parse(429, body)
        assertEquals("limit_reached", e.code)
        assertEquals("Hourly task limit reached", e.message)
        assertEquals("2026-10-25T10:00:00Z", e.detailString("reset_at"))
        assertEquals(10, e.detailInt("limit"))
        assertEquals(false, e.detailBool("signed_in"))
        assertTrue(e.isLimit)
    }

    @Test fun `file_too_large is a limit error even without 429`() {
        val e = ApiError.parse(413, """{"error":{"code":"file_too_large","message":"too big","details":{"max_file_mb":50,"can_upgrade":true}}}""")
        assertTrue(e.isLimit)
        assertEquals(50, e.detailInt("max_file_mb"))
    }

    @Test fun `falls back gracefully on non-JSON bodies`() {
        val e = ApiError.parse(502, "<html>bad gateway</html>")
        assertEquals("error", e.code)
        assertTrue(e.message.contains("server"))
        assertNull(e.details)
        assertFalse(e.isLimit)
    }

    @Test fun `maps IO failures to a network error`() {
        val e = ApiError.from(IOException("timeout"))
        assertEquals(ApiError.NETWORK, e.code)
        assertEquals(0, e.status)
    }
}
