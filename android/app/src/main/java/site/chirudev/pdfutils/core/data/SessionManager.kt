package site.chirudev.pdfutils.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import site.chirudev.pdfutils.core.network.ApiError
import site.chirudev.pdfutils.core.network.AuthRefreshService
import site.chirudev.pdfutils.core.network.RefreshRequest
import site.chirudev.pdfutils.core.network.Session
import site.chirudev.pdfutils.core.network.User
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the signed-in state. The access token lives in memory only; the refresh token is
 * encrypted at rest. Refreshes are single-flight: the server rotates refresh tokens and
 * treats a replayed one as theft, so two concurrent refreshes would sign the user out.
 */
@Singleton
class SessionManager @Inject constructor(
    private val store: SecureStore,
    private val refreshService: AuthRefreshService,
) {
    private val mutex = Mutex()

    @Volatile var accessToken: String? = null
        private set

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    suspend fun deviceId(): String = store.deviceId()

    /** Called once at startup: trades a stored refresh token for a session. */
    suspend fun restore() {
        if (store.refreshToken() != null) refresh(staleAccessToken = null)
    }

    suspend fun onSignedIn(session: Session) = mutex.withLock { apply(session) }

    fun updateUser(user: User?) {
        if (user != null && accessToken != null) _user.value = user
    }

    /**
     * Gets a fresh access token. [staleAccessToken] is the token a request failed with; if another
     * caller already replaced it, that newer token is returned without hitting the network.
     */
    suspend fun refresh(staleAccessToken: String?): String? = mutex.withLock {
        val current = accessToken
        if (current != null && current != staleAccessToken) return current
        val token = store.refreshToken() ?: return clearLocked()
        try {
            apply(refreshService.refresh(RefreshRequest(token)))
            accessToken
        } catch (t: Throwable) {
            val err = ApiError.from(t)
            // Only an explicit rejection ends the session; network blips keep it for next time.
            if (err.status in 400..499) clearLocked() else null
        }
    }

    /** Clears local state; returns the refresh token so the caller can revoke it server-side. */
    suspend fun signOut(): String? = mutex.withLock {
        val token = store.refreshToken()
        clearLocked()
        token
    }

    private suspend fun apply(session: Session) {
        session.refreshToken?.let { store.setRefreshToken(it) }
        accessToken = session.accessToken
        _user.value = session.user
    }

    private suspend fun clearLocked(): String? {
        store.setRefreshToken(null)
        accessToken = null
        _user.value = null
        return null
    }
}
