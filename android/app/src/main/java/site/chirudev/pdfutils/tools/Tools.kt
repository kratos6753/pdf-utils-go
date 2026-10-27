package site.chirudev.pdfutils.tools

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import site.chirudev.pdfutils.core.pdf.PageRanges
import kotlin.math.roundToLong

// The tool registry: a 1:1 port of frontend/src/lib/tools.ts. Adding a backend tool means
// adding one entry here.

enum class Category(val label: String) { Organize("Organize"), Optimize("Optimize"), Convert("Convert"), Edit("Edit"), Security("Security") }

enum class Accepts(val mimes: List<String>) {
    Pdf(listOf("application/pdf")),
    Image(listOf("image/jpeg", "image/png", "image/webp", "image/tiff"));

    fun matches(mime: String) = mime in mimes || (this == Image && mime.startsWith("image/") && mime != "image/gif" && mime != "image/svg+xml")

    companion object {
        fun of(mime: String): Accepts? = entries.firstOrNull { it.matches(mime) }
    }
}

enum class PageEditor { Organize, Remove }

/** Which icon to draw; mapped to an ImageVector in the UI layer. */
enum class ToolIconKey { Merge, Split, Compress, ImageToPdf, PdfToImage, Rotate, Organize, RemovePages, Watermark, PageNumbers, Protect, Unlock }

/** Form values: String, Double or Boolean, keyed by field key. */
typealias Values = Map<String, Any>

private val always: (Values) -> Boolean = { true }

@Immutable
sealed interface Field {
    val key: String
    val label: String
    val hint: String?
    val showIf: (Values) -> Boolean

    data class Option(val value: String, val label: String, val hint: String? = null)
    enum class Layout { Cards, Pills }

    data class Choice(
        override val key: String, override val label: String, val options: List<Option>, val default: String,
        val layout: Layout = Layout.Pills, override val hint: String? = null, override val showIf: (Values) -> Boolean = always,
    ) : Field

    data class Text(
        override val key: String, override val label: String, val placeholder: String? = null, val required: Boolean = false,
        val maxLength: Int? = null, override val hint: String? = null, override val showIf: (Values) -> Boolean = always,
    ) : Field

    data class Pages(
        override val key: String, override val label: String, val placeholder: String? = null, val required: Boolean = false,
        override val hint: String? = null, override val showIf: (Values) -> Boolean = always,
    ) : Field

    data class Password(
        override val key: String, override val label: String, val confirm: Boolean = false, val required: Boolean = false,
        override val hint: String? = null, override val showIf: (Values) -> Boolean = always,
    ) : Field

    data class Range(
        override val key: String, override val label: String, val min: Double, val max: Double, val step: Double, val default: Double,
        val unit: String? = null, override val hint: String? = null, override val showIf: (Values) -> Boolean = always,
    ) : Field

    data class Color(override val key: String, override val label: String, val default: String, override val hint: String? = null, override val showIf: (Values) -> Boolean = always) : Field

    data class Toggle(override val key: String, override val label: String, val default: Boolean, override val hint: String? = null, override val showIf: (Values) -> Boolean = always) : Field
}

/** Page-editor output that some tools turn into options. */
data class EditorState(val pageOrder: List<Int>? = null, val removed: Set<Int>? = null, val pageCount: Int? = null)

@Immutable
data class Tool(
    val slug: String,
    val api: String,
    val title: String,
    val verb: String,
    val description: String,
    val category: Category,
    val icon: ToolIconKey,
    val accepts: Accepts,
    val multiple: Boolean,
    val minFiles: Int,
    val fields: List<Field> = emptyList(),
    val pageEditor: PageEditor? = null,
    private val options: ((Values, EditorState) -> Map<String, Any?>)? = null,
    private val check: ((Values, EditorState) -> String?)? = null,
) {
    /** Turns form values into the API `options` object; defaults to passing values through. */
    fun toOptions(values: Values, editor: EditorState = EditorState()): JsonObject =
        toJsonObject(options?.invoke(values, editor) ?: values)

    fun defaultValues(): Values = fields.associate { f ->
        f.key to when (f) {
            is Field.Choice -> f.default
            is Field.Range -> f.default
            is Field.Color -> f.default
            is Field.Toggle -> f.default
            else -> ""
        }
    }

    /** Client-side validation; the server re-validates everything. Returns an error message. */
    fun validate(values: Values, confirm: Map<String, String> = emptyMap(), editor: EditorState = EditorState()): String? {
        for (f in fields) {
            if (!f.showIf(values)) continue
            val v = values[f.key]?.toString().orEmpty()
            val required = (f as? Field.Text)?.required ?: (f as? Field.Pages)?.required ?: (f as? Field.Password)?.required ?: false
            if (required && v.isBlank()) return "${f.label} is required"
            if (f is Field.Pages && v.isNotEmpty() && !PageRanges.isValid(v)) return "${f.label}: use page numbers like $PAGE_HINT"
            if (f.key == "ranges" && v.isNotEmpty() && !v.split(',').all { PageRanges.isValid(it) }) return "Ranges: use e.g. 1-3, 4-6"
            if (f is Field.Text && f.maxLength != null && v.length > f.maxLength) return "${f.label} is too long"
            if (f is Field.Password && f.confirm && v != confirm[f.key].orEmpty()) return "Passwords do not match"
        }
        return check?.invoke(values, editor)
    }
}

