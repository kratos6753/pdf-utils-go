package site.chirudev.pdfutils.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangesTest {
    @Test fun `accepts the same selections as the server`() {
        listOf("1", "1-3", "1-3,5", " 1 - 3 , 5 , 8-10 ", "12,4").forEach { assertTrue(it, PageRanges.isValid(it)) }
    }

    @Test fun `rejects malformed selections`() {
        listOf("", " ", "a", "1-", "-3", "1,,2", "1-2-3", "1;2", "1.5").forEach { assertFalse(it, PageRanges.isValid(it)) }
    }

    @Test fun `expands ranges in order without duplicates and clamps to page count`() {
        assertEquals(listOf(1, 2, 3, 5), PageRanges.expand("1-3,5,2"))
        assertEquals(listOf(3, 4), PageRanges.expand("3-6", pageCount = 4))
        assertEquals(listOf(2, 3, 4), PageRanges.expand("4-2"))
        assertEquals(emptyList<Int>(), PageRanges.expand("x"))
    }

    @Test fun `compacts page numbers back into ranges`() {
        assertEquals("1-3,5,7-8", PageRanges.compact(listOf(8, 1, 2, 3, 5, 7)))
        assertEquals("4", PageRanges.compact(setOf(4)))
        assertEquals("", PageRanges.compact(emptyList()))
    }
}
