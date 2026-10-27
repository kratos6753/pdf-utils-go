package site.chirudev.pdfutils.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import site.chirudev.pdfutils.core.network.ApiService
import site.chirudev.pdfutils.core.network.AppConfig
import site.chirudev.pdfutils.core.network.ChangePasswordRequest
import site.chirudev.pdfutils.core.network.EmailRequest
import site.chirudev.pdfutils.core.network.GoogleTokenRequest
import site.chirudev.pdfutils.core.network.LoginRequest
import site.chirudev.pdfutils.core.network.Me
import site.chirudev.pdfutils.core.network.RefreshRequest
import site.chirudev.pdfutils.core.network.ResetPasswordRequest
import site.chirudev.pdfutils.core.network.SignupRequest
import site.chirudev.pdfutils.core.network.TokenRequest
import site.chirudev.pdfutils.core.network.UpdateMeRequest
import site.chirudev.pdfutils.core.network.User
import site.chirudev.pdfutils.core.network.apiCall
import site.chirudev.pdfutils.core.network.orThrow
import javax.inject.Inject
import javax.inject.Singleton

/** Auth, profile and plan/usage. `me` is cached and refreshed after anything that changes it. */
@Singleton
class AccountRepository @Inject constructor(
    private val api: ApiService,
    private val session: SessionManager,
) {
    val user: StateFlow<User?> = session.user

    private val _me = MutableStateFlow<Me?>(null)
    val me: StateFlow<Me?> = _me.asStateFlow()

    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    suspend fun refreshMe(): Me? = runCatching { apiCall { api.me() } }.getOrNull()?.also {
        _me.value = it
        session.updateUser(it.user)
    }

    suspend fun loadConfig() {
        runCatching { apiCall { api.config() } }.onSuccess { _config.value = it }
    }

    suspend fun login(email: String, password: String) = signedIn { api.login(LoginRequest(email.trim(), password)) }
    suspend fun signup(email: String, password: String, name: String) = signedIn { api.signup(SignupRequest(email.trim(), password, name.trim())) }
    suspend fun google(idToken: String) = signedIn { api.googleToken(GoogleTokenRequest(idToken)) }

    private suspend inline fun signedIn(crossinline call: suspend () -> site.chirudev.pdfutils.core.network.Session) {
        session.onSignedIn(apiCall { call() })
        refreshMe()
    }

    suspend fun logout() {
        val token = session.signOut()
        if (token != null) runCatching { api.logout(RefreshRequest(token)) }
        refreshMe()
    }

    suspend fun forgotPassword(email: String) { apiCall { api.forgotPassword(EmailRequest(email.trim())).orThrow() } }
    suspend fun resetPassword(token: String, password: String) { apiCall { api.resetPassword(ResetPasswordRequest(token, password)).orThrow() } }
    suspend fun verifyEmail(token: String) {
        apiCall { api.verifyEmail(TokenRequest(token)).orThrow() }
        refreshMe()
    }

    suspend fun updateName(name: String) {
        apiCall { api.updateMe(UpdateMeRequest(name.trim())).orThrow() }
        refreshMe()
    }

    suspend fun changePassword(current: String?, new: String) {
        apiCall { api.changePassword(ChangePasswordRequest(current?.takeIf { it.isNotEmpty() }, new)).orThrow() }
        refreshMe()
    }

    suspend fun resendVerification() { apiCall { api.resendVerification().orThrow() } }

    suspend fun deleteAccount() {
        apiCall { api.deleteMe().orThrow() }
        session.signOut()
        refreshMe()
    }
}
