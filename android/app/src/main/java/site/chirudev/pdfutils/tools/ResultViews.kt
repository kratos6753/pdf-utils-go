package site.chirudev.pdfutils.tools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import site.chirudev.pdfutils.core.network.Job
import site.chirudev.pdfutils.ui.components.PrimaryButton
import site.chirudev.pdfutils.ui.components.SurfaceCard
import site.chirudev.pdfutils.ui.components.ToolIcon
import site.chirudev.pdfutils.ui.components.formatBytes
import site.chirudev.pdfutils.ui.components.formatDateTime
import site.chirudev.pdfutils.ui.theme.AppTheme
import kotlin.math.roundToInt

@Composable
fun ProcessingView(tool: Tool, job: Job?) {
    val progress by animateFloatAsState(maxOf(5, job?.progress ?: 5) / 100f, label = "job")
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(56.dp), strokeWidth = 5.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            if (job?.status == Job.STATUS_QUEUED) "Waiting in line…" else "${tool.title}: working on it…",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "This usually takes a few seconds. You can switch apps — we'll pick up where we left off.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).height(8.dp), drawStopIndicator = {})
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DoneView(
    tool: Tool,
    done: Phase.Done,
    signedIn: Boolean,
    busy: ResultAction?,
    onAction: (ResultAction) -> Unit,
    onRestart: () -> Unit,
    onContinue: (Tool) -> Unit,
    onSignUp: () -> Unit,
    onHistory: () -> Unit,
) {
    val out = done.output
    val saved = if (tool.api == "compress" && done.inputBytes > 0) ((1 - out.size.toDouble() / done.inputBytes) * 100).roundToInt() else null
    val kind = when {
        out.isZip -> "ZIP"
        out.isPdf -> "PDF"
        else -> "file"
    }
    val next = nextTools(tool, out.mime)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.CheckCircle, null, tint = AppTheme.extended.ok, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(12.dp))
        Text("Your file is ready!", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            "${out.name} · ${formatBytes(out.size)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )

        if (saved != null) {
            Surface(color = AppTheme.extended.okSoft, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(top = 20.dp)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("${saved.coerceAtLeast(0)}%", style = MaterialTheme.typography.headlineLarge, color = AppTheme.extended.ok)
                    Column {
                        Text(if (saved > 0) "smaller" else "already optimized", style = MaterialTheme.typography.titleSmall)
                        Text("${formatBytes(done.inputBytes)} → ${formatBytes(out.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                text = "Save $kind",
                icon = Icons.Rounded.Download,
                loading = busy == ResultAction.Save,
                enabled = busy == null,
                onClick = { onAction(ResultAction.Save) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Share", Icons.Rounded.Share, busy == ResultAction.Share, busy == null, Modifier.weight(1f)) { onAction(ResultAction.Share) }
                ActionButton("Open", Icons.AutoMirrored.Rounded.OpenInNew, busy == ResultAction.Open, busy == null, Modifier.weight(1f)) { onAction(ResultAction.Open) }
                ActionButton("Save as", Icons.Rounded.SaveAs, busy == ResultAction.SaveAs, busy == null, Modifier.weight(1f)) { onAction(ResultAction.SaveAs) }
            }
        }

        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRestart) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Start over")
            }
            if (signedIn) {
                TextButton(onClick = onHistory) {
                    Icon(Icons.Rounded.History, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("View in history")
                }
            }
        }

        if (!signedIn && out.expiresAt != null) {
            SurfaceCard(Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(top = 16.dp), onClick = onSignUp) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "Files are deleted automatically at ${formatDateTime(out.expiresAt)}. Create a free account to keep them in your history and get higher limits.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (next.isNotEmpty()) {
            Text(
                "CONTINUE TO",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 32.dp, bottom = 12.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 2,
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            ) {
                next.forEach { t ->
                    SurfaceCard(Modifier.weight(1f), onClick = { onContinue(t) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ToolIcon(t, size = 32.dp)
                            Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, loading: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(icon, null, Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
