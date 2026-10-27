package site.chirudev.pdfutils.history

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import site.chirudev.pdfutils.core.data.AccountRepository
import site.chirudev.pdfutils.core.data.PdfRepository
import site.chirudev.pdfutils.core.files.ResultFiles
import site.chirudev.pdfutils.core.network.ApiError
import site.chirudev.pdfutils.core.network.FilePage
import site.chirudev.pdfutils.core.network.Me
import site.chirudev.pdfutils.core.network.User
import site.chirudev.pdfutils.testing.MainDispatcherRule
import site.chirudev.pdfutils.testing.fileMeta
import site.chirudev.pdfutils.testing.testUser

class HistoryViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = mockk<PdfRepository>()
    private val user = MutableStateFlow<User?>(testUser)
    private val account = mockk<AccountRepository>(relaxed = true) {
        every { me } returns MutableStateFlow(Me(user = testUser, plan = "free"))
        every { this@mockk.user } returns this@HistoryViewModelTest.user
    }
    private val results = mockk<ResultFiles>(relaxed = true)

    @Test fun `loads the first page and appends the next`() {
        coEvery { repo.myFiles(null, null) } returns FilePage(listOf(fileMeta("a"), fileMeta("b")), nextCursor = "c1")
        coEvery { repo.myFiles(null, "c1") } returns FilePage(listOf(fileMeta("b"), fileMeta("c")), nextCursor = null)

        val vm = HistoryViewModel(repo, account, results)
        assertEquals(listOf("a", "b"), vm.state.value.files.map { it.id })

        vm.load(reset = false)
        assertEquals(listOf("a", "b", "c"), vm.state.value.files.map { it.id })

        vm.load(reset = false) // no cursor left: no request
        coVerify(exactly = 2) { repo.myFiles(any(), any()) }
    }

    @Test fun `filter switches the kind and reloads`() {
        coEvery { repo.myFiles(any(), any()) } returns FilePage(emptyList())
        val vm = HistoryViewModel(repo, account, results)
        vm.setFilter(HistoryFilter.Results)
        coVerify { repo.myFiles("output", null) }
        assertTrue(vm.state.value.loaded)
    }

    @Test fun `delete is optimistic and rolls back on failure`() {
        coEvery { repo.myFiles(any(), any()) } returns FilePage(listOf(fileMeta("a"), fileMeta("b")))
        coEvery { repo.delete("a") } throws ApiError(500, "internal_error", "boom")
        val vm = HistoryViewModel(repo, account, results)

        vm.delete(fileMeta("a"))

        assertEquals(listOf("a", "b"), vm.state.value.files.map { it.id })
    }

    @Test fun `successful delete removes the row`() {
        coEvery { repo.myFiles(any(), any()) } returns FilePage(listOf(fileMeta("a"), fileMeta("b")))
        coEvery { repo.delete("a") } returns Unit
        val vm = HistoryViewModel(repo, account, results)

        vm.delete(fileMeta("a"))

        assertEquals(listOf("b"), vm.state.value.files.map { it.id })
    }

    @Test fun `signing out clears the list`() {
        coEvery { repo.myFiles(any(), any()) } returns FilePage(listOf(fileMeta("a")))
        val vm = HistoryViewModel(repo, account, results)
        user.value = null
        assertTrue(vm.state.value.files.isEmpty())
    }
}
