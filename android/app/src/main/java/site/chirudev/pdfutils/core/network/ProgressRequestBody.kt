package site.chirudev.pdfutils.core.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream

/**
 * Streams an upload straight from its source (e.g. a content:// Uri) without buffering the
 * file in memory, reporting progress as a 0..1 fraction. Progress callbacks are throttled to
 * whole-percent changes so the UI isn't flooded.
 */
class ProgressRequestBody(
    private val contentType: MediaType?,
    private val length: Long,
    private val open: () -> InputStream,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {
    override fun contentType() = contentType
    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
        open().source().use { source ->
            var written = 0L
            var lastPercent = -1
            val buffer = sink.buffer
            while (true) {
                val read = source.read(buffer, SEGMENT)
                if (read == -1L) break
                written += read
                sink.emitCompleteSegments()
                if (length > 0) {
                    val percent = (written * 100 / length).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent / 100f)
                    }
                }
            }
        }
    }

    private companion object {
        const val SEGMENT = 64 * 1024L
    }
}
