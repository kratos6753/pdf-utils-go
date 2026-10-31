package site.chirudev.pdfutils.history

import android.content.ActivityNotFoundException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.chirudev.pdfutils.core.network.FileMeta
import site.chirudev.pdfutils.tools.Tool
import site.chirudev.pdfutils.tools.toolsFor
import site.chirudev.pdfutils.ui.components.EmptyState
import site.chirudev.pdfutils.ui.components.ErrorBanner
import site.chirudev.pdfutils.ui.components.FileThumb
import site.chirudev.pdfutils.ui.components.PrimaryButton
import site.chirudev.pdfutils.ui.components.SurfaceCard
import site.chirudev.pdfutils.ui.components.ToolIcon
import site.chirudev.pdfutils.ui.components.formatBytes
import site.chirudev.pdfutils.ui.components.formatDateTime
import site.chirudev.pdfutils.ui.components.readableWidth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    contentPadding: PaddingValues,
    snackbar: SnackbarHostState,
    onLogin: () -> Unit,
    onOpenWith: (Tool, FileMeta) -> Unit,
    vm: HistoryViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf<FileMeta?>(null) }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is HistoryEvent.Launch -> try { context.startActivity(e.intent) } catch (_: ActivityNotFoundException) { snackbar.showSnackbar("No app can open this file") }
                is HistoryEvent.Message -> snackbar.showSnackbar(e.text)
            }
        }
    }

    Column(Modifier.readableWidth().padding(top = contentPadding.calculateTopPadding())) {
        Text("History", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
        if (!state.signedIn) {
            EmptyState(
                Icons.Rounded.History,
                "Keep your files",
                "Sign in to see everything you've processed, download it again, and get higher limits.",
                modifier = Modifier.padding(top = 48.dp),
            ) { PrimaryButton("Sign in", onClick = onLogin, modifier = Modifier.padding(top = 12.dp)) }
            return@Column
        }

        state.storage?.takeIf { it.limitBytes > 0 }?.let { s ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("${formatBytes(s.usedBytes)} of ${formatBytes(s.limitBytes)} used", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { (s.usedBytes.toFloat() / s.limitBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
            }
        }

        SecondaryTabRow(selectedTabIndex = state.filter.ordinal, containerColor = MaterialTheme.colorScheme.background) {
            HistoryFilter.entries.forEach { f ->
                Tab(selected = state.filter == f, onClick = { vm.setFilter(f) }, text = { Text(f.label) })
            }
        }

        val list = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 5 } == true } }
        LaunchedEffect(nearEnd, state.nextCursor) { if (nearEnd && state.nextCursor != null) vm.load(reset = false) }

        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "error") { ErrorBanner(state.error) }
                if (state.loading && state.files.isEmpty()) {
                    item(key = "loading") { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                } else if (state.loaded && state.files.isEmpty() && state.error == null) {
                    item(key = "empty") { EmptyState(Icons.Rounded.History, "Nothing here yet", "Files you upload and the results you create will show up here.") }
                }
                items(state.files, key = { it.id }) { file ->
                    HistoryRow(
                        file = file,
                        busy = state.busyId == file.id,
                        onAction = { vm.act(file, it) },
                        onDelete = { confirmDelete = file },
                        onOpenWith = { onOpenWith(it, file) },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (state.loadingMore) {
                    item(key = "more") { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
                }
            }
        }
    }

    confirmDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text("Delete file?") },
            text = { Text("“${file.name}” will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { vm.delete(file); confirmDelete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryRow(
    file: FileMeta,
    busy: Boolean,
    onAction: (FileAction) -> Unit,
    onDelete: () -> Unit,
    onOpenWith: (Tool) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val chainable = remember(file.mime) { toolsFor(file.mime) }
    SurfaceCard(modifier.fillMaxWidth()) {
        Row(Modifier.clickable { onAction(FileAction.Open) }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FileThumb(null, file.mime, locked = false, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(
                    "${formatBytes(file.size)} · ${if (file.kind == "output") "Result" else "Upload"} · ${formatDateTime(file.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (busy) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.5.dp)
            } else {
                IconButton(onClick = { onAction(FileAction.Save) }) { Icon(Icons.Rounded.Download, "Save ${file.name}") }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) }, onClick = { menu = false; onAction(FileAction.Open) })
                    DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { menu = false; onAction(FileAction.Share) })
                    if (chainable.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Open with…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        chainable.forEach { t ->
                            DropdownMenuItem(text = { Text(t.title) }, leadingIcon = { ToolIcon(t, size = 24.dp) }, onClick = { menu = false; onOpenWith(t) })
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}
