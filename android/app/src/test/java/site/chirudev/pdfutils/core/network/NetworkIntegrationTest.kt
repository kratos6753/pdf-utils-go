package site.chirudev.pdfutils.core.network

import android.content.Context
import android.net.Uri
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import site.chirudev.pdfutils.core.data.LocalFile
import site.chirudev.pdfutils.core.data.PdfRepository
import site.chirudev.pdfutils.core.data.SessionManager
import site.chirudev.pdfutils.core.di.AppJson
import site.chirudev.pdfutils.core.di.NetworkModule
import site.chirudev.pdfutils.testing.AlwaysForeground
import site.chirudev.pdfutils.testing.FakeFileSource
import site.chirudev.pdfutils.testing.FakeSecureStore
import site.chirudev.pdfutils.testing.session

/** Exercises the real OkHttp/Retrofit stack (interceptor, authenticator, repository) against a local server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkIntegrationTest {
    private val server = MockWebServer()
    private lateinit var store: FakeSecureStore
    private lateinit var sessionManager: SessionManager
    private lateinit var api: ApiService
    private lateinit var base: OkHttpClient

    @Before fun setUp() {
        server.start()
        val baseUrl = server.url("/").toString()
        base = NetworkModule.baseClient()
        store = FakeSecureStore()
        lateinit var interceptor: AuthInterceptor
        val refresh = NetworkModule.retrofit(base.newBuilder().addInterceptor { interceptor.intercept(it) }.build(), baseUrl)
            .create(AuthRefreshService::class.java)
        sessionManager = SessionManager(store, refresh)
        interceptor = AuthInterceptor(sessionManager)
        api = NetworkModule.retrofit(base.newBuilder().addInterceptor(interceptor).authenticator(TokenAuthenticator(sessionManager)).build(), baseUrl)
            .create(ApiService::class.java)
    }

    @After fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun sessionJson(access: String, refresh: String) = AppJson.encodeToString(Session.serializer(), session(access, refresh))

    @Test fun `every request carries the device id and client type`() = runTest {
        server.enqueue(json("""{"user":null,"plan":"anon","limits":{"tasks_per_hour":10,"max_file_mb":50,"max_files_per_task":20,"storage_mb":0}}"""))

        val me = api.me()

        val req = server.takeRequest()
        assertEquals("testdevice0123456789", req.headers["X-Device-Id"])
        assertEquals("android", req.headers["X-Client-Type"])
        assertNull(req.headers["Authorization"])
        assertEquals("anon", me.plan)
        assertEquals(50, me.limits.maxFileMb)
    }

    @Test fun `an expired access token is refreshed once and the request retried`() = runTest {
        sessionManager.onSignedIn(session("old-access", "r1"))
        server.enqueue(json("""{"error":{"code":"invalid_token","message":"token expired"}}""", 401))
        server.enqueue(json(sessionJson("new-access", "r2")))
        server.enqueue(json("""{"user":{"id":"u1","email":"a@b.co"},"plan":"free"}"""))

        val me = api.me()

        val first = server.takeRequest()
        val refresh = server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("Bearer old-access", first.headers["Authorization"])
        assertEquals("/v1/auth/refresh", refresh.url.encodedPath)
        assertEquals("r1", AppJson.parseToJsonElement(refresh.body!!.utf8()).jsonObject["refresh_token"]!!.jsonPrimitive.content)
        assertEquals("Bearer new-access", retry.headers["Authorization"])
        assertEquals("r2", store.token)
        assertEquals("free", me.plan)
    }

    @Test fun `server errors surface as ApiError`() = runTest {
        server.enqueue(json("""{"error":{"code":"not_found","message":"file not found"}}""", 404))
        val e = runCatching { apiCall { api.file("missing") } }.exceptionOrNull() as ApiError
        assertEquals(404, e.status)
        assertEquals("not_found", e.code)
    }

    private fun repository() = PdfRepository(api, FakeFileSource(), AlwaysForeground, base, mockk<Context>(relaxed = true))

    @Test fun `upload streams the file as multipart field 'file' with progress`() = runTest {
        server.enqueue(json("""{"files":[{"id":"f1","name":"doc.pdf","size":200000,"mime":"application/pdf","kind":"input"}]}""", 201))
        val progress = mutableListOf<Float>()

        val meta = repository().upload(LocalFile(Uri.parse("content://x/doc.pdf"), "doc.pdf", 200_000, "application/pdf")) { progress += it }

        val req = server.takeRequest()
        assertEquals("/v1/uploads", req.url.encodedPath)
        assertTrue(req.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val body = req.body!!.utf8()
        assertTrue(body.contains("""name="file"; filename="doc.pdf""""))
        assertTrue(req.bodySize > 200_000)
        assertEquals("f1", meta.id)
        assertEquals(1f, progress.last(), 0f)
    }

    @Test fun `tasks send options with an idempotency key and polling stops when done`() = runTest {
        server.enqueue(json("""{"id":"j1","tool":"compress","status":"queued","progress":0,"input_ids":["f1"]}""", 202))
        server.enqueue(json("""{"id":"j1","tool":"compress","status":"processing","progress":30,"input_ids":["f1"]}"""))
        server.enqueue(json("""{"id":"j1","tool":"compress","status":"done","progress":100,"input_ids":["f1"],"output":{"id":"o1","name":"doc-compressed.pdf","size":10,"mime":"application/pdf","kind":"output"}}"""))
        val repo = repository()

        val job = repo.startTask("compress", listOf("f1"), buildJsonObject { put("level", "recommended") })
        val updates = repo.poll(job).toList()

        val create = server.takeRequest()
        assertEquals("/v1/tasks/compress", create.url.encodedPath)
        assertNotNull(create.headers["Idempotency-Key"])
        assertEquals(
            buildJsonObject { put("file_ids", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("f1")))); put("options", buildJsonObject { put("level", "recommended") }) },
            AppJson.parseToJsonElement(create.body!!.utf8()),
        )
        assertEquals(listOf("queued", "processing", "done"), updates.map { it.status })
        assertEquals("o1", updates.last().output?.id)
        assertEquals("/v1/jobs/j1", server.takeRequest().url.encodedPath)
        assertEquals(3, server.requestCount)
    }

    @Test fun `poll cadence matches the web app`() {
        assertEquals(600L, PdfRepository.pollDelay(0))
        assertEquals(600L, PdfRepository.pollDelay(9))
        assertEquals(1500L, PdfRepository.pollDelay(10))
    }

    @Test fun `downloaded names are made filesystem safe`() {
        assertEquals("a_b_c.pdf", PdfRepository.safeName("a/b:c.pdf"))
        assertEquals("file", PdfRepository.safeName(""))
    }
}
