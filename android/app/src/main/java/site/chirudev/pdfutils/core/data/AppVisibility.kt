package site.chirudev.pdfutils.core.data

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Lets background-sensitive work (job polling) pause while the app isn't visible. */
interface Foreground {
    suspend fun await()
}

@Singleton
class AppVisibility @Inject constructor() : Foreground, DefaultLifecycleObserver {
    private val visible = MutableStateFlow(true)

    fun install() = ProcessLifecycleOwner.get().lifecycle.addObserver(this)

    override fun onStart(owner: LifecycleOwner) { visible.value = true }
    override fun onStop(owner: LifecycleOwner) { visible.value = false }

    override suspend fun await() {
        visible.first { it }
    }
}
