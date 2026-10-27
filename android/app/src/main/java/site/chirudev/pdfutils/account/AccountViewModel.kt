package site.chirudev.pdfutils.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import site.chirudev.pdfutils.auth.AuthViewModel
import site.chirudev.pdfutils.core.data.AccountRepository
import site.chirudev.pdfutils.core.network.ApiError
import site.chirudev.pdfutils.core.network.Me
import javax.inject.Inject

enum class AccountAction { Name, Password, Resend, Delete, Logout }

data class AccountUiState(val busy: AccountAction? = null, val error: String? = null, val passwordChanged: Boolean = false)

@HiltViewModel
class AccountViewModel @Inject constructor(private val account: AccountRepository) : ViewModel() {
    val me: StateFlow<Me?> = account.me

    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch { account.refreshMe() }
    }

    fun updateName(name: String) = run(AccountAction.Name, "Name updated") {
        if (name.isBlank()) throw ApiError(0, "invalid", "Enter your name")
        account.updateName(name)
    }

    fun changePassword(current: String, new: String, repeat: String) = run(AccountAction.Password, "Password updated") {
        AuthViewModel.validatePassword(new)?.let { throw ApiError(0, "invalid", it) }
        if (new != repeat) throw ApiError(0, "invalid", "Passwords do not match")
        account.changePassword(current, new)
        _state.update { it.copy(passwordChanged = true) }
    }

    fun resendVerification() = run(AccountAction.Resend, "Verification email sent") { account.resendVerification() }

    fun deleteAccount(confirmation: String) = run(AccountAction.Delete, "Your account was deleted") {
        if (confirmation != "DELETE") throw ApiError(0, "invalid", "Type DELETE to confirm")
        account.deleteAccount()
    }

    fun logout() = run(AccountAction.Logout, "Signed out") { account.logout() }

    fun clearError() = _state.update { it.copy(error = null) }
    fun passwordFormShown() = _state.update { it.copy(passwordChanged = false) }

    private fun run(action: AccountAction, success: String, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = action, error = null) }
        viewModelScope.launch {
            try {
                block()
                _messages.send(success)
                _state.update { it.copy(busy = null) }
            } catch (e: ApiError) {
                _state.update { it.copy(busy = null, error = if (e.code == "invalid_credentials") "Your current password is not correct" else e.message) }
            }
        }
    }
}
