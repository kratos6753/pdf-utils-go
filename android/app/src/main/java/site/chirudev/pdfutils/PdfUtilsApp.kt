package site.chirudev.pdfutils

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import site.chirudev.pdfutils.core.data.AppVisibility
import javax.inject.Inject

@HiltAndroidApp
class PdfUtilsApp : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var visibility: AppVisibility

    override fun onCreate() {
        super.onCreate()
        visibility.install()
    }

    // Thumbnails only: small memory cache, no disk cache (sources are already local files).
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .crossfade(true)
            .diskCache(null)
            .build()
}
