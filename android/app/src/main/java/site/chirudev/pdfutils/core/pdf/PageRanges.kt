package site.chirudev.pdfutils.core.pdf

/** Page selections like "1-3, 5, 8-10", validated exactly like the server and web app. */
object PageRanges {
    private val pattern = Regex("""^\s*\d+(\s*-\s*\d+)?(\s*,\s*\d+(\s*-\s*\d+)?)*\s*$""")

    fun isValid(input: String): Boolean = pattern.matches(input)

    /** Expands a valid selection to 1-based page numbers, clamped to [pageCount] when known. */
    fun expand(input: String, pageCount: Int? = null): List<Int> {
        if (!isValid(input)) return emptyList()
        val pages = linkedSetOf<Int>()
        for (part in input.split(',')) {
            val bounds = part.split('-').map { it.trim().toInt() }
            val from = bounds.first()
            val to = bounds.last()
            for (p in minOf(from, to)..maxOf(from, to)) {
                if (p >= 1 && (pageCount == null || p <= pageCount)) pages += p
            }
        }
        return pages.toList()
    }

    /** Compacts page numbers back into a selection string: [1,2,3,5] → "1-3,5". */
    fun compact(pages: Collection<Int>): String {
        val sorted = pages.toSortedSet().toList()
        if (sorted.isEmpty()) return ""
        val out = StringBuilder()
        var start = sorted[0]
        var prev = start
        fun flush() {
            if (out.isNotEmpty()) out.append(',')
            out.append(if (start == prev) "$start" else "$start-$prev")
        }
        for (p in sorted.drop(1)) {
            if (p == prev + 1) { prev = p; continue }
            flush()
            start = p
            prev = p
        }
        flush()
        return out.toString()
    }
}
