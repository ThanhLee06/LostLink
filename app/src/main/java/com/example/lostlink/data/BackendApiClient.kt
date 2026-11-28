package com.example.lostlink.data

import com.example.lostlink.BuildConfig
import com.example.lostlink.ui.home.HomePost
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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

data class SmartSearchRequest(
    val query: String = "",
    val type: String? = null,
    val categoryIds: List<String> = emptyList(),
    val areaIds: List<String> = emptyList(),
    val timeFilter: String = "ALL",
    val pageSize: Int = 20,
    val cursor: String? = null,
)

data class SmartSearchPage(
    val posts: List<HomePost>,
    val hasMore: Boolean,
    val nextCursor: String?,
    val searchMode: String,
)

fun friendlySearchError(error: Throwable): String {
    if (error is SocketTimeoutException || error is InterruptedIOException) {
        return "Máy chủ đang khởi động hoặc phản hồi chậm. Vui lòng thử lại sau vài giây."
    }
    if (error is UnknownHostException || error is ConnectException) {
        return "Không có kết nối mạng hoặc không thể kết nối máy chủ. Vui lòng thử lại."
    }
    if (error is BackendApiException) {
        return when (error.statusCode) {
            503, 504 -> "Máy chủ đang khởi động hoặc tạm bận. Vui lòng thử lại sau vài giây."
            else -> error.message.orEmpty().ifBlank { "Lỗi tìm kiếm (${error.statusCode})." }
        }
    }
    return "Chưa thể tìm kiếm lúc này. Vui lòng kiểm tra mạng và thử lại."
}

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

    /** POST /api/v1/search — tìm kiếm thông minh trên máy chủ kèm lọc hiển thị. */
    suspend fun searchPosts(searchRequest: SmartSearchRequest): Result<SmartSearchPage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = requireConfiguredUrl()
                val token = tokenProvider()
                val bodyMap = linkedMapOf<String, Any?>(
                    "query" to searchRequest.query.trim().take(200),
                    "categoryIds" to searchRequest.categoryIds,
                    "areaIds" to searchRequest.areaIds,
                    "timeFilter" to searchRequest.timeFilter,
                    "pageSize" to searchRequest.pageSize.coerceIn(1, 50),
                )
                searchRequest.type?.let { bodyMap["type"] = it }
                searchRequest.cursor?.let { bodyMap["cursor"] = it }
                val request = Request.Builder()
                    .url("$root/api/v1/search")
                    .post(SimpleJson.encodeObject(bodyMap).toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                awaitCall(client.newCall(request)).use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw parseApiError(response.code, body, response.header("X-Trace-Id"))
                    }
                    val json = SimpleJson.parseObject(body)
                    val rawItems = (json["items"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
                    val posts = rawItems.mapNotNull { item ->
                        val postId = SimpleJson.stringValue(item, "postId").trim()
                        val title = SimpleJson.stringValue(item, "title").trim()
                        val type = SimpleJson.stringValue(item, "type").trim()
                        val status = SimpleJson.stringValue(item, "status", "OPEN").trim()
                        if (postId.isEmpty() || title.isEmpty() || type !in listOf("LOST", "FOUND")) {
                            return@mapNotNull null
                        }
                        val imgUrl = SimpleJson.stringValue(item, "imageUrl").trim()
                        HomePost(
                            id = postId,
                            title = title,
                            type = type,
                            location = SimpleJson.stringValue(item, "location").trim().ifBlank { "Chưa cập nhật địa điểm" },
                            createdAt = parseIsoMillis(SimpleJson.stringValue(item, "createdAt")),
                            images = if (imgUrl.startsWith("https://")) listOf(imgUrl) else emptyList(),
                            status = status,
                            categoryId = SimpleJson.stringValue(item, "categoryId").trim(),
                            areaId = SimpleJson.stringValue(item, "areaId").trim(),
                        )
                    }
                    val rawCursor = SimpleJson.stringValue(json, "nextCursor").trim()
                    SmartSearchPage(
                        posts = posts,
                        hasMore = json["hasMore"] as? Boolean ?: false,
                        nextCursor = rawCursor.takeIf { it.isNotEmpty() },
                        searchMode = SimpleJson.stringValue(json, "searchMode", "KEYWORD"),
                    )
                }
            }
        }

    private suspend fun awaitCall(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resumeWith(Result.success(response)) else response.close()
            }
        })
    }

    private fun parseIsoMillis(raw: String): Long {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return 0L
        val normalized = cleaned
            .replace(Regex("\\.\\d+"), "")
            .replace("Z", "+0000")
            .replace("z", "+0000")
            .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return runCatching { fmt.parse(normalized)?.time ?: 0L }.getOrDefault(0L)
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
