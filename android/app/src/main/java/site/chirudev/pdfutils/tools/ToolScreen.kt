package site.chirudev.pdfutils.tools

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.calvin.reorderable.rememberReorderableLazyGridState
import site.chirudev.pdfutils.core.files.CreateTypedDocument
import site.chirudev.pdfutils.ui.components.ErrorBanner
import site.chirudev.pdfutils.ui.components.LimitSheet
import site.chirudev.pdfutils.ui.components.PrimaryButton
import site.chirudev.pdfutils.ui.components.readableWidth
import site.chirudev.pdfutils.ui.components.ToolIcon
import site.chirudev.pdfutils.ui.theme.AppTheme

data class ToolNav(
    val back: () -> Unit,
    val openTool: (slug: String, fileId: String) -> Unit,
    val signUp: () -> Unit,
    val login: () -> Unit,
    val history: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScreen(nav: ToolNav, vm: ToolViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tool = state.tool
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val pdfMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addUris(it) }
    val pdfOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.addUris(listOf(it)) } }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addUris(it) }
    val createDoc = rememberLauncherForActivityResult(CreateTypedDocument()) { vm.onDocumentCreated(it) }

    val pickFiles: () -> Unit = {
        when {
            tool.accepts == Accepts.Image -> pdfMany.launch(Accepts.Image.mimes.toTypedArray())
            tool.multiple -> pdfMany.launch(arrayOf("application/pdf"))
            else -> pdfOne.launch(arrayOf("application/pdf"))
        }
    }
    val pickPhotos: () -> Unit = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is ToolEvent.Launch -> try {
                    context.startActivity(e.intent)
                } catch (_: ActivityNotFoundException) {
                    snackbar.showSnackbar("No app on this device can open that file")
                }
                is ToolEvent.CreateDocument -> createDoc.launch(e.name to e.mime)
                is ToolEvent.Message -> snackbar.showSnackbar(e.text)
            }
        }
    }

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(tool.title) },
                navigationIcon = { IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.phase == Phase.Editing && state.files.isNotEmpty()) {
                Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).readableWidth(fillHeight = false).padding(16.dp)) {
                        val waiting = state.files.count { it.uploading }
                        PrimaryButton(
                            text = if (waiting > 0) "Uploading $waiting file${if (waiting > 1) "s" else ""}…" else tool.verb,
                            onClick = vm::submit,
                            enabled = state.canSubmit,
                            loading = state.submitting,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = state.phase::class,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "phase",
            modifier = Modifier.padding(padding).readableWidth(),
        ) { phase ->
            when (phase) {
                Phase.Editing::class -> EditingContent(state, vm, pickFiles, pickPhotos)
                Phase.Processing::class -> ProcessingView(tool, (state.phase as? Phase.Processing)?.job)
                else -> (state.phase as? Phase.Done)?.let { done ->
                    DoneView(
                        tool = tool,
                        done = done,
                        signedIn = state.signedIn,
                        busy = state.busyAction,
                        onAction = vm::onResultAction,
                        onRestart = vm::restart,
                        onContinue = { next -> nav.openTool(next.slug, done.output.id) },
                        onSignUp = nav.signUp,
                        onHistory = nav.history,
                    )
                }
            }
        }
    }

    state.limitError?.let { err ->
        LimitSheet(err, state.signedIn, onDismiss = vm::dismissLimit, onSignUp = { vm.dismissLimit(); nav.signUp() }, onLogin = { vm.dismissLimit(); nav.login() })
    }
}

private fun LazyGridScope.fullLine(key: String? = null, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun EditingContent(state: ToolUiState, vm: ToolViewModel, pickFiles: () -> Unit, pickPhotos: () -> Unit) {
    val tool = state.tool
    val grid = rememberLazyGridState()
    val reorder = rememberReorderableLazyGridState(grid) { from, to ->
        val f = from.key as? String ?: return@rememberReorderableLazyGridState
        val t = to.key as? String ?: return@rememberReorderableLazyGridState
        when {
            f.startsWith("f:") && t.startsWith("f:") -> {
                val files = vm.state.value.files
                vm.move(files.indexOfFirst { "f:${it.key}" == f }, files.indexOfFirst { "f:${it.key}" == t })
            }
            f.startsWith("p:") && t.startsWith("p:") -> {
                val pages = vm.state.value.pageOrder
                vm.movePage(pages.indexOf(f.removePrefix("p:").toInt()), pages.indexOf(t.removePrefix("p:").toInt()))
            }
        }
    }
    val showEditor = tool.pageEditor != null && state.files.isNotEmpty()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (showEditor) 104.dp else 600.dp),
        state = grid,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        fullLine("header") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ToolIcon(tool, size = 52.dp)
                Text(tool.description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
        state.tasksLeft?.let { left ->
            fullLine("quota") {
                Surface(color = AppTheme.extended.warnSoft, shape = MaterialTheme.shapes.small) {
                    Text(
                        if (left == 0) "No tasks left this hour" else "$left task${if (left == 1) "" else "s"} left this hour",
                        color = AppTheme.extended.warn,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        if (state.files.isEmpty()) {
            fullLine("pick") { PickCard(tool, pickFiles, pickPhotos) }
        } else {
            items(state.files, key = { "f:${it.key}" }, span = { GridItemSpan(maxLineSpan) }) { item ->
                FileRow(
                    item = item,
                    reorderState = reorder,
                    reorderable = tool.multiple && state.files.size > 1,
                    onRemove = { vm.remove(item.key) },
                    onRetry = { vm.retry(item.key) },
                )
            }
            if (tool.multiple) {
                fullLine("add") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = pickFiles, shape = MaterialTheme.shapes.small) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Add files")
                        }
                        if (tool.accepts == Accepts.Image) {
                            OutlinedButton(onClick = pickPhotos, shape = MaterialTheme.shapes.small) {
                                Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Add photos")
                            }
                        }
                    }
                }
                if (state.files.size > 1) {
                    fullLine("order-hint") {
                        Text(
                            if (tool.api == "merge") "Files are merged top to bottom. Long-press and drag to reorder." else "Pages follow this order. Long-press and drag to reorder.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (showEditor) pageEditor(state, vm, reorder)

        if (state.files.isNotEmpty() && tool.fields.isNotEmpty()) {
            fullLine("options") {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 8.dp)) {
                    tool.fields.filter { it.showIf(state.values) }.forEach { f ->
                        OptionField(f, state.values, state.confirm, vm::setValue, vm::setConfirm)
                    }
                }
            }
        }
        fullLine("error") { ErrorBanner(state.error) }
    }
}

@Composable
private fun PickCard(tool: Tool, pickFiles: () -> Unit, pickPhotos: () -> Unit) {
    val noun = if (tool.accepts == Accepts.Image) "images" else if (tool.multiple) "PDF files" else "PDF file"
    Surface(
        onClick = if (tool.accepts == Accepts.Image) pickPhotos else pickFiles,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(vertical = 36.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp)) {
                Icon(
                    if (tool.accepts == Accepts.Image) Icons.Rounded.PhotoLibrary else Icons.Rounded.UploadFile,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Select $noun", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "or share them to PDF Utils from any app",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (tool.accepts == Accepts.Image) {
                TextButton(onClick = pickFiles, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Browse files instead")
                }
            }
        }
    }
}
