package site.chirudev.pdfutils.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.core.graphics.createBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PdfInfo {
    data class Ok(val pageCount: Int) : PdfInfo
    data object Locked : PdfInfo
    data object Broken : PdfInfo
}

/**
 * Renders small page previews with the platform PdfRenderer (no bundled PDF engine).
 * One document stays open at a time, rendering is serialized (PdfRenderer isn't thread-safe),
 * and bitmaps are cached in a size-bounded LRU so scrolling a page grid doesn't re-render.
 */
@Singleton
class PdfThumbnailer @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    private var openUri: Uri? = null
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    suspend fun info(uri: Uri): PdfInfo = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                PdfInfo.Ok(open(uri).pageCount)
            } catch (_: SecurityException) {
                PdfInfo.Locked
            } catch (_: Exception) {
                PdfInfo.Broken
            }
        }
    }

    /** Renders 0-based [page] at [widthPx] wide; null if the file can't be rendered. */
    suspend fun render(uri: Uri, page: Int, widthPx: Int): Bitmap? {
        val key = "$uri#$page@$widthPx"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                cache.get(key) ?: runCatching {
                    val r = open(uri)
                    if (page !in 0 until r.pageCount) return@runCatching null
                    r.openPage(page).use { p ->
                        val height = (widthPx.toFloat() * p.height / p.width).toInt().coerceIn(1, widthPx * 3)
                        createBitmap(widthPx, height).also { bmp ->
                            bmp.eraseColor(Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }.getOrNull()?.also { cache.put(key, it) }
            }
        }
    }

    /** Releases the open document (e.g. when leaving a tool). */
    suspend fun close() = mutex.withLock { closeLocked() }

    private fun open(uri: Uri): PdfRenderer {
        if (uri == openUri) renderer?.let { return it }
        closeLocked()
        val descriptor = when (uri.scheme) {
            "file" -> ParcelFileDescriptor.open(java.io.File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
            else -> context.contentResolver.openFileDescriptor(uri, "r") ?: error("Can't open $uri")
        }
        try {
            return PdfRenderer(descriptor).also {
                fd = descriptor
                renderer = it
                openUri = uri
            }
        } catch (t: Throwable) {
            descriptor.close()
            throw t
        }
    }

    private fun closeLocked() {
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
        renderer = null
        fd = null
        openUri = null
    }

    private companion object {
        fun maxCacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024).toInt()
    }
}
