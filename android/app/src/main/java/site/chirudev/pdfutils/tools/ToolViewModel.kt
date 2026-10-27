package site.chirudev.pdfutils.tools

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import site.chirudev.pdfutils.core.data.AccountRepository
import site.chirudev.pdfutils.core.data.IncomingFiles
import site.chirudev.pdfutils.core.data.LocalFile
import site.chirudev.pdfutils.core.data.PdfRepository
import site.chirudev.pdfutils.core.files.ResultFiles
import site.chirudev.pdfutils.core.network.ApiError
import site.chirudev.pdfutils.core.network.FileMeta
import site.chirudev.pdfutils.core.network.Job
import site.chirudev.pdfutils.core.network.Limits
import site.chirudev.pdfutils.core.pdf.PdfInfo
import site.chirudev.pdfutils.core.pdf.PdfThumbnailer
import site.chirudev.pdfutils.navigation.ToolRoute
import site.chirudev.pdfutils.ui.components.formatBytes
import java.io.File
import java.util.UUID
import javax.inject.Inject

@Immutable
data class FileItem(
    val key: String,
    val name: String,
    val size: Long,
    val mime: String,
    val localUri: Uri? = null,
    val meta: FileMeta? = null,
    val progress: Float = 0f,
    val error: String? = null,
    val locked: Boolean = false,
) {
    val uploaded get() = meta != null
    val uploading get() = meta == null && error == null
}

sealed interface Phase {
    data object Editing : Phase
    data class Processing(val job: Job?) : Phase
    data class Done(val output: FileMeta, val inputBytes: Long) : Phase
}

@Immutable
data class ToolUiState(
    val tool: Tool,
    val files: List<FileItem> = emptyList(),
    val values: Values = tool.defaultValues(),
    val confirm: Map<String, String> = emptyMap(),
    val editorUri: Uri? = null,
    val pageCount: Int? = null,
    val pageOrder: List<Int> = emptyList(),
    val removed: Set<Int> = emptySet(),
    val editorLoading: Boolean = false,
    val phase: Phase = Phase.Editing,
    val error: String? = null,
    val limitError: ApiError? = null,
    val submitting: Boolean = false,
    val busyAction: ResultAction? = null,
    val tasksLeft: Int? = null,
    val signedIn: Boolean = false,
) {
    val canSubmit get() = !submitting && files.size >= tool.minFiles && files.all { it.uploaded } && files.none { it.locked && tool.api != "unlock" }
}

enum class ResultAction { Save, SaveAs, Share, Open }

sealed interface ToolEvent {
    data class Launch(val intent: Intent) : ToolEvent
    data class CreateDocument(val name: String, val mime: String) : ToolEvent
    data class Message(val text: String) : ToolEvent
}

