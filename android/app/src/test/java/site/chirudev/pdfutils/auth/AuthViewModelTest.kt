package site.chirudev.pdfutils.auth

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import site.chirudev.pdfutils.core.data.AccountRepository
import site.chirudev.pdfutils.core.network.ApiError
import site.chirudev.pdfutils.core.network.AppConfig
import site.chirudev.pdfutils.testing.MainDispatcherRule

class AuthViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val account = mockk<AccountRepository>(relaxed = true) {
        every { config } returns MutableStateFlow(AppConfig(googleAuth = true))
    }

    @Test fun `validates email and password before calling the server`() {
        val vm = AuthViewModel(account)
        vm.login("not-an-email", "x")
        assertEquals("Enter a valid email address", vm.state.value.error)
        vm.signup("Ada", "ada@example.com", "short")
        assertEquals("Use at least 8 characters for your password", vm.state.value.error)
        coVerify(exactly = 0) { account.login(any(), any()) }
        coVerify(exactly = 0) { account.signup(any(), any(), any()) }
    }

    @Test fun `successful login marks the flow done`() {
        val vm = AuthViewModel(account)
        vm.login("ada@example.com", "correct horse")
        coVerify { account.login("ada@example.com", "correct horse") }
        assertTrue(vm.state.value.done)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
    }

    @Test fun `server errors become friendly messages`() {
        coEvery { account.login(any(), any()) } throws ApiError(401, "invalid_credentials", "invalid credentials")
        val vm = AuthViewModel(account)
        vm.login("ada@example.com", "wrong-password")
        assertEquals("Wrong email or password", vm.state.value.error)
        assertFalse(vm.state.value.done)
    }

    @Test fun `reset requires matching passwords`() {
        val vm = AuthViewModel(account)
        vm.reset("tok", "longenough1", "longenough2")
        assertEquals("Passwords do not match", vm.state.value.error)
        vm.reset("tok", "longenough1", "longenough1")
        coVerify { account.resetPassword("tok", "longenough1") }
        assertTrue(vm.state.value.done)
    }
}
