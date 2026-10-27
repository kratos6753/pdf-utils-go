package site.chirudev.pdfutils

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import site.chirudev.pdfutils.core.data.AccountRepository
import site.chirudev.pdfutils.core.data.IncomingFiles
import site.chirudev.pdfutils.core.data.PdfRepository
import site.chirudev.pdfutils.core.data.SessionManager
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val session: SessionManager,
    private val account: AccountRepository,
    private val repo: PdfRepository,
    val incoming: IncomingFiles,
) : ViewModel() {
    private val _ready = MutableStateFlow(false)
    /** The splash screen stays up until the stored session (if any) has been restored. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    val user = account.user

    init {
        viewModelScope.launch {
            withTimeoutOrNull(2_500) { session.restore() }
            _ready.value = true
            account.refreshMe()
            account.loadConfig()
            repo.trimCache()
        }
    }

    /** Called when the app comes back to the foreground: usage numbers may have reset. */
    fun refresh() {
        if (_ready.value) viewModelScope.launch { account.refreshMe() }
    }
}
