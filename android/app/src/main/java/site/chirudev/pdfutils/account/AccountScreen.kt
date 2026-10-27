package site.chirudev.pdfutils.account

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.chirudev.pdfutils.BuildConfig
import site.chirudev.pdfutils.core.network.Me
import site.chirudev.pdfutils.ui.components.ErrorBanner
import site.chirudev.pdfutils.ui.components.PrimaryButton
import site.chirudev.pdfutils.ui.components.SurfaceCard
import site.chirudev.pdfutils.ui.components.formatBytes
import site.chirudev.pdfutils.ui.components.formatTime
import site.chirudev.pdfutils.ui.theme.AppTheme

@Composable
fun AccountScreen(contentPadding: PaddingValues, snackbar: SnackbarHostState, onLogin: () -> Unit, onSignup: () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val me by vm.me.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Account", style = MaterialTheme.typography.headlineMedium)
        val user = me?.user
        if (user == null) {
            SurfaceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("You're using PDF Utils without an account", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Files are deleted after a couple of hours. A free account keeps your history and raises your limits.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrimaryButton("Create a free account", onClick = onSignup, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onLogin, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text("Sign in") }
                }
            }
            me?.let { PlanCard(it) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    val initial = (user.name.ifBlank { user.email }).first().uppercaseChar()
                    Text("$initial", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(user.name.ifBlank { "Your account" }, style = MaterialTheme.typography.titleMedium)
                    Text(user.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!user.emailVerified) {
                Surface(color = AppTheme.extended.warnSoft, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.MarkEmailUnread, null, tint = AppTheme.extended.warn)
                        Text("Please verify your email address.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::resendVerification, enabled = state.busy == null) { Text("Resend") }
                    }
                }
            }
            me?.let { PlanCard(it) }
            ErrorBanner(state.error)
            NameCard(user.name, state.busy == AccountAction.Name, vm::updateName)
            PasswordCard(user.hasPassword, state.busy == AccountAction.Password, state.passwordChanged, vm::changePassword, vm::passwordFormShown)
            OutlinedButton(onClick = vm::logout, enabled = state.busy == null, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Sign out")
            }
            DeleteCard(state.busy == AccountAction.Delete, vm::deleteAccount)
        }
        Text(
            "PDF Utils ${BuildConfig.VERSION_NAME} · chirudev.site",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun PlanCard(me: Me) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Plan", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        if (me.plan == "anon") "Guest" else me.plan.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            me.tasks?.let { t ->
                Usage("Tasks this hour", "${(t.limit - t.remaining).coerceAtLeast(0)} of ${t.limit}", if (t.limit > 0) 1f - t.remaining.toFloat() / t.limit else 0f)
                if (t.resetAt != null && t.remaining < t.limit) {
                    Text("Resets at ${formatTime(t.resetAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            me.storage?.takeIf { it.limitBytes > 0 }?.let { s ->
                Usage("Storage", "${formatBytes(s.usedBytes)} of ${formatBytes(s.limitBytes)}", s.usedBytes.toFloat() / s.limitBytes)
            }
            Text(
                "Up to ${me.limits.maxFileMb} MB per file · ${me.limits.maxFilesPerTask} files per task · ${me.limits.tasksPerHour} tasks per hour",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Usage(label: String, value: String, fraction: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
    }
}

@Composable
private fun NameCard(current: String, busy: Boolean, onSave: (String) -> Unit) {
    var name by rememberSaveable(current) { mutableStateOf(current) }
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Profile", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(100) },
                label = { Text("Name") },
                leadingIcon = { Icon(Icons.Rounded.Person, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton("Save", onClick = { onSave(name) }, enabled = name.trim() != current && name.isNotBlank(), loading = busy)
        }
    }
}

@Composable
private fun PasswordCard(hasPassword: Boolean, busy: Boolean, changed: Boolean, onSave: (String, String, String) -> Unit, onReset: () -> Unit) {
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(changed) {
        if (changed) { current = ""; new = ""; repeat = ""; onReset() }
    }
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (hasPassword) "Change password" else "Set a password", style = MaterialTheme.typography.titleMedium)
            if (!hasPassword) {
                Text("You sign in with Google. Add a password to also sign in with your email.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (hasPassword) Secret("Current password", current) { current = it }
            Secret("New password", new) { new = it }
            Secret("Repeat new password", repeat) { repeat = it }
            PrimaryButton(if (hasPassword) "Update password" else "Set password", onClick = { onSave(current, new, repeat) }, enabled = new.isNotEmpty(), loading = busy)
        }
    }
}

@Composable
private fun Secret(label: String, value: String, onChange: (String) -> Unit) = OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label) },
    singleLine = true,
    shape = MaterialTheme.shapes.small,
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun DeleteCard(busy: Boolean, onDelete: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Delete account", color = MaterialTheme.colorScheme.error)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false; typed = "" },
            title = { Text("Delete your account?") },
            text = {
                Column(Modifier.widthIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This permanently deletes your account and all of your files. It can't be undone.")
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, label = { Text("Type DELETE to confirm") }, singleLine = true, shape = MaterialTheme.shapes.small)
                }
            },
            confirmButton = {
                TextButton(onClick = { onDelete(typed); open = false; typed = "" }, enabled = typed == "DELETE" && !busy) {
                    Text("Delete forever", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { open = false; typed = "" }) { Text("Cancel") } },
        )
    }
    Spacer(Modifier.height(8.dp))
}