@HiltViewModel
class ToolViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: PdfRepository,
    private val account: AccountRepository,
    private val thumbnailer: PdfThumbnailer,
    private val results: ResultFiles,
    incoming: IncomingFiles,
) : ViewModel() {
    private val handle = savedState
    private val route = savedState.toRoute<ToolRoute>()
    val tool: Tool = toolBySlug(route.slug) ?: tools.first()

    private val _state = MutableStateFlow(ToolUiState(tool = tool))
    val state: StateFlow<ToolUiState> = _state.asStateFlow()

    private val _events = Channel<ToolEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val uploads = mutableMapOf<String, CoroutineJob>()
    private val uploadSlots = Semaphore(3)
    private var pollJob: CoroutineJob? = null
    private var resultFile: File? = null
    internal var io: CoroutineDispatcher = Dispatchers.IO

    private val limits: Limits get() = account.me.value?.limits ?: Limits()

    init {
        viewModelScope.launch {
            account.me.collect { me ->
                _state.update { it.copy(tasksLeft = me?.tasks?.remaining?.takeIf { r -> r <= 3 }, signedIn = me?.user != null) }
            }
        }
        val resumeJob = handle.get<String>(KEY_JOB)
        when {
            resumeJob != null -> resume(resumeJob)
            route.incoming -> addUris(incoming.take())
            !route.fileIds.isNullOrBlank() -> addRemote(route.fileIds.split(','))
        }
    }

    // ---- Picking & uploading -------------------------------------------------------------

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val described = withContext(io) { uris.mapNotNull { runCatching { repo.describe(it) }.getOrNull() } }
            val accepted = described.filter { tool.accepts.matches(it.mime) }
            if (accepted.size < described.size) {
                setError(if (tool.accepts == Accepts.Pdf) "Only PDF files work with ${tool.title}" else "Only JPG, PNG, WEBP or TIFF images work here")
            }
            val maxBytes = limits.maxFileMb * 1024L * 1024L
            val (fits, tooBig) = accepted.partition { it.size <= maxBytes }
            if (tooBig.isNotEmpty()) {
                _state.update { it.copy(limitError = ApiError(413, "file_too_large", "${tooBig.first().name} is larger than ${limits.maxFileMb} MB")) }
            }
            if (fits.isEmpty()) return@launch
            val capacity = if (tool.multiple) limits.maxFilesPerTask.coerceAtMost(200) else 1
            val toAdd = if (tool.multiple) fits.take((capacity - _state.value.files.size).coerceAtLeast(0)) else fits.take(1)
            if (tool.multiple && toAdd.size < fits.size) setError("You can add up to $capacity files at once")
            if (!tool.multiple) clearFiles()
            toAdd.forEach(::startUpload)
            if (!tool.multiple || tool.pageEditor != null) toAdd.firstOrNull()?.takeIf { it.isPdf }?.let { prepareEditor(it.uri) }
        }
    }

    private fun startUpload(file: LocalFile) {
        val key = UUID.randomUUID().toString()
        _state.update { it.copy(files = it.files + FileItem(key, file.name, file.size, file.mime, localUri = file.uri)) }
        uploads[key] = viewModelScope.launch {
            try {
                val meta = uploadSlots.withPermit { repo.upload(file) { p -> updateFile(key) { it.copy(progress = p) } } }
                updateFile(key) { it.copy(meta = meta, progress = 1f) }
            } catch (e: ApiError) {
                if (e.isLimit) _state.update { it.copy(limitError = e) }
                updateFile(key) { it.copy(error = e.message) }
            } finally {
                uploads.remove(key)
            }
        }
    }

    /** Chaining: files that are already on the server (a previous result or history). */
    fun addRemote(ids: List<String>) {
        viewModelScope.launch {
            for (id in ids) {
                try {
                    val meta = repo.fileMeta(id)
                    if (!tool.accepts.matches(meta.mime)) continue
                    _state.update { it.copy(files = it.files + FileItem(meta.id, meta.name, meta.size, meta.mime, meta = meta, progress = 1f)) }
                    if (tool.pageEditor != null && meta.isPdf) {
                        _state.update { it.copy(editorLoading = true) }
                        val local = repo.download(meta)
                        prepareEditor(Uri.fromFile(local))
                    }
                } catch (e: ApiError) {
                    setError(if (e.status == 404) "That file has expired. Please select it again." else e.message)
                    _state.update { it.copy(editorLoading = false) }
                }
            }
        }
    }

    private suspend fun prepareEditor(uri: Uri) {
        _state.update { it.copy(editorLoading = true) }
        when (val info = thumbnailer.info(uri)) {
            is PdfInfo.Ok -> _state.update {
                it.copy(editorUri = uri, pageCount = info.pageCount, pageOrder = (1..info.pageCount).toList(), removed = emptySet(), editorLoading = false)
            }
            PdfInfo.Locked -> {
                _state.update { s -> s.copy(editorLoading = false, files = s.files.map { f -> if (f.localUri == uri || s.files.size == 1) f.copy(locked = true) else f }) }
                if (tool.api != "unlock") setError("This PDF is password-protected. Unlock it first.")
            }
            PdfInfo.Broken -> _state.update { it.copy(editorLoading = false) }
        }
    }

    fun remove(key: String) {
        uploads.remove(key)?.cancel()
        _state.update { s ->
            val files = s.files.filterNot { it.key == key }
            if (files.isEmpty()) s.copy(files = files, editorUri = null, pageCount = null, pageOrder = emptyList(), removed = emptySet())
            else s.copy(files = files)
        }
    }

    fun retry(key: String) {
        val item = _state.value.files.firstOrNull { it.key == key } ?: return
        val uri = item.localUri ?: return
        remove(key)
        startUpload(LocalFile(uri, item.name, item.size, item.mime))
    }

    fun move(from: Int, to: Int) = _state.update { s ->
        if (from !in s.files.indices || to !in s.files.indices) s
        else s.copy(files = s.files.toMutableList().apply { add(to, removeAt(from)) })
    }

    private fun clearFiles() {
        uploads.values.forEach { it.cancel() }
        uploads.clear()
        _state.update { it.copy(files = emptyList(), editorUri = null, pageCount = null, pageOrder = emptyList(), removed = emptySet()) }
    }

    private fun updateFile(key: String, f: (FileItem) -> FileItem) =
        _state.update { s -> s.copy(files = s.files.map { if (it.key == key) f(it) else it }) }

    // ---- Options & page editors -----------------------------------------------------------

    fun setValue(key: String, value: Any) = _state.update { it.copy(values = it.values + (key to value), error = null) }
    fun setConfirm(key: String, value: String) = _state.update { it.copy(confirm = it.confirm + (key to value), error = null) }

    fun movePage(from: Int, to: Int) = _state.update { s ->
        if (from !in s.pageOrder.indices || to !in s.pageOrder.indices) s
        else s.copy(pageOrder = s.pageOrder.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun deletePage(page: Int) = _state.update { it.copy(pageOrder = it.pageOrder - page) }
    fun resetPages() = _state.update { s -> s.copy(pageOrder = s.pageCount?.let { (1..it).toList() }.orEmpty(), removed = emptySet()) }
    fun togglePage(page: Int) = _state.update { s -> s.copy(removed = if (page in s.removed) s.removed - page else s.removed + page) }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun dismissLimit() = _state.update { it.copy(limitError = null) }
    private fun setError(msg: String?) = _state.update { it.copy(error = msg) }

    // ---- Processing -------------------------------------------------------------------------

    fun submit() {
        val s = _state.value
        if (s.files.size < s.tool.minFiles) return setError(if (tool.minFiles > 1) "Add at least ${tool.minFiles} files" else "Select a file first")
        if (s.files.any { it.error != null }) return setError("Remove or retry the files that failed to upload")
        if (s.files.any { !it.uploaded }) return setError("Wait for the upload to finish")
        val editor = EditorState(pageOrder = s.pageOrder.takeIf { tool.pageEditor == PageEditor.Organize }, removed = s.removed.takeIf { tool.pageEditor == PageEditor.Remove }, pageCount = s.pageCount)
        tool.validate(s.values, s.confirm, editor)?.let { return setError(it) }

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val job = repo.startTask(tool.api, s.files.map { it.meta!!.id }, tool.toOptions(s.values, editor))
                handle[KEY_INPUT_BYTES] = s.files.sumOf { it.size.coerceAtLeast(0) }
                track(job)
            } catch (e: ApiError) {
                _state.update { it.copy(submitting = false, error = e.message, limitError = e.takeIf { e.isLimit }) }
            }
        }
    }

    /** After process death the job keeps running server-side; pick polling back up. */
    private fun resume(jobId: String) = track(Job(jobId, tool.api, Job.STATUS_QUEUED))

    private fun track(job: Job) {
        handle[KEY_JOB] = job.id
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            try {
                repo.poll(job).collect { j ->
                    when {
                        j.isRunning -> _state.update { it.copy(phase = Phase.Processing(j), submitting = false) }
                        j.status == Job.STATUS_DONE && j.output != null -> {
                            handle.remove<String>(KEY_JOB)
                            _state.update { it.copy(phase = Phase.Done(j.output, handle.get<Long>(KEY_INPUT_BYTES) ?: 0L), submitting = false) }
                            account.refreshMe()
                        }
                        else -> {
                            handle.remove<String>(KEY_JOB)
                            _state.update { it.copy(phase = Phase.Editing, submitting = false, error = friendlyJobError(j)) }
                            account.refreshMe()
                        }
                    }
                }
            } catch (e: ApiError) {
                _state.update { it.copy(phase = Phase.Editing, submitting = false, error = e.message) }
            }
        }
    }

    private fun friendlyJobError(j: Job): String = when (j.error?.code) {
        "wrong_password" -> "That password is not correct."
        "encrypted_pdf" -> "This PDF is password-protected. Unlock it first."
        "invalid_pdf" -> "This file doesn't look like a valid PDF."
        "not_encrypted" -> "This PDF isn't password-protected."
        "file_expired" -> "The file expired. Please select it again."
        else -> j.error?.message ?: "Processing failed. Please try again."
    }

    fun restart() {
        pollJob?.cancel()
        resultFile = null
        handle.remove<String>(KEY_JOB)
        clearFiles()
        _state.update { ToolUiState(tool = tool, tasksLeft = it.tasksLeft, signedIn = it.signedIn) }
    }

    // ---- Result -------------------------------------------------------------------------------

    fun onResultAction(action: ResultAction) {
        val output = (_state.value.phase as? Phase.Done)?.output ?: return
        if (_state.value.busyAction != null) return
        _state.update { it.copy(busyAction = action) }
        viewModelScope.launch {
            try {
                val file = resultFile ?: repo.download(output).also { resultFile = it }
                when (action) {
                    ResultAction.Save -> {
                        if (results.canSaveToDownloads) {
                            results.saveToDownloads(file, output.mime)
                            _events.send(ToolEvent.Message("Saved to Downloads/PDF Utils (${formatBytes(file.length())})"))
                        } else {
                            _events.send(ToolEvent.CreateDocument(file.name, output.mime))
                        }
                    }
                    ResultAction.SaveAs -> _events.send(ToolEvent.CreateDocument(file.name, output.mime))
                    ResultAction.Share -> _events.send(ToolEvent.Launch(results.shareIntent(file, output.mime)))
                    ResultAction.Open -> _events.send(ToolEvent.Launch(results.viewIntent(file, output.mime)))
                }
            } catch (e: ApiError) {
                _events.send(ToolEvent.Message(e.message))
            } catch (e: Exception) {
                _events.send(ToolEvent.Message(e.message ?: "Couldn't save the file"))
            } finally {
                _state.update { it.copy(busyAction = null) }
            }
        }
    }

    fun onDocumentCreated(target: Uri?) {
        val file = resultFile ?: return
        if (target == null) return
        viewModelScope.launch {
            runCatching { results.copyTo(file, target) }
                .onSuccess { _events.send(ToolEvent.Message("Saved ${file.name}")) }
                .onFailure { _events.send(ToolEvent.Message("Couldn't save the file")) }
        }
    }

    private companion object {
        const val KEY_JOB = "job_id"
        const val KEY_INPUT_BYTES = "input_bytes"
    }
}
