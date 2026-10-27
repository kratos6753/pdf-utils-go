package site.chirudev.pdfutils.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import site.chirudev.pdfutils.core.pdf.PdfThumbnailer

val LocalThumbnailer = staticCompositionLocalOf<PdfThumbnailer?> { null }

/** Lazily renders one PDF page; shows a document glyph until (or unless) it's ready. */
@Composable
fun PdfPageThumb(uri: Uri, page: Int, width: Dp, modifier: Modifier = Modifier) {
    val thumbnailer = LocalThumbnailer.current
    val px = with(LocalDensity.current) { width.roundToPx() }.coerceAtMost(480)
    val bitmap by produceState<Bitmap?>(null, uri, page, px) { value = thumbnailer?.render(uri, page, px) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = "Page ${page + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(28.dp))
        }
    }
}

/** First-page / image preview for a file row. */
@Composable
fun FileThumb(uri: Uri?, mime: String, locked: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        when {
            locked -> Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
            uri != null && mime == "application/pdf" -> PdfPageThumb(uri, 0, 56.dp, Modifier.fillMaxSize())
            uri != null && mime.startsWith("image/") && mime != "image/tiff" ->
                AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            mime.startsWith("image/") -> Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            mime == "application/zip" -> Icon(Icons.Rounded.FolderZip, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
