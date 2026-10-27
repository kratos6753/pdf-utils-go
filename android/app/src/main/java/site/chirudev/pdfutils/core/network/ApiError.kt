package site.chirudev.pdfutils.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

/** Every failure the UI sees: the server's `{"error":{code,message,details}}` or a local code. */
class ApiError(
    val status: Int,
    val code: String,
    override val message: String,
    val details: JsonObject? = null,
) : Exception(message) {

    fun detailString(key: String): String? = (details?.get(key) as? JsonPrimitive)?.contentOrNull
    fun detailInt(key: String): Int? = (details?.get(key) as? JsonPrimitive)?.intOrNull
    fun detailBool(key: String): Boolean? = (details?.get(key) as? JsonPrimitive)?.booleanOrNull

    /** Errors that mean "a plan limit stopped you" and deserve the upgrade sheet. */
    val isLimit get() = status == 429 || code in LIMIT_CODES

    companion object {
        const val NETWORK = "network"
        const val CANCELLED = "aborted"
        private val LIMIT_CODES = setOf("limit_reached", "file_too_large", "quota_exceeded", "rate_limited")

        fun parse(status: Int, body: String?, fallback: String = "Request failed"): ApiError {
            if (!body.isNullOrBlank()) {
                runCatching {
                    val err = Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                    if (err != null) {
                        return ApiError(
                            status = status,
                            code = err["code"]?.jsonPrimitive?.contentOrNull ?: "error",
                            message = err["message"]?.jsonPrimitive?.contentOrNull ?: fallback,
                            details = err["details"] as? JsonObject,
                        )
                    }
                }
            }
            return ApiError(status, "error", if (status >= 500) "The server had a problem. Please try again." else fallback)
        }

        fun from(t: Throwable): ApiError = when (t) {
            is ApiError -> t
            is CancellationException -> throw t
            is HttpException -> parse(t.code(), runCatching { t.response()?.errorBody()?.string() }.getOrNull(), t.message())
            is IOException -> ApiError(0, NETWORK, "No connection. Check your internet and try again.")
            else -> ApiError(0, "error", t.message ?: "Something went wrong")
        }
    }
}

/** Runs an API call, turning every failure into an [ApiError]. */
suspend inline fun <T> apiCall(crossinline block: suspend () -> T): T =
    try {
        block()
    } catch (t: Throwable) {
        throw ApiError.from(t)
    }
