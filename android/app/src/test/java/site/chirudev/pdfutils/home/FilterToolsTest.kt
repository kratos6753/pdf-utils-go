package site.chirudev.pdfutils.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import site.chirudev.pdfutils.tools.Category
import site.chirudev.pdfutils.tools.tools

class FilterToolsTest {
    @Test fun `empty query and no category shows everything`() = assertEquals(tools, filterTools(tools, "  ", null))

    @Test fun `search matches title and description case-insensitively`() {
        assertEquals(listOf("compress-pdf"), filterTools(tools, "COMPRESS", null).map { it.slug })
        assertTrue(filterTools(tools, "password", null).any { it.slug == "protect-pdf" })
    }

    @Test fun `category narrows results`() {
        val security = filterTools(tools, "", Category.Security)
        assertEquals(setOf("protect-pdf", "unlock-pdf"), security.map { it.slug }.toSet())
        assertEquals(emptyList<String>(), filterTools(tools, "merge", Category.Security).map { it.slug })
    }
}
