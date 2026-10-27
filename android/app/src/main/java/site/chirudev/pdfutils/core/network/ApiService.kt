package site.chirudev.pdfutils.core.network

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @GET("v1/config") suspend fun config(): AppConfig
    @GET("v1/me") suspend fun me(): Me

    @Multipart
    @POST("v1/uploads")
    suspend fun upload(@Part file: MultipartBody.Part): UploadResponse

    @POST("v1/tasks/{tool}")
    suspend fun createTask(
        @Path("tool") tool: String,
        @Body body: TaskRequest,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): Job

    @GET("v1/jobs/{id}") suspend fun job(@Path("id") id: String): Job
    @GET("v1/files/{id}") suspend fun file(@Path("id") id: String): FileMeta
    @GET("v1/files/{id}/link") suspend fun fileLink(@Path("id") id: String): FileLink
    @DELETE("v1/files/{id}") suspend fun deleteFile(@Path("id") id: String): Response<Unit>

    @GET("v1/me/files")
    suspend fun myFiles(
        @Query("kind") kind: String?,
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int = 30,
    ): FilePage

    @POST("v1/auth/login") suspend fun login(@Body body: LoginRequest): Session
    @POST("v1/auth/signup") suspend fun signup(@Body body: SignupRequest): Session
    @POST("v1/auth/google/token") suspend fun googleToken(@Body body: GoogleTokenRequest): Session
    @POST("v1/auth/logout") suspend fun logout(@Body body: RefreshRequest): Response<Unit>
    @POST("v1/auth/forgot-password") suspend fun forgotPassword(@Body body: EmailRequest): Response<Unit>
    @POST("v1/auth/reset-password") suspend fun resetPassword(@Body body: ResetPasswordRequest): Response<Unit>
    @POST("v1/auth/verify-email") suspend fun verifyEmail(@Body body: TokenRequest): Response<Unit>

    @PATCH("v1/me") suspend fun updateMe(@Body body: UpdateMeRequest): Response<Unit>
    @DELETE("v1/me") suspend fun deleteMe(): Response<Unit>
    @POST("v1/me/password") suspend fun changePassword(@Body body: ChangePasswordRequest): Response<Unit>
    @POST("v1/me/resend-verification") suspend fun resendVerification(@Body body: Map<String, String> = emptyMap()): Response<Unit>
}

/** Refresh lives on its own client so the authenticator can't recurse into itself. */
interface AuthRefreshService {
    @POST("v1/auth/refresh") suspend fun refresh(@Body body: RefreshRequest): Session
}

/** Converts a non-2xx [Response] into an [ApiError]. */
fun <T> Response<T>.orThrow(): Response<T> {
    if (!isSuccessful) throw ApiError.parse(code(), errorBody()?.string(), message())
    return this
}
