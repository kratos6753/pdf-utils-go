package site.chirudev.pdfutils.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import site.chirudev.pdfutils.BuildConfig
import site.chirudev.pdfutils.core.data.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/** Adds the identity headers every /v1 request needs. */
@Singleton
class AuthInterceptor @Inject constructor(private val session: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val device = runBlocking { session.deviceId() }
        val builder = chain.request().newBuilder()
            .header("X-Device-Id", device)
            .header("X-Client-Type", "android")
            .header("User-Agent", "PdfUtils-Android/${BuildConfig.VERSION_NAME}")
        val token = session.accessToken
        if (token != null && chain.request().header("Authorization") == null) {
            builder.header("Authorization", "Bearer $token")
        }
        return chain.proceed(builder.build())
    }
}

/** On a 401 from an expired access token, refreshes once and retries the request. */
@Singleton
class TokenAuthenticator @Inject constructor(private val session: SessionManager) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (request.url.encodedPath.startsWith("/v1/auth/")) return null
        if (response.priorResponse != null) return null // already retried once
        val sent = request.header("Authorization")?.removePrefix("Bearer ") ?: return null
        val fresh = runBlocking { session.refresh(staleAccessToken = sent) } ?: return null
        return request.newBuilder().header("Authorization", "Bearer $fresh").build()
    }
}
