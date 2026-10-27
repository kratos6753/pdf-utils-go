package site.chirudev.pdfutils.auth

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import site.chirudev.pdfutils.BuildConfig
import site.chirudev.pdfutils.core.data.AccountRepository
import site.chirudev.pdfutils.core.network.ApiError
import javax.inject.Inject

data class AuthUiState(
    val busy: Boolean = false,
    val error: String? = null,
    /** Set once the action succeeded (signed in, email sent, password reset, verified…). */
    val done: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(private val account: AccountRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    val googleEnabled = account.config

    val googleClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

    init {
        viewModelScope.launch { account.loadConfig() }
    }

    fun login(email: String, password: String) {
        validateEmail(email)?.let { return fail(it) }
        if (password.isEmpty()) return fail("Enter your password")
        run { account.login(email, password) }
    }

    fun signup(name: String, email: String, password: String) {
        validateEmail(email)?.let { return fail(it) }
        validatePassword(password)?.let { return fail(it) }
        run { account.signup(email, password, name) }
    }

    fun forgot(email: String) {
        validateEmail(email)?.let { return fail(it) }
        run { account.forgotPassword(email) }
    }

    fun reset(token: String, password: String, repeat: String) {
        validatePassword(password)?.let { return fail(it) }
        if (password != repeat) return fail("Passwords do not match")
        run { account.resetPassword(token, password) }
    }

    fun verify(token: String) = run { account.verifyEmail(token) }

    fun google(idToken: String) = run { account.google(idToken) }

    fun fail(message: String?) = _state.update { it.copy(error = message, busy = false) }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(busy = false, done = true) }
            } catch (e: ApiError) {
                _state.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    private fun friendly(e: ApiError) = when (e.code) {
        "invalid_credentials" -> "Wrong email or password"
        "email_taken" -> "An account with this email already exists. Try signing in."
        "weak_password" -> e.message
        "link_expired" -> "This link has expired. Request a new one."
        else -> e.message
    }

    companion object {
        fun validateEmail(email: String): String? = when {
            email.isBlank() -> "Enter your email"
            !isEmail(email.trim()) -> "Enter a valid email address"
            else -> null
        }

        fun validatePassword(password: String): String? = when {
            password.length < 8 -> "Use at least 8 characters for your password"
            password.length > 128 -> "That password is too long"
            else -> null
        }

        private val emailRe = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private fun isEmail(s: String) = runCatching { Patterns.EMAIL_ADDRESS.matcher(s).matches() }.getOrNull() ?: emailRe.matches(s)
    }
}
