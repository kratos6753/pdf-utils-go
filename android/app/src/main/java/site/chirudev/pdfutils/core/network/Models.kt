package site.chirudev.pdfutils.core.network

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Immutable
@Serializable
data class FileMeta(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
    val kind: String = "input",
    @SerialName("job_id") val jobId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
) {
    val isPdf get() = mime == "application/pdf"
    val isImage get() = mime.startsWith("image/")
    val isZip get() = mime == "application/zip"
}

@Serializable
data class UploadResponse(val files: List<FileMeta>)

@Serializable
data class JobError(val code: String = "error", val message: String = "Processing failed")

@Immutable
@Serializable
data class Job(
    val id: String,
    val tool: String,
    val status: String,
    val progress: Int = 0,
    val error: JobError? = null,
    @SerialName("input_ids") val inputIds: List<String> = emptyList(),
    val output: FileMeta? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
) {
    val isRunning get() = status == STATUS_QUEUED || status == STATUS_PROCESSING

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_PROCESSING = "processing"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
    }
}

@Serializable
data class TaskRequest(
    @SerialName("file_ids") val fileIds: List<String>,
    val options: JsonObject,
)

@Serializable
data class FileLink(val url: String, @SerialName("expires_at") val expiresAt: String? = null, val name: String, val size: Long)

@Immutable
@Serializable
data class User(
    val id: String,
    val email: String,
    val name: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    val plan: String = "free",
    @SerialName("email_verified") val emailVerified: Boolean = false,
    @SerialName("has_password") val hasPassword: Boolean = true,
    @SerialName("has_google") val hasGoogle: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class Session(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val user: User,
)

@Serializable
data class Limits(
    @SerialName("tasks_per_hour") val tasksPerHour: Int = 10,
    @SerialName("max_file_mb") val maxFileMb: Int = 50,
    @SerialName("max_files_per_task") val maxFilesPerTask: Int = 20,
    @SerialName("storage_mb") val storageMb: Int = 0,
)

@Serializable
data class TaskUsage(val remaining: Int = 0, val limit: Int = 0, @SerialName("reset_at") val resetAt: String? = null)

@Serializable
data class StorageUsage(@SerialName("used_bytes") val usedBytes: Long = 0, @SerialName("limit_bytes") val limitBytes: Long = 0)

@Immutable
@Serializable
data class Me(
    val user: User? = null,
    val plan: String = "anon",
    val limits: Limits = Limits(),
    val tasks: TaskUsage? = null,
    val storage: StorageUsage? = null,
)

@Serializable
data class AppConfig(@SerialName("google_auth") val googleAuth: Boolean = false)

@Serializable
data class FilePage(val files: List<FileMeta> = emptyList(), @SerialName("next_cursor") val nextCursor: String? = null)

@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class SignupRequest(val email: String, val password: String, val name: String)
@Serializable data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)
@Serializable data class GoogleTokenRequest(@SerialName("id_token") val idToken: String)
@Serializable data class EmailRequest(val email: String)
@Serializable data class TokenRequest(val token: String)
@Serializable data class ResetPasswordRequest(val token: String, val password: String)
@Serializable data class UpdateMeRequest(val name: String)
@Serializable
data class ChangePasswordRequest(
    @SerialName("current_password") val currentPassword: String? = null,
    @SerialName("new_password") val newPassword: String,
)
