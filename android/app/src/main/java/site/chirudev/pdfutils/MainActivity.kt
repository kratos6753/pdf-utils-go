package site.chirudev.pdfutils

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.CompositionLocalProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import site.chirudev.pdfutils.core.pdf.PdfThumbnailer
import site.chirudev.pdfutils.navigation.AppNav
import site.chirudev.pdfutils.navigation.ResetRoute
import site.chirudev.pdfutils.navigation.VerifyRoute
import site.chirudev.pdfutils.ui.components.LocalThumbnailer
import site.chirudev.pdfutils.ui.theme.PdfUtilsTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var thumbnailer: PdfThumbnailer

    private val vm: MainViewModel by viewModels()

    /** A route requested from outside the app (email link) waiting to be navigated to. */
    private val pendingRoute = MutableStateFlow<Any?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { !vm.ready.value }
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)

        setContent {
            PdfUtilsTheme {
                CompositionLocalProvider(LocalThumbnailer provides thumbnailer) {
                    AppNav(main = vm, pendingRoute = pendingRoute)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.refresh()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { vm.incoming.offer(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.takeIf { it.isNotEmpty() }?.let { vm.incoming.offer(it) }
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme == "https") {
                    val token = data.getQueryParameter("token") ?: return
                    pendingRoute.value = when (data.path) {
                        "/verify-email" -> VerifyRoute(token)
                        "/reset-password" -> ResetRoute(token)
                        else -> null
                    }
                } else {
                    vm.incoming.offer(listOf(data))
                }
            }
        }
    }
}
