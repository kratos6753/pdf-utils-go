package site.chirudev.pdfutils.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.automirrored.rounded.BrandingWatermark
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import site.chirudev.pdfutils.tools.Tool
import site.chirudev.pdfutils.tools.ToolIconKey
import site.chirudev.pdfutils.ui.theme.color

val ToolIconKey.vector: ImageVector
    get() = when (this) {
        ToolIconKey.Merge -> Icons.Rounded.Merge
        ToolIconKey.Split -> Icons.Rounded.ContentCut
        ToolIconKey.Compress -> Icons.Rounded.Compress
        ToolIconKey.ImageToPdf -> Icons.Rounded.AddPhotoAlternate
        ToolIconKey.PdfToImage -> Icons.Rounded.Collections
        ToolIconKey.Rotate -> Icons.Rounded.Rotate90DegreesCw
        ToolIconKey.Organize -> Icons.Rounded.GridView
        ToolIconKey.RemovePages -> Icons.Rounded.DeleteSweep
        ToolIconKey.Watermark -> Icons.AutoMirrored.Rounded.BrandingWatermark
        ToolIconKey.PageNumbers -> Icons.Rounded.Tag
        ToolIconKey.Protect -> Icons.Rounded.Lock
        ToolIconKey.Unlock -> Icons.Rounded.LockOpen
    }

/** The tool's icon on a tile tinted with its category color, like the web's ToolIcon. */
@Composable
fun ToolIcon(tool: Tool, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val tint = tool.category.color()
    Box(
        modifier
            .size(size)
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(tool.icon.vector, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}
