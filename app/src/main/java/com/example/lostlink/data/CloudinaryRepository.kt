package com.example.lostlink.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class UploadedMedia(
    val assetId: String,
    val secureUrl: String,
    val publicId: String,
)

/**
 * Signed Cloudinary image upload coordinated through the LostLink FastAPI backend:
 * 1) POST /api/v1/media/uploads -> assetId, uploadUrl, signed params (no API secret in APK)
 * 2) Multipart POST to Cloudinary uploadUrl with signed params
 * 3) POST /api/v1/media/uploads/{assetId}/complete -> verified READY asset metadata
 */
class CloudinaryRepository(
    private val apiClient: BackendApiClient = BackendApiClient(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
) {
    /**
     * Uploads an image from [imageUri] using signed parameters bound to [postId].
     * Returns null if [imageUri] is null.
     */
    suspend fun uploadSignedImage(
        context: Context,
        postId: String,
        imageUri: Uri?,
    ): Result<UploadedMedia?> = withContext(Dispatchers.IO) {
        if (imageUri == null) {
            return@withContext Result.success(null)
        }
        val cleanPostId = postId.trim()
        if (cleanPostId.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Thiếu mã bài đăng để tải ảnh."))
        }

        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(imageUri)?.lowercase().orEmpty()
        if (!isAllowedMimeType(mimeType)) {
            return@withContext Result.failure(
                IllegalArgumentException("Chỉ hỗ trợ ảnh định dạng JPG, PNG hoặc WebP."),
            )
        }

        val bytes = try {
            contentResolver.openInputStream(imageUri)?.use { readImageUploadBytes(it) }
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalArgumentException("Không thể đọc ảnh đã chọn: ${e.localizedMessage}"),
            )
        }

        if (bytes == null || bytes.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException("Tệp ảnh đã chọn bị rỗng."),
            )
        }
        uploadSignedImageBytes(cleanPostId, bytes, mimeType).map { it }
    }

    internal suspend fun uploadSignedImageBytes(
        postId: String,
        bytes: ByteArray,
        mimeType: String = "image/jpeg",
    ): Result<UploadedMedia> = withContext(Dispatchers.IO) {
        val cleanPostId = postId.trim()
        if (cleanPostId.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Thiếu mã bài đăng để tải ảnh."))
        }
        val cleanMime = mimeType.lowercase().trim()
        if (!isAllowedMimeType(cleanMime)) {
            return@withContext Result.failure(
                IllegalArgumentException("Chỉ hỗ trợ ảnh định dạng JPG, PNG hoặc WebP."),
            )
        }
        if (bytes.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Tệp ảnh đã chọn bị rỗng."))
        }
        if (bytes.size > MAX_FILE_SIZE_BYTES) {
            return@withContext Result.failure(IllegalArgumentException("Ảnh vượt quá giới hạn 5 MB."))
        }

        // Step 1: Ask backend for a signed upload ticket bound to postId
        val ticket = apiClient.requestMediaUpload(cleanPostId).getOrElse { err ->
            return@withContext Result.failure(err)
        }
        val missingSignedKeys = REQUIRED_SIGNED_PARAM_KEYS.filter { ticket.params[it].isNullOrBlank() }
        if (missingSignedKeys.isNotEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("Chữ ký tải ảnh thiếu tham số bắt buộc: ${missingSignedKeys.joinToString()}"),
            )
        }

        // Step 2: Upload bytes directly to Cloudinary using exact backend-signed params
        // (preserving optional backend-signed upload_preset while never forwarding api_secret).
        val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
        ticket.params.entries
            .sortedBy { it.key }
            .forEach { (key, value) ->
                if (key != "api_secret" && key != "file" && value.isNotEmpty()) {
                    bodyBuilder.addFormDataPart(key, value)
                }
            }
        val requestMediaType = (if (cleanMime.isNotEmpty()) cleanMime else "image/jpeg").toMediaTypeOrNull()
        bodyBuilder.addFormDataPart(
            "file",
            "upload.${getFileNameExtension(cleanMime)}",
            bytes.toRequestBody(requestMediaType),
        )

        val uploadRequest = Request.Builder()
            .url(ticket.uploadUrl)
            .post(bodyBuilder.build())
            .build()

        try {
            client.newCall(uploadRequest).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errorMessage = parseCloudinaryError(responseBody)
                    return@withContext Result.failure(
                        IOException("Tải ảnh lên thất bại (${response.code}): $errorMessage"),
                    )
                }
                val jsonResponse = SimpleJson.parseObjectOrNull(responseBody)
                val cloudinarySecureUrl = SimpleJson.stringValue(jsonResponse, "secure_url")
                if (!cloudinarySecureUrl.startsWith("https://")) {
                    return@withContext Result.failure(
                        IllegalStateException("Cloudinary không trả về đường dẫn secure_url hợp lệ."),
                    )
                }
            }
        } catch (_: UnknownHostException) {
            return@withContext Result.failure(
                IOException("Không có kết nối mạng. Vui lòng kiểm tra lại Internet."),
            )
        } catch (_: SocketTimeoutException) {
            return@withContext Result.failure(
                IOException("Quá thời gian tải ảnh lên. Vui lòng thử lại."),
            )
        } catch (e: IOException) {
            return@withContext Result.failure(
                IOException("Lỗi mạng khi tải ảnh: ${e.localizedMessage}"),
            )
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }

        // Step 3: Verify completion with backend so media_assets/{assetId} becomes READY
        val completed = apiClient.completeMediaUpload(ticket.assetId).getOrElse { err ->
            return@withContext Result.failure(err)
        }

        Result.success(
            UploadedMedia(
                assetId = completed.assetId,
                secureUrl = completed.secureUrl,
                publicId = completed.publicId,
            ),
        )
    }

    private fun isAllowedMimeType(mimeType: String): Boolean {
        if (mimeType.isEmpty()) return true
        return mimeType == "image/jpeg" ||
            mimeType == "image/jpg" ||
            mimeType == "image/png" ||
            mimeType == "image/webp"
    }

    private fun parseCloudinaryError(responseBody: String): String {
        val json = SimpleJson.parseObjectOrNull(responseBody)
        val errorObj = SimpleJson.objectValue(json, "error")
        if (errorObj != null) {
            return SimpleJson.stringValue(errorObj, "message", responseBody)
        }
        return if (responseBody.isBlank()) "Lỗi Cloudinary không xác định" else responseBody
    }

    private fun getFileNameExtension(mimeType: String): String {
        return when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
    }

    companion object {
        const val MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024 // 5 MB
        private val REQUIRED_SIGNED_PARAM_KEYS = listOf("public_id", "timestamp", "api_key", "signature")
    }
}
