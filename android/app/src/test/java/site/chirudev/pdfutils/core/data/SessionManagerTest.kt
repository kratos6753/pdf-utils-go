package site.chirudev.pdfutils.core.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import site.chirudev.pdfutils.core.network.ApiError
import site.chirudev.pdfutils.core.network.AuthRefreshService
import site.chirudev.pdfutils.core.network.RefreshRequest
import site.chirudev.pdfutils.core.network.Session
import site.chirudev.pdfutils.testing.FakeSecureStore
import site.chirudev.pdfutils.testing.session
import java.io.IOException

class SessionManagerTest {
    private class FakeRefresh(var result: () -> Session) : AuthRefreshService {
        var calls = 0
        val tokensSeen = mutableListOf<String>()
        override suspend fun refresh(body: RefreshRequest): Session {
            calls++
            tokensSeen += body.refreshToken
            delay(50) // let concurrent callers pile up
            return result()
        }
    }

    @Test fun `restore trades the stored refresh token for a session`() = runTest {
        val store = FakeSecureStore("r1")
        val refresh = FakeRefresh { session("a2", "r2") }
        val sm = SessionManager(store, refresh)

        sm.restore()

        assertEquals("a2", sm.accessToken)
        assertEquals("r2", store.token)
        assertEquals("u1", sm.user.value?.id)
    }

    @Test fun `restore without a stored token stays anonymous`() = runTest {
        val refresh = FakeRefresh { error("must not be called") }
        val sm = SessionManager(FakeSecureStore(null), refresh)
        sm.restore()
        assertNull(sm.accessToken)
        assertEquals(0, refresh.calls)
    }

    @Test fun `concurrent refreshes hit the network once (rotated tokens must not be replayed)`() = runTest {
        val store = FakeSecureStore()
        val refresh = FakeRefresh { session("a2", "r2") }
        val sm = SessionManager(store, refresh)
        sm.onSignedIn(session("a1", "r1"))

        val results = List(5) { async { sm.refresh(staleAccessToken = "a1") } }.awaitAll()

        assertEquals(1, refresh.calls)
        assertEquals(listOf("r1"), refresh.tokensSeen)
        assertEquals(List(5) { "a2" }, results)
    }

    @Test fun `a rejected refresh token signs the user out`() = runTest {
        val store = FakeSecureStore()
        val sm = SessionManager(store, FakeRefresh { throw ApiError(401, "invalid_refresh_token", "expired") })
        sm.onSignedIn(session("a1", "r1"))

        assertNull(sm.refresh(staleAccessToken = "a1"))
        assertNull(sm.accessToken)
        assertNull(sm.user.value)
        assertNull(store.token)
    }

    @Test fun `a network failure keeps the stored session for later`() = runTest {
        val store = FakeSecureStore()
        val sm = SessionManager(store, FakeRefresh { throw IOException("offline") })
        sm.onSignedIn(session("a1", "r1"))

        assertNull(sm.refresh(staleAccessToken = "a1"))
        assertEquals("r1", store.token)
        assertEquals("u1", sm.user.value?.id)
    }

    @Test fun `sign out clears local state and hands back the token to revoke`() = runTest {
        val store = FakeSecureStore()
        val sm = SessionManager(store, FakeRefresh { error("unused") })
        sm.onSignedIn(session("a1", "r1"))

        assertEquals("r1", sm.signOut())
        assertNull(sm.accessToken)
        assertNull(store.token)
    }
}
