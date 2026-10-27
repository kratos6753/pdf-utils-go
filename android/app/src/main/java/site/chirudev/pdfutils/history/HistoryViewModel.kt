package site.chirudev.pdfutils.history

import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import site.chirudev.pdfutils.core.data.AccountRepository
import site.chirudev.pdfutils.core.data.PdfRepository
import site.chirudev.pdfutils.core.files.ResultFiles
import site.chirudev.pdfutils.core.network.ApiError
import site.chirudev.pdfutils.core.network.FileMeta
import site.chirudev.pdfutils.core.network.StorageUsage
import javax.inject.Inject

enum class HistoryFilter(val label: String, val kind: String?) { All("All", null), Results("Results", "output"), Uploads("Uploads", "input") }

@Immutable
data class HistoryUiState(
    val filter: HistoryFilter = HistoryFilter.All,
    val files: List<FileMeta> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val busyId: String? = null,
    val storage: StorageUsage? = null,
    val signedIn: Boolean = false,
    val loaded: Boolean = false,
)

sealed interface HistoryEvent {
    data class Launch(val intent: Intent) : HistoryEvent
    data class Message(val text: String) : HistoryEvent
}

enum class FileAction { Open, Share, Save }

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repo: PdfRepository,
    private val account: AccountRepository,
    private val results: ResultFiles,
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private val _events = Channel<HistoryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            account.me.collect { me -> _state.update { it.copy(storage = me?.storage, signedIn = me?.user != null) } }
        }
        viewModelScope.launch {
            account.user.collect { user ->
                if (user != null) load(reset = true) else _state.update { it.copy(files = emptyList(), nextCursor = null, loaded = false) }
            }
        }
    }

    fun setFilter(filter: HistoryFilter) {
        if (filter == _state.value.filter) return
        _state.update { it.copy(filter = filter, files = emptyList(), nextCursor = null) }
        load(reset = true)
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch { account.refreshMe() }
        load(reset = true)
    }

    fun load(reset: Boolean) {
        val s = _state.value
        if (!reset && (s.nextCursor == null || s.loadingMore || s.loading)) return
        loadJob?.cancel()
        _state.update { if (reset) it.copy(loading = !it.refreshing, error = null) else it.copy(loadingMore = true) }
        loadJob = viewModelScope.launch {
            try {
                val page = repo.myFiles(s.filter.kind, if (reset) null else s.nextCursor)
                _state.update {
                    it.copy(
                        files = if (reset) page.files else (it.files + page.files).distinctBy { f -> f.id },
                        nextCursor = page.nextCursor,
                        loading = false, refreshing = false, loadingMore = false, loaded = true,
                    )
                }
            } catch (e: ApiError) {
                _state.update { it.copy(loading = false, refreshing = false, loadingMore = false, error = e.message, loaded = true) }
            }
        }
    }

    fun delete(file: FileMeta) {
        viewModelScope.launch {
            val before = _state.value.files
            _state.update { s -> s.copy(files = s.files.filterNot { it.id == file.id }) } // optimistic
            try {
                repo.delete(file.id)
                account.refreshMe()
                _events.send(HistoryEvent.Message("Deleted ${file.name}"))
            } catch (e: ApiError) {
                _state.update { it.copy(files = before) }
                _events.send(HistoryEvent.Message(e.message))
            }
        }
    }

    fun act(file: FileMeta, action: FileAction) {
        if (_state.value.busyId != null) return
        _state.update { it.copy(busyId = file.id) }
        viewModelScope.launch {
            try {
                val local = repo.download(file)
                when (action) {
                    FileAction.Open -> _events.send(HistoryEvent.Launch(results.viewIntent(local, file.mime)))
                    FileAction.Share -> _events.send(HistoryEvent.Launch(results.shareIntent(local, file.mime)))
                    FileAction.Save -> if (results.canSaveToDownloads) {
                        results.saveToDownloads(local, file.mime)
                        _events.send(HistoryEvent.Message("Saved to Downloads/PDF Utils"))
                    } else {
                        _events.send(HistoryEvent.Launch(results.shareIntent(local, file.mime)))
                    }
                }
            } catch (e: ApiError) {
                _events.send(HistoryEvent.Message(if (e.status == 404) "That file has expired" else e.message))
            } catch (e: Exception) {
                _events.send(HistoryEvent.Message(e.message ?: "Something went wrong"))
            } finally {
                _state.update { it.copy(busyId = null) }
            }
        }
    }
}