const val PAGE_HINT = "e.g. 1-3, 5, 8-10"

private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

private fun Values.str(key: String) = this[key]?.toString().orEmpty()

private fun Values.num(key: String): Double = when (val v = this[key]) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull() ?: 0.0
    else -> 0.0
}

val tools: List<Tool> = listOf(
    Tool(
        slug = "merge-pdf", api = "merge", title = "Merge PDF", verb = "Merge PDF",
        description = "Combine PDFs in the order you want into a single file.",
        category = Category.Organize, icon = ToolIconKey.Merge, accepts = Accepts.Pdf, multiple = true, minFiles = 2,
    ),
    Tool(
        slug = "split-pdf", api = "split", title = "Split PDF", verb = "Split PDF",
        description = "Separate pages into independent files, by range or every N pages.",
        category = Category.Organize, icon = ToolIconKey.Split, accepts = Accepts.Pdf, multiple = false, minFiles = 1,
        fields = listOf(
            Field.Choice(
                "mode", "Split mode", layout = Field.Layout.Cards, default = "ranges",
                options = listOf(
                    Field.Option("ranges", "By range", "One file per range"),
                    Field.Option("every", "Fixed size", "Every N pages"),
                    Field.Option("extract", "Extract pages", "Pick specific pages"),
                ),
            ),
            Field.Text("ranges", "Ranges", placeholder = "1-3, 4-6, 7-10", hint = "Each range becomes its own PDF.", required = true, showIf = { it["mode"] == "ranges" }),
            Field.Range("every", "Pages per file", min = 1.0, max = 50.0, step = 1.0, default = 1.0, showIf = { it["mode"] == "every" }),
            Field.Pages("pages", "Pages to extract", placeholder = PAGE_HINT, required = true, showIf = { it["mode"] == "extract" }),
            Field.Toggle("merge", "Merge extracted pages into one PDF", default = true, showIf = { it["mode"] == "extract" }),
        ),
        options = { v, _ ->
            when (v["mode"]) {
                "ranges" -> mapOf("mode" to "ranges", "ranges" to v.str("ranges").split(',').map { it.trim() }.filter { it.isNotEmpty() })
                "every" -> mapOf("mode" to "every", "every" to v.num("every"))
                else -> mapOf("mode" to "extract", "pages" to v.str("pages"), "merge" to (v["merge"] as? Boolean ?: true))
            }
        },
    ),
    Tool(
        slug = "compress-pdf", api = "compress", title = "Compress PDF", verb = "Compress PDF",
        description = "Reduce file size while keeping the best possible quality.",
        category = Category.Optimize, icon = ToolIconKey.Compress, accepts = Accepts.Pdf, multiple = false, minFiles = 1,
        fields = listOf(
            Field.Choice(
                "level", "Compression level", layout = Field.Layout.Cards, default = "recommended",
                options = listOf(
                    Field.Option("extreme", "Extreme", "Smallest file, lower quality"),
                    Field.Option("recommended", "Recommended", "Good quality, good compression"),
                    Field.Option("low", "Less compression", "High quality, larger file"),
                ),
            ),
        ),
    ),
    Tool(
        slug = "jpg-to-pdf", api = "image_to_pdf", title = "JPG to PDF", verb = "Convert to PDF",
        description = "Turn JPG, PNG, WEBP and TIFF images into a PDF in seconds.",
        category = Category.Convert, icon = ToolIconKey.ImageToPdf, accepts = Accepts.Image, multiple = true, minFiles = 1,
        fields = listOf(
            Field.Choice("page_size", "Page size", default = "fit", options = listOf(Field.Option("fit", "Fit to image"), Field.Option("A4", "A4"), Field.Option("Letter", "US Letter"))),
            Field.Choice("orientation", "Orientation", default = "portrait", options = listOf(Field.Option("portrait", "Portrait"), Field.Option("landscape", "Landscape")), showIf = { it["page_size"] != "fit" }),
            Field.Choice("margin", "Margin", default = "none", options = listOf(Field.Option("none", "No margin"), Field.Option("small", "Small"), Field.Option("big", "Big")), showIf = { it["page_size"] != "fit" }),
        ),
    ),
    Tool(
        slug = "pdf-to-jpg", api = "pdf_to_image", title = "PDF to JPG", verb = "Convert to images",
        description = "Convert every PDF page into a high-quality JPG or PNG image.",
        category = Category.Convert, icon = ToolIconKey.PdfToImage, accepts = Accepts.Pdf, multiple = false, minFiles = 1,
        fields = listOf(
            Field.Choice("format", "Image format", default = "jpg", options = listOf(Field.Option("jpg", "JPG"), Field.Option("png", "PNG"))),
            Field.Choice("dpi", "Quality", default = "150", options = listOf(Field.Option("72", "Web (72 dpi)"), Field.Option("150", "Standard (150 dpi)"), Field.Option("300", "High (300 dpi)"))),
            Field.Pages("pages", "Pages (optional)", placeholder = "All pages"),
        ),
        options = { v, _ -> mapOf("format" to v["format"], "dpi" to v.num("dpi"), "pages" to v.str("pages").orNullIfBlank()) },
    ),
    Tool(
        slug = "rotate-pdf", api = "rotate", title = "Rotate PDF", verb = "Rotate PDF",
        description = "Rotate all pages, or just the ones you choose.",
        category = Category.Organize, icon = ToolIconKey.Rotate, accepts = Accepts.Pdf, multiple = false, minFiles = 1,
        fields = listOf(
            Field.Choice("angle", "Rotation", default = "90", options = listOf(Field.Option("90", "90° right"), Field.Option("180", "180°"), Field.Option("270", "90° left"))),
            Field.Pages("pages", "Pages (optional)", placeholder = "All pages"),
        ),
        options = { v, _ -> mapOf("angle" to v.num("angle"), "pages" to v.str("pages").orNullIfBlank()) },
    ),
    Tool(
        slug = "organize-pdf", api = "organize", title = "Organize PDF", verb = "Save changes",
        description = "Drag to reorder pages or delete the ones you do not need.",
        category = Category.Organize, icon = ToolIconKey.Organize, accepts = Accepts.Pdf, multiple = false, minFiles = 1,
        pageEditor = PageEditor.Organize,
        options = { _, e -> mapOf("order" to e.pageOrder) },
        check = { _, e -> if (e.pageOrder.isNullOrEmpty()) "Keep at least one page" else null },
    ),
    Tool(
        slug = "remove-pages", api = "remove_pages", title = "Remove pages", verb = "Remove pages",
        description = "Tap the pages you want to delete from your PDF.",
        category = Category.Organize, icon = ToolIconKey.RemovePages, accepts = Accepts.Pdf, multiple = false, minFiles = 1,
        pageEditor = PageEditor.Remove,
        options = { _, e -> mapOf("pages" to PageRanges.compact(e.removed.orEmpty())) },
        check = { _, e ->
            when {
                e.removed.isNullOrEmpty() -> "Select at least one page to remove"
                e.pageCount != null && e.removed.size >= e.pageCount -> "You cannot remove every page"
                else -> null
            }
        },
    ),
    Tool(
        slug = "add-watermark", api = "watermark", title = "Add watermark", verb = "Add watermark",
        description = "Stamp text over your PDF pages, with the opacity and angle you choose.",
        category = Category.Edit, icon = ToolIconKey.Watermark, accepts = Accepts.Pdf, multiple = false, minFiles = 1,
        fields = listOf(
            Field.Text("text", "Watermark text", placeholder = "CONFIDENTIAL", required = true, maxLength = 200),
            Field.Range("font_size", "Font size", min = 12.0, max = 120.0, step = 2.0, default = 48.0, unit = "pt"),
            Field.Range("opacity", "Opacity", min = 0.1, max = 1.0, step = 0.05, default = 0.3),
            Field.Range("rotation", "Angle", min = -90.0, max = 90.0, step = 5.0, default = 45.0, unit = "°"),
            Field.Color("color", "Color", default = "#808080"),
            Field.Choice(
                "position", "Position", default = "c",
                options = listOf(Field.Option("tl", "Top left"), Field.Option("tc", "Top"), Field.Option("c", "Center"), Field.Option("bc", "Bottom"), Field.Option("br", "Bottom right")),
            ),
        ),
    ),
    Tool(
        slug = "add-page-numbers", api = "page_numbers", title = "Add page numbers", verb = "Add page numbers",
        description = "Number your pages with the position and format you need.",
        category = Category.Edit, icon = ToolIconKey.PageNumbers, accepts = Accepts.Pdf, multiple = false, minFiles = 1,
        fields = listOf(
            Field.Choice(
                "position", "Position", default = "bc",
                options = listOf(
                    Field.Option("bl", "Bottom left"), Field.Option("bc", "Bottom center"), Field.Option("br", "Bottom right"),
                    Field.Option("tl", "Top left"), Field.Option("tc", "Top center"), Field.Option("tr", "Top right"),
                ),
            ),
            Field.Choice(
                "format", "Format", default = "n",
                options = listOf(Field.Option("n", "1"), Field.Option("n_of_total", "1 of 10"), Field.Option("page_n", "Page 1"), Field.Option("page_n_of_total", "Page 1 of 10")),
            ),
            Field.Range("font_size", "Font size", min = 8.0, max = 24.0, step = 1.0, default = 11.0, unit = "pt"),
            Field.Pages("pages", "Pages (optional)", placeholder = "All pages"),
        ),
        options = { v, _ -> v + ("pages" to v.str("pages").orNullIfBlank()) },
    ),
    Tool(
        slug = "protect-pdf", api = "protect", title = "Protect PDF", verb = "Protect PDF",
        description = "Encrypt your PDF with a password (AES-256) to keep it private.",
        category = Category.Security, icon = ToolIconKey.Protect, accepts = Accepts.Pdf, multiple = false, minFiles = 1,
        fields = listOf(Field.Password("password", "Password", confirm = true, required = true)),
    ),
    Tool(
        slug = "unlock-pdf", api = "unlock", title = "Unlock PDF", verb = "Unlock PDF",
        description = "Remove the password and restrictions from a PDF you can open.",
        category = Category.Security, icon = ToolIconKey.Unlock, accepts = Accepts.Pdf, multiple = false, minFiles = 1,
        fields = listOf(Field.Password("password", "Current password", hint = "Leave empty if the PDF opens without a password but is restricted.")),
    ),
)

