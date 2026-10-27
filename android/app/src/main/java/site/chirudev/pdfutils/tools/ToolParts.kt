package site.chirudev.pdfutils.tools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableLazyGridState
import sh.calvin.reorderable.ReorderableItem
import site.chirudev.pdfutils.ui.components.FileThumb
import site.chirudev.pdfutils.ui.components.PdfPageThumb
import site.chirudev.pdfutils.ui.components.formatBytes
import androidx.compose.foundation.lazy.grid.LazyGridItemScope

/** One picked file: preview, name/size, upload progress, and remove/retry. */
@Composable
fun LazyGridItemScope.FileRow(
    item: FileItem,
    reorderState: ReorderableLazyGridState,
    reorderable: Boolean,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    ReorderableItem(reorderState, key = "f:${item.key}", enabled = reorderable) { dragging ->
        val elevation = if (dragging) 8.dp else 0.dp
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, if (item.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = elevation,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (reorderable) Modifier.longPressDraggableHandle(
                        onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    ) else Modifier,
                ),
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                FileThumb(item.localUri, item.mime, item.locked, Modifier.size(48.dp, 60.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    val status = when {
                        item.error != null -> item.error
                        item.locked -> "Password-protected"
                        item.uploading -> "Uploading… ${(item.progress * 100).toInt()}%"
                        else -> formatBytes(item.size)
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                    if (item.uploading) {
                        val progress by animateFloatAsState(item.progress, label = "upload")
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            drawStopIndicator = {},
                        )
                    }
                }
                if (item.error != null && item.localUri != null) {
                    IconButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, "Retry upload") }
                }
                if (reorderable) Icon(Icons.Rounded.DragIndicator, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove ${item.name}") }
            }
        }
    }
}

/** Organize: long-press drag to reorder, × to delete. Remove: tap pages to mark them. */
fun LazyGridScope.pageEditor(state: ToolUiState, vm: ToolViewModel, reorder: ReorderableLazyGridState) {
    val uri = state.editorUri
    val organize = state.tool.pageEditor == PageEditor.Organize
    item(key = "editor-head", span = { GridItemSpan(maxLineSpan) }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            val text = when {
                state.editorLoading -> "Loading pages…"
                uri == null -> "Preview unavailable for this file"
                organize -> "${state.pageOrder.size} of ${state.pageCount} pages · long-press to drag"
                else -> if (state.removed.isEmpty()) "Tap the pages to remove" else "${state.removed.size} page${if (state.removed.size == 1) "" else "s"} selected"
            }
            Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            val dirty = if (organize) state.pageOrder != (1..(state.pageCount ?: 0)).toList() else state.removed.isNotEmpty()
            if (dirty) TextButton(onClick = vm::resetPages) { Text(if (organize) "Reset" else "Clear") }
        }
    }
    if (state.editorLoading) {
        item(key = "editor-loading", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
    if (uri == null) return
    if (organize) {
        items(state.pageOrder, key = { "p:$it" }) { page ->
            ReorderableItem(reorder, key = "p:$page") { dragging ->
                val haptics = LocalHapticFeedback.current
                PageCard(
                    page = page,
                    modifier = Modifier
                        .shadow(if (dragging) 8.dp else 0.dp, RoundedCornerShape(10.dp))
                        .longPressDraggableHandle(onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }),
                    content = { PdfPageThumb(uri, page - 1, 104.dp, Modifier.fillMaxSize()) },
                    badge = {
                        if (state.pageOrder.size > 1) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f))
                                    .clickable(onClickLabel = "Delete page $page") { vm.deletePage(page) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(16.dp)) }
                        }
                    },
                )
            }
        }
    } else {
        items((1..(state.pageCount ?: 0)).toList(), key = { "r:$it" }) { page ->
            val selected = page in state.removed
            PageCard(
                page = page,
                selected = selected,
                modifier = Modifier
                    .clickable(onClickLabel = if (selected) "Keep page $page" else "Remove page $page") { vm.togglePage(page) }
                    .semantics { contentDescription = "Page $page${if (selected) ", marked for removal" else ""}" },
                content = {
                    PdfPageThumb(uri, page - 1, 104.dp, Modifier.fillMaxSize())
                    if (selected) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Delete, null, tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun PageCard(
    page: Int,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    badge: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            modifier = modifier.fillMaxWidth().aspectRatio(0.72f),
        ) {
            Box {
                content()
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { badge() }
            }
        }
        Text("$page", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
