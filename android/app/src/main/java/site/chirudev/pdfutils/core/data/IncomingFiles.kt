package site.chirudev.pdfutils.core.data

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Files shared into the app (ACTION_SEND / VIEW) waiting for the user to pick a tool. */
@Singleton
class IncomingFiles @Inject constructor() {
    private val _pending = MutableStateFlow<List<Uri>>(emptyList())
    val pending: StateFlow<List<Uri>> = _pending.asStateFlow()

    /** Uris handed from the share sheet to the tool the user picked. */
    private var handoff: List<Uri> = emptyList()

    fun offer(uris: List<Uri>) { _pending.value = uris }
    fun dismiss() { _pending.value = emptyList() }

    fun handOff() {
        handoff = _pending.value
        _pending.value = emptyList()
    }

    fun take(): List<Uri> = handoff.also { handoff = emptyList() }
}
