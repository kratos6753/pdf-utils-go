package site.chirudev.pdfutils.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import site.chirudev.pdfutils.core.network.ApiError

/** Shown when a plan limit stops the user, nudging toward an account (web: LimitNotice). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitSheet(error: ApiError, signedIn: Boolean, onDismiss: () -> Unit, onSignUp: () -> Unit, onLogin: () -> Unit) {
    val title = when (error.code) {
        "limit_reached", "rate_limited" -> if (signedIn) "You've reached your hourly limit" else "You've reached the free limit"
        "file_too_large" -> "That file is too large"
        "quota_exceeded" -> "Your storage is full"
        else -> "Limit reached"
    }
    val reset = formatTime(error.detailString("reset_at"))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(error.message.trimEnd('.') + ".", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (reset.isNotEmpty()) {
                Text("Your allowance resets at $reset.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!signedIn) {
                    PrimaryButton("Sign up free for higher limits", onClick = onSignUp, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onLogin, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text("I already have an account") }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}
