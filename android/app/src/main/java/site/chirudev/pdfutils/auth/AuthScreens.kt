package site.chirudev.pdfutils.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import site.chirudev.pdfutils.ui.components.ErrorBanner
import site.chirudev.pdfutils.ui.components.PrimaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthScaffold(title: String, subtitle: String?, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = {}, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(4.dp))
                content()
            }
        }
    }
}

@Composable
private fun EmailField(value: String, onChange: (String) -> Unit, imeAction: ImeAction = ImeAction.Next) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Email") },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
    )
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit, newPassword: Boolean, imeAction: ImeAction, onDone: () -> Unit = {}) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        supportingText = if (newPassword) { { Text("At least 8 characters") } } else null,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { focus.clearFocus(); onDone() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) "Hide password" else "Show password")
            }
        },
        modifier = Modifier.fillMaxWidth().semantics { contentType = if (newPassword) ContentType.NewPassword else ContentType.Password },
    )
}

@Composable
private fun GoogleButton(vm: AuthViewModel) {
    val config by vm.googleEnabled.collectAsStateWithLifecycle()
    if (!config.googleAuth || vm.googleClientId.isBlank()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f))
        Text("or", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f))
    }
    OutlinedButton(
        onClick = {
            scope.launch {
                requestGoogleIdToken(context, vm.googleClientId)
                    .onSuccess { token -> token?.let(vm::google) }
                    .onFailure { vm.fail(it.message) }
            }
        },
        enabled = !state.busy,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        Text("G", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(10.dp))
        Text("Continue with Google", style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun LoginScreen(onBack: () -> Unit, onDone: () -> Unit, onSignup: () -> Unit, onForgot: (String) -> Unit, vm: AuthViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.done) { if (state.done) onDone() }

    AuthScaffold("Welcome back", "Sign in to keep your files and get higher limits.", onBack) {
        EmailField(email, { email = it; vm.clearError() })
        PasswordField("Password", password, { password = it; vm.clearError() }, newPassword = false, imeAction = ImeAction.Done) { vm.login(email, password) }
        TextButton(onClick = { onForgot(email) }, modifier = Modifier.align(Alignment.End)) { Text("Forgot password?") }
        ErrorBanner(state.error)
        PrimaryButton("Sign in", onClick = { vm.login(email, password) }, loading = state.busy, modifier = Modifier.fillMaxWidth())
        GoogleButton(vm)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("New here?", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onSignup) { Text("Create a free account") }
        }
    }
}

@Composable
fun SignupScreen(onBack: () -> Unit, onDone: () -> Unit, onLogin: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    LaunchedEffect(state.done) { if (state.done) onDone() }

    AuthScaffold("Create your account", "Free forever. Keep your history and process more files.", onBack) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(100) },
            label = { Text("Name") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.PersonFullName },
        )
        EmailField(email, { email = it; vm.clearError() })
        PasswordField("Password", password, { password = it; vm.clearError() }, newPassword = true, imeAction = ImeAction.Done) { vm.signup(name, email, password) }
        ErrorBanner(state.error)
        PrimaryButton("Create account", onClick = { vm.signup(name, email, password) }, loading = state.busy, modifier = Modifier.fillMaxWidth())
        GoogleButton(vm)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("Already have an account?", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onLogin) { Text("Sign in") }
        }
    }
}

@Composable
fun ForgotPasswordScreen(initialEmail: String, onBack: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    AuthScaffold("Reset your password", if (state.done) null else "We'll email you a link to choose a new password.", onBack) {
        if (state.done) {
            Confirmation(Icons.Rounded.MarkEmailRead, "Check your inbox", "If an account exists for $email, a reset link is on its way. Open it on this phone to continue in the app.")
            PrimaryButton("Back to sign in", onClick = onBack, modifier = Modifier.fillMaxWidth())
        } else {
            EmailField(email, { email = it; vm.clearError() }, imeAction = ImeAction.Done)
            ErrorBanner(state.error)
            PrimaryButton("Send reset link", onClick = { vm.forgot(email) }, loading = state.busy, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ResetPasswordScreen(token: String, onBack: () -> Unit, onLogin: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var password by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    AuthScaffold("Choose a new password", null, onBack) {
        if (state.done) {
            Confirmation(Icons.Rounded.CheckCircle, "Password changed", "You've been signed out everywhere. Sign in with your new password.")
            PrimaryButton("Sign in", onClick = onLogin, modifier = Modifier.fillMaxWidth())
        } else {
            PasswordField("New password", password, { password = it; vm.clearError() }, newPassword = true, imeAction = ImeAction.Next)
            PasswordField("Repeat password", repeat, { repeat = it; vm.clearError() }, newPassword = true, imeAction = ImeAction.Done) { vm.reset(token, password, repeat) }
            ErrorBanner(state.error)
            PrimaryButton("Change password", onClick = { vm.reset(token, password, repeat) }, loading = state.busy, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun VerifyEmailScreen(token: String, onContinue: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(token) { vm.verify(token) }
    AuthScaffold("Verify email", null, onContinue) {
        when {
            state.done -> {
                Confirmation(Icons.Rounded.CheckCircle, "Email verified", "Thanks! Your email address is confirmed.")
                PrimaryButton("Continue", onClick = onContinue, modifier = Modifier.fillMaxWidth())
            }
            state.error != null -> {
                ErrorBanner(state.error)
                OutlinedButton(onClick = { vm.verify(token) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text("Try again") }
            }
            else -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun Confirmation(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
