package site.chirudev.pdfutils.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the Kotlin registry produces exactly the options the web app sends. */
class ToolRegistryTest {
    private fun tool(slug: String) = toolBySlug(slug)!!

    @Test fun `registry matches every backend tool`() {
        assertEquals(
            setOf("merge", "split", "compress", "image_to_pdf", "pdf_to_image", "rotate", "organize", "remove_pages", "watermark", "page_numbers", "protect", "unlock"),
            tools.map { it.api }.toSet(),
        )
        assertEquals(tools.size, tools.map { it.slug }.toSet().size)
    }

    @Test fun `merge sends empty options`() {
        assertEquals(buildJsonObject { }, tool("merge-pdf").toOptions(emptyMap()))
    }

    @Test fun `split by ranges splits on commas and trims`() {
        val t = tool("split-pdf")
        val opts = t.toOptions(t.defaultValues() + ("ranges" to " 1-3, 4-6 ,,7 "))
        assertEquals(buildJsonObject { put("mode", "ranges"); putJsonArray("ranges") { add(JsonPrimitive("1-3")); add(JsonPrimitive("4-6")); add(JsonPrimitive("7")) } }, opts)
    }

    @Test fun `split every sends an integer`() {
        val t = tool("split-pdf")
        val opts = t.toOptions(t.defaultValues() + mapOf("mode" to "every", "every" to 3.0))
        assertEquals(buildJsonObject { put("mode", "every"); put("every", 3) }, opts)
    }

    @Test fun `split extract sends pages and merge flag`() {
        val t = tool("split-pdf")
        val opts = t.toOptions(t.defaultValues() + mapOf("mode" to "extract", "pages" to "1-3,5", "merge" to false))
        assertEquals(buildJsonObject { put("mode", "extract"); put("pages", "1-3,5"); put("merge", false) }, opts)
    }

    @Test fun `pdf to image converts dpi to a number and omits empty pages`() {
        val t = tool("pdf-to-jpg")
        assertEquals(buildJsonObject { put("format", "jpg"); put("dpi", 150) }, t.toOptions(t.defaultValues()))
        assertEquals(
            buildJsonObject { put("format", "png"); put("dpi", 300); put("pages", "2-4") },
            t.toOptions(t.defaultValues() + mapOf("format" to "png", "dpi" to "300", "pages" to "2-4")),
        )
    }

    @Test fun `rotate sends angle as integer`() {
        val t = tool("rotate-pdf")
        assertEquals(buildJsonObject { put("angle", 270) }, t.toOptions(t.defaultValues() + ("angle" to "270")))
    }

    @Test fun `watermark passes values through with whole numbers as integers`() {
        val t = tool("add-watermark")
        val opts = t.toOptions(t.defaultValues() + ("text" to "DRAFT"))
        assertEquals(
            buildJsonObject {
                put("text", "DRAFT"); put("font_size", 48); put("opacity", 0.3); put("rotation", 45); put("color", "#808080"); put("position", "c")
            },
            opts,
        )
    }

    @Test fun `page numbers omits empty pages`() {
        val t = tool("add-page-numbers")
        val opts = t.toOptions(t.defaultValues())
        assertEquals(buildJsonObject { put("position", "bc"); put("format", "n"); put("font_size", 11) }, opts)
    }

    @Test fun `image to pdf always sends orientation and margin like the web`() {
        val t = tool("jpg-to-pdf")
        assertEquals(buildJsonObject { put("page_size", "fit"); put("orientation", "portrait"); put("margin", "none") }, t.toOptions(t.defaultValues()))
    }

    @Test fun `organize sends the page order`() {
        val opts = tool("organize-pdf").toOptions(emptyMap(), EditorState(pageOrder = listOf(3, 1, 2)))
        assertEquals(JsonArray(listOf(JsonPrimitive(3), JsonPrimitive(1), JsonPrimitive(2))), opts["order"])
    }

    @Test fun `remove pages sends a compact selection`() {
        val opts = tool("remove-pages").toOptions(emptyMap(), EditorState(removed = setOf(5, 2, 3)))
        assertEquals(buildJsonObject { put("pages", "2-3,5") }, opts)
    }

    @Test fun `required fields and page syntax are validated`() {
        val split = tool("split-pdf")
        assertEquals("Ranges is required", split.validate(split.defaultValues()))
        assertEquals("Ranges: use e.g. 1-3, 4-6", split.validate(split.defaultValues() + ("ranges" to "1-3, x")))
        assertNull(split.validate(split.defaultValues() + ("ranges" to "1-3, 4")))

        val rotate = tool("rotate-pdf")
        assertNull(rotate.validate(rotate.defaultValues()))
        assertTrue(rotate.validate(rotate.defaultValues() + ("pages" to "1-")).orEmpty().startsWith("Pages (optional)"))

        val wm = tool("add-watermark")
        assertEquals("Watermark text is required", wm.validate(wm.defaultValues()))
    }

    @Test fun `hidden fields are not validated`() {
        val split = tool("split-pdf")
        assertNull(split.validate(split.defaultValues() + mapOf("mode" to "every")))
    }

    @Test fun `protect requires matching passwords`() {
        val t = tool("protect-pdf")
        assertEquals("Password is required", t.validate(t.defaultValues()))
        assertEquals("Passwords do not match", t.validate(mapOf("password" to "secret1"), mapOf("password" to "secret2")))
        assertNull(t.validate(mapOf("password" to "secret1"), mapOf("password" to "secret1")))
    }

    @Test fun `unlock password is optional`() {
        val t = tool("unlock-pdf")
        assertNull(t.validate(t.defaultValues()))
        assertEquals(buildJsonObject { put("password", "") }, t.toOptions(t.defaultValues()))
    }

    @Test fun `page editors validate their selection`() {
        val organize = tool("organize-pdf")
        assertEquals("Keep at least one page", organize.validate(emptyMap(), editor = EditorState(pageOrder = emptyList())))
        val remove = tool("remove-pages")
        assertEquals("Select at least one page to remove", remove.validate(emptyMap(), editor = EditorState(removed = emptySet(), pageCount = 3)))
        assertEquals("You cannot remove every page", remove.validate(emptyMap(), editor = EditorState(removed = setOf(1, 2, 3), pageCount = 3)))
        assertNull(remove.validate(emptyMap(), editor = EditorState(removed = setOf(1), pageCount = 3)))
    }

    @Test fun `continue-to suggests compatible tools only`() {
        val next = nextTools(tool("merge-pdf"), "application/pdf")
        assertFalse(next.any { it.slug == "merge-pdf" })
        assertTrue(next.size <= 6)
        assertTrue(next.all { it.accepts == Accepts.Pdf })
        assertEquals(emptyList<Tool>(), nextTools(tool("pdf-to-jpg"), "application/zip"))
    }

    @Test fun `accept matching`() {
        assertTrue(Accepts.Pdf.matches("application/pdf"))
        assertFalse(Accepts.Pdf.matches("image/png"))
        assertTrue(Accepts.Image.matches("image/webp"))
        assertFalse(Accepts.Image.matches("image/gif"))
        assertEquals(listOf("jpg-to-pdf"), toolsFor("image/png").map { it.slug })
    }
}
