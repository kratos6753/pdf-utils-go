package site.chirudev.pdfutils.core.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import site.chirudev.pdfutils.core.di.DownloadClient
import site.chirudev.pdfutils.core.network.ApiError
import site.chirudev.pdfutils.core.network.ApiService
import site.chirudev.pdfutils.core.network.FileMeta
import site.chirudev.pdfutils.core.network.Job
import site.chirudev.pdfutils.core.network.ProgressRequestBody
import site.chirudev.pdfutils.core.network.TaskRequest
import site.chirudev.pdfutils.core.network.apiCall
import site.chirudev.pdfutils.core.network.orThrow
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A file picked on the device, not yet (or being) uploaded. */
data class LocalFile(val uri: Uri, val name: String, val size: Long, val mime: String) {
    val isPdf get() = mime == "application/pdf"
}

/** Reads picked files; split out so tests can fake content:// access. */
interface FileSource {
    fun describe(uri: Uri): LocalFile
    fun open(uri: Uri): InputStream
}

@Singleton
class ContentFileSource @Inject constructor(@ApplicationContext private val context: Context) : FileSource {
    private val resolver: ContentResolver get() = context.contentResolver

    override fun describe(uri: Uri): LocalFile {
        var name: String? = null
        var size = -1L
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val f = File(uri.path!!)
            name = f.name
            size = f.length()
        } else {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val fileName = name ?: uri.lastPathSegment ?: "file"
        val mime = resolver.getType(uri) ?: guessMime(fileName)
        return LocalFile(uri, fileName, size, mime)
    }

    override fun open(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw ApiError(0, "unreadable", "Couldn't read that file")

    private fun guessMime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "tif", "tiff" -> "image/tiff"
        else -> "application/octet-stream"
    }
}

/** Upload → task → poll → download, plus file management. */
@Singleton
class PdfRepository @Inject constructor(
    private val api: ApiService,
    private val files: FileSource,
    private val foreground: Foreground,
    @DownloadClient private val downloadClient: OkHttpClient,
    @ApplicationContext private val context: Context,
) {
    fun describe(uri: Uri): LocalFile = files.describe(uri)

    suspend fun upload(file: LocalFile, onProgress: (Float) -> Unit): FileMeta = apiCall {
        val body = ProgressRequestBody(file.mime.toMediaTypeOrNull(), file.size, { files.open(file.uri) }, onProgress)
        val part = MultipartBody.Part.createFormData("file", file.name, body)
        withContext(Dispatchers.IO) { api.upload(part) }.files.firstOrNull()
            ?: throw ApiError(0, "upload_failed", "Upload failed")
    }

    suspend fun startTask(tool: String, fileIds: List<String>, options: JsonObject): Job = apiCall {
        api.createTask(tool, TaskRequest(fileIds, options), idempotencyKey = UUID.randomUUID().toString())
    }

    /**
     * Polls a job until it finishes, emitting every update. Matches the web cadence (fast for the
     * first few seconds, then slower) and pauses entirely while the app is in the background.
     */
    fun poll(job: Job): Flow<Job> = flow {
        var current = job
        emit(current)
        var n = 0
        while (current.isRunning) {
            delay(pollDelay(n++))
            foreground.await()
            current = apiCall { api.job(current.id) }
            emit(current)
        }
    }

    suspend fun fileMeta(id: String): FileMeta = apiCall { api.file(id) }

    suspend fun myFiles(kind: String?, cursor: String?) = apiCall { api.myFiles(kind, cursor) }

    suspend fun delete(id: String) {
        apiCall { api.deleteFile(id).orThrow() }
        withContext(Dispatchers.IO) { File(cacheRoot(), id).deleteRecursively() }
    }

    /** Downloads a server file into the app cache once and reuses it (for save, share, previews). */
    suspend fun download(file: FileMeta): File = withContext(Dispatchers.IO) {
        val dir = File(cacheRoot(), file.id).apply { mkdirs() }
        val target = File(dir, safeName(file.name))
        if (target.exists() && target.length() == file.size) return@withContext target
        val link = apiCall { api.fileLink(file.id) }
        val tmp = File(dir, target.name + ".part")
        try {
            downloadClient.newCall(Request.Builder().url(link.url).build()).execute().use { res ->
                if (!res.isSuccessful) throw ApiError.parse(res.code, res.body.string(), "Download failed")
                tmp.outputStream().use { out -> res.body.byteStream().copyTo(out, 64 * 1024) }
            }
            if (!tmp.renameTo(target)) throw ApiError(0, "download_failed", "Download failed")
        } catch (t: Throwable) {
            tmp.delete()
            throw ApiError.from(t)
        }
        target
    }

    /** Drops cached results older than a day so the cache never grows unbounded. */
    suspend fun trimCache() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        cacheRoot().listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    private fun cacheRoot() = File(context.cacheDir, "results")

    companion object {
        fun pollDelay(n: Int): Long = if (n < 10) 600 else 1500
        fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").ifBlank { "file" }
    }
}
