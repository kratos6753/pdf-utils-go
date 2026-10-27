package site.chirudev.pdfutils.testing

import android.net.Uri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import site.chirudev.pdfutils.core.data.FileSource
import site.chirudev.pdfutils.core.data.Foreground
import site.chirudev.pdfutils.core.data.LocalFile
import site.chirudev.pdfutils.core.data.SecureStore
import site.chirudev.pdfutils.core.network.FileMeta
import site.chirudev.pdfutils.core.network.Job
import site.chirudev.pdfutils.core.network.Session
import site.chirudev.pdfutils.core.network.User
import java.io.ByteArrayInputStream
import java.io.InputStream

class FakeSecureStore(var token: String? = null) : SecureStore {
    override suspend fun deviceId() = "testdevice0123456789"
    override suspend fun refreshToken() = token
    override suspend fun setRefreshToken(token: String?) { this.token = token }
}

object AlwaysForeground : Foreground {
    override suspend fun await() = Unit
}

class FakeFileSource(private val bytes: ByteArray = ByteArray(200_000) { (it % 251).toByte() }) : FileSource {
    override fun describe(uri: Uri) = LocalFile(uri, "doc.pdf", bytes.size.toLong(), "application/pdf")
    override fun open(uri: Uri): InputStream = ByteArrayInputStream(bytes)
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

val testUser = User(id = "u1", email = "a@b.co", name = "Ada")

fun session(access: String, refresh: String) = Session(accessToken = access, refreshToken = refresh, user = testUser)

fun fileMeta(id: String, name: String = "$id.pdf", size: Long = 1000, mime: String = "application/pdf", kind: String = "input") =
    FileMeta(id = id, name = name, size = size, mime = mime, kind = kind)

fun job(status: String, progress: Int = 0, output: FileMeta? = null, error: site.chirudev.pdfutils.core.network.JobError? = null) =
    Job(id = "j1", tool = "merge", status = status, progress = progress, output = output, error = error)
