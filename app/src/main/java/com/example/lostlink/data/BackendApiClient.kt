package com.example.lostlink.data

import com.example.lostlink.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SignedUploadTicket(
    val assetId: String,
    val uploadUrl: String,
    val publicId: String,
    val params: Map<String, String>,
)

data class CompletedMediaUpload(
    val assetId: String,
    val secureUrl: String,
    val publicId: String,
    val status: String,
)

data class WakeResult(
    val processed: Int,
    val message: String,
)

class BackendApiException(
    val statusCode: Int,
    val code: String?,
    val traceId: String?,
    message: String,
) : IOException(message)

/**
 * Calls the LostLink FastAPI backend using the signed-in student's Firebase ID token.
 * Clear-text HTTP (10.0.2.2/localhost emulator) is strictly limited to debug builds.
 */
class BackendApiClient(
    rawBaseUrl: String = BuildConfig.BACKEND_BASE_URL,
    private val tokenProvider: suspend () -> String = { defaultIdToken() },
    private val allowCleartext: Boolean = BuildConfig.DEBUG,
    private val client: OkHttpClient = defaultClient(),
    private val wakeClient: OkHttpClient = defaultWakeClient(),
) {
    constructor(
        rawBaseUrl: String = BuildConfig.BACKEND_BASE_URL,
        auth: FirebaseAuth,
        allowCleartext: Boolean = BuildConfig.DEBUG,
        client: OkHttpClient = defaultClient(),
        wakeClient: OkHttpClient = defaultWakeClient(),
    ) : this(
        rawBaseUrl = rawBaseUrl,
        tokenProvider = { fetchIdToken(auth) },
        allowCleartext = allowCleartext,
        client = client,
        wakeClient = wakeClient,
    )

    private val baseUrl = rawBaseUrl.trim().trimEnd('/')

    private fun requireConfiguredUrl(): String {
        if (baseUrl.isBlank()) {
            error("Chưa cấu hình địa chỉ backend.")
        }
        val lower = baseUrl.lowercase()
        val isHttps = lower.startsWith("https://")
        val isHttp = lower.startsWith("http://")
        if (!isHttps && !(allowCleartext && isHttp)) {
            error("Kết nối backend yêu cầu HTTPS.")
        }
        return baseUrl
    }

    /** POST /api/v1/media/uploads — reserve postId and obtain Cloudinary signed params. */
    suspend fun requestMediaUpload(postId: String): Result<SignedUploadTicket> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanPostId = postId.trim()
                require(cleanPostId.isNotEmpty()) { "postId không được để trống." }
                val root = requireConfiguredUrl()
                val token = tokenProvider()
                val payload = SimpleJson.encodeObject(mapOf("postId" to cleanPostId))
                val request = Request.Builder()
                    .url("$root/api/v1/media/uploads")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw parseApiError(response.code, body, response.header("X-Trace-Id"))
                    }
                    val json = SimpleJson.parseObject(body)
                    val assetId = SimpleJson.stringValue(json, "assetId").trim()
                    val uploadUrl = SimpleJson.stringValue(json, "uploadUrl").trim()
                    val publicId = SimpleJson.stringValue(json, "publicId").trim()
                    val paramsJson = SimpleJson.objectValue(json, "params")
                        ?: error("Thiếu chữ ký upload từ máy chủ.")
                    require(assetId.isNotEmpty() && uploadUrl.isNotEmpty() && publicId.isNotEmpty()) {
                        "Phản hồi xin chữ ký ảnh không đầy đủ."
                    }
                    val parsedParams = linkedMapOf<String, String>()
                    for ((key, rawVal) in paramsJson) {
                        parsedParams[key] = SimpleJson.scalarToString(rawVal).orEmpty()
                    }
                    SignedUploadTicket(
                        assetId = assetId,
                        uploadUrl = uploadUrl,
                        publicId = publicId,
                        params = parsedParams,
                    )
                }
            }
        }

    /** POST /api/v1/media/uploads/{assetId}/complete — verify & promote asset to READY. */
    suspend fun completeMediaUpload(assetId: String): Result<CompletedMediaUpload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanAssetId = assetId.trim()
                require(cleanAssetId.isNotEmpty()) { "assetId không được để trống." }
                val root = requireConfiguredUrl()
                val token = tokenProvider()
                val encodedAssetId = URLEncoder.encode(cleanAssetId, Charsets.UTF_8.name())
                    .replace("+", "%20")
                val request = Request.Builder()
                    .url("$root/api/v1/media/uploads/$encodedAssetId/complete")
                    .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw parseApiError(response.code, body, response.header("X-Trace-Id"))
                    }
                    val json = SimpleJson.parseObject(body)
                    val returnedId = SimpleJson.stringValue(json, "assetId").trim()
                    val secureUrl = SimpleJson.stringValue(json, "secureUrl").trim()
                    val publicId = SimpleJson.stringValue(json, "publicId").trim()
                    val status = SimpleJson.stringValue(json, "status").trim()
                    require(returnedId.isNotEmpty() && secureUrl.startsWith("https://") && publicId.isNotEmpty()) {
                        "Phản hồi xác nhận ảnh không hợp lệ."
                    }
                    require(status == "READY") {
                        "Ảnh chưa sẵn sàng ($status)."
                    }
                    CompletedMediaUpload(
                        assetId = returnedId,
                        secureUrl = secureUrl,
                        publicId = publicId,
                        status = status,
                    )
                }
            }
        }

    /** POST /api/v1/events/wake — best-effort trigger using short timeouts. */
    suspend fun wakeWorker(): Result<WakeResult> = withContext(Dispatchers.IO) {
        runCatching {
            val root = requireConfiguredUrl()
            val token = tokenProvider()
            val request = Request.Builder()
                .url("$root/api/v1/events/wake")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Authorization", "Bearer $token")
                .build()

            wakeClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw parseApiError(response.code, body, response.header("X-Trace-Id"))
                }
                val json = SimpleJson.parseObject(body)
                WakeResult(
                    processed = SimpleJson.intValue(json, "processed", 0),
                    message = SimpleJson.stringValue(json, "message", ""),
                )
            }
        }
    }

    internal fun parseApiError(statusCode: Int, responseBody: String, headerTraceId: String?): BackendApiException {
        val json = SimpleJson.parseObjectOrNull(responseBody)
        val rawDetail = SimpleJson.stringValue(json, "detail").trim()
        val code = SimpleJson.stringValue(json, "code").trim().takeIf { it.isNotEmpty() }
        val traceId = SimpleJson.stringValue(json, "traceId").trim().takeIf { it.isNotEmpty() }
            ?: headerTraceId?.trim()?.takeIf { it.isNotEmpty() }
        val friendly = when (statusCode) {
            401 -> "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại."
            403 -> if (rawDetail.isNotBlank()) rawDetail else "Tài khoản không có quyền thực hiện thao tác này."
            409 -> if (rawDetail.isNotBlank()) rawDetail else "Xung đột trạng thái dữ liệu. Vui lòng thử lại."
            429 -> "Bạn gửi yêu cầu quá nhanh. Vui lòng đợi một lát."
            else -> rawDetail.ifBlank { "Lỗi máy chủ ($statusCode)." }
        }
        return BackendApiException(statusCode = statusCode, code = code, traceId = traceId, message = friendly)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

        private fun defaultWakeClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()

        private suspend fun defaultIdToken(): String = fetchIdToken(FirebaseAuth.getInstance())

        private suspend fun fetchIdToken(auth: FirebaseAuth): String {
            val user = auth.currentUser ?: error("Not signed in")
            return user.getIdToken(false).await().token
                ?: error("Could not get ID token")
        }
    }
}