fun toolBySlug(slug: String): Tool? = tools.firstOrNull { it.slug == slug }

/** Tools a file of this mime type can be chained into (the "Continue to" list). */
fun nextTools(current: Tool?, mime: String): List<Tool> {
    val kind = Accepts.of(mime) ?: return emptyList()
    return listOf("compress-pdf", "merge-pdf", "split-pdf", "protect-pdf", "add-page-numbers", "organize-pdf", "add-watermark", "pdf-to-jpg")
        .mapNotNull(::toolBySlug)
        .filter { it.slug != current?.slug && it.accepts == kind }
        .take(6)
}

/** Every tool that accepts this mime type (for files shared into the app). */
fun toolsFor(mime: String): List<Tool> = tools.filter { it.accepts.matches(mime) }

// JSON mapping. Whole numbers are sent as integers: the Go backend rejects 48.0 for an int field.
internal fun toJsonObject(map: Map<String, Any?>): JsonObject = JsonObject(map.mapNotNull { (k, v) -> toJson(v)?.let { k to it } }.toMap())

private fun toJson(v: Any?): JsonElement? = when (v) {
    null -> null
    is JsonElement -> v
    is Boolean -> JsonPrimitive(v)
    is Int, is Long -> JsonPrimitive(v as Number)
    is Number -> v.toDouble().let { d -> if (d % 1.0 == 0.0) JsonPrimitive(d.toLong()) else JsonPrimitive((d * 10_000).roundToLong() / 10_000.0) }
    is String -> JsonPrimitive(v)
    is Iterable<*> -> JsonArray(v.mapNotNull(::toJson))
    is Map<*, *> -> JsonObject(v.entries.mapNotNull { (k, value) -> toJson(value)?.let { k.toString() to it } }.toMap())
    else -> JsonPrimitive(v.toString())
}
