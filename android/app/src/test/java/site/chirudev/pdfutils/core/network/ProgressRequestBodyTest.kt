package site.chirudev.pdfutils.core.network

import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressRequestBodyTest {
    @Test fun `streams every byte and reports monotonic progress up to 1`() {
        val data = ByteArray(1_000_000) { (it % 7).toByte() }
        val seen = mutableListOf<Float>()
        val body = ProgressRequestBody(null, data.size.toLong(), { data.inputStream() }, { seen += it })

        val sink = Buffer()
        body.writeTo(sink)

        assertArrayEquals(data, sink.readByteArray())
        assertEquals(1f, seen.last(), 0f)
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("progress is throttled to whole percents", seen.size <= 101)
    }

    @Test fun `can be written twice for an authenticated retry`() {
        val data = "hello".toByteArray()
        val body = ProgressRequestBody(null, data.size.toLong(), { data.inputStream() }, {})
        repeat(2) {
            val sink = Buffer()
            body.writeTo(sink)
            assertEquals("hello", sink.readUtf8())
        }
    }
}
