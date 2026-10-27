package site.chirudev.pdfutils.tools

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import site.chirudev.pdfutils.core.data.AccountRepository
import site.chirudev.pdfutils.core.data.IncomingFiles
import site.chirudev.pdfutils.core.data.LocalFile
import site.chirudev.pdfutils.core.data.PdfRepository
import site.chirudev.pdfutils.core.files.ResultFiles
import site.chirudev.pdfutils.core.network.ApiError
import site.chirudev.pdfutils.core.network.Job
import site.chirudev.pdfutils.core.network.JobError
import site.chirudev.pdfutils.core.network.Me
import site.chirudev.pdfutils.core.pdf.PdfInfo
import site.chirudev.pdfutils.core.pdf.PdfThumbnailer
import site.chirudev.pdfutils.testing.MainDispatcherRule
import site.chirudev.pdfutils.testing.fileMeta
import site.chirudev.pdfutils.testing.job

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = mockk<PdfRepository>()
    private val account = mockk<AccountRepository>(relaxed = true) {
        every { me } returns MutableStateFlow(Me())
    }
    private val thumbnailer = mockk<PdfThumbnailer> { coEvery { info(any()) } returns PdfInfo.Ok(3) }
    private val results = mockk<ResultFiles>(relaxed = true)

    private val a = Uri.parse("content://test/a.pdf")
    private val b = Uri.parse("content://test/b.pdf")

    private fun vm(slug: String) = ToolViewModel(
        SavedStateHandle(mapOf("slug" to slug, "incoming" to false)),
        repo, account, thumbnailer, results, IncomingFiles(),
    ).also { it.io = main.dispatcher }

    private fun stubUpload(uri: Uri, id: String, mime: String = "application/pdf", size: Long = 1000) {
        every { repo.describe(uri) } returns LocalFile(uri, "$id.pdf", size, mime)
        coEvery { repo.upload(match { it.uri == uri }, any()) } answers {
            secondArg<(Float) -> Unit>()(1f)
            fileMeta(id, size = size)
        }
    }

    @Test fun `merge needs at least two files`() {
        stubUpload(a, "f1")
        val vm = vm("merge-pdf")
        vm.addUris(listOf(a))
        assertTrue(vm.state.value.files.single().uploaded)

        vm.submit()

        assertEquals("Add at least 2 files", vm.state.value.error)
        coVerify(exactly = 0) { repo.startTask(any(), any(), any()) }
    }

    @Test fun `uploads, runs the task and lands on the result`() {
        stubUpload(a, "f1", size = 3000)
        stubUpload(b, "f2", size = 2000)
        val out = fileMeta("out", name = "merged.pdf", kind = "output")
        coEvery { repo.startTask("merge", listOf("f1", "f2"), any()) } returns job(Job.STATUS_QUEUED)
        every { repo.poll(any()) } returns flowOf(job(Job.STATUS_PROCESSING, 30), job(Job.STATUS_DONE, 100, output = out))

        val vm = vm("merge-pdf")
        vm.addUris(listOf(a, b))
        vm.submit()

        val phase = vm.state.value.phase as Phase.Done
        assertEquals(out, phase.output)
        assertEquals(5000L, phase.inputBytes)
        coVerify { account.refreshMe() }
    }

    @Test fun `reordering files changes the merge order`() {
        stubUpload(a, "f1")
        stubUpload(b, "f2")
        coEvery { repo.startTask(any(), any(), any()) } returns job(Job.STATUS_QUEUED)
        every { repo.poll(any()) } returns flowOf(job(Job.STATUS_PROCESSING))

        val vm = vm("merge-pdf")
        vm.addUris(listOf(a, b))
        vm.move(0, 1)
        vm.submit()

        coVerify { repo.startTask("merge", listOf("f2", "f1"), JsonObject(emptyMap())) }
        assertTrue(vm.state.value.phase is Phase.Processing)
    }

    @Test fun `a failed job returns to editing with a friendly message`() {
        stubUpload(a, "f1")
        coEvery { repo.startTask(any(), any(), any()) } returns job(Job.STATUS_QUEUED)
        every { repo.poll(any()) } returns flowOf(job(Job.STATUS_FAILED, error = JobError("wrong_password", "bad password")))

        val vm = vm("unlock-pdf")
        vm.addUris(listOf(a))
        vm.submit()

        assertEquals(Phase.Editing, vm.state.value.phase)
        assertEquals("That password is not correct.", vm.state.value.error)
    }

    @Test fun `hitting the plan limit opens the limit sheet`() {
        stubUpload(a, "f1")
        coEvery { repo.startTask(any(), any(), any()) } throws ApiError(429, "limit_reached", "Hourly task limit reached")

        val vm = vm("compress-pdf")
        vm.addUris(listOf(a))
        vm.submit()

        assertEquals("limit_reached", vm.state.value.limitError?.code)
        assertEquals(Phase.Editing, vm.state.value.phase)
    }

    @Test fun `organize editor sends the edited page order`() {
        stubUpload(a, "f1")
        val options = slot<JsonObject>()
        coEvery { repo.startTask("organize", listOf("f1"), capture(options)) } returns job(Job.STATUS_QUEUED)
        every { repo.poll(any()) } returns flowOf(job(Job.STATUS_PROCESSING))

        val vm = vm("organize-pdf")
        vm.addUris(listOf(a))
        assertEquals(listOf(1, 2, 3), vm.state.value.pageOrder)

        vm.movePage(0, 2) // [2, 3, 1]
        vm.deletePage(3) // [2, 1]
        vm.submit()

        assertEquals(buildJsonObject { put("order", JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(1)))) }, options.captured)
    }

    @Test fun `files of the wrong type are rejected before uploading`() {
        every { repo.describe(a) } returns LocalFile(a, "photo.png", 10, "image/png")
        val vm = vm("compress-pdf")
        vm.addUris(listOf(a))

        assertTrue(vm.state.value.files.isEmpty())
        assertEquals("Only PDF files work with Compress PDF", vm.state.value.error)
        coVerify(exactly = 0) { repo.upload(any(), any()) }
    }

    @Test fun `files over the plan size limit show the limit sheet`() {
        every { repo.describe(a) } returns LocalFile(a, "huge.pdf", 60L * 1024 * 1024, "application/pdf")
        val vm = vm("compress-pdf")
        vm.addUris(listOf(a))

        assertTrue(vm.state.value.files.isEmpty())
        assertEquals("file_too_large", vm.state.value.limitError?.code)
    }

    @Test fun `upload failures can be retried`() {
        every { repo.describe(a) } returns LocalFile(a, "a.pdf", 1000, "application/pdf")
        coEvery { repo.upload(any(), any()) } throws ApiError(0, ApiError.NETWORK, "No connection") andThen fileMeta("f1")

        val vm = vm("compress-pdf")
        vm.addUris(listOf(a))
        val failed = vm.state.value.files.single()
        assertEquals("No connection", failed.error)

        vm.retry(failed.key)
        val item = vm.state.value.files.single()
        assertNull(item.error)
        assertNotNull(item.meta)
    }
}

