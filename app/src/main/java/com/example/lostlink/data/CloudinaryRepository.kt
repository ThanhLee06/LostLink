package com.example.lostlink.data

import android.content.Context
import android.net.Uri
import com.example.lostlink.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Handles image uploading to Cloudinary using Unsigned Upload Presets.
 *
 * Configured via local.properties (CLOUDINARY_CLOUD_NAME and CLOUDINARY_UPLOAD_PRESET)
 * which are exposed as BuildConfig constants.
 */
class CloudinaryRepository(
    private val cloudName: String = BuildConfig.CLOUDINARY_CLOUD_NAME,
    private val uploadPreset: String = BuildConfig.CLOUDINARY_UPLOAD_PRESET,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    /**
     * Uploads an image from [imageUri] to Cloudinary asynchronously.
     *
     * @param context Application/Activity context to resolve ContentResolver.
     * @param imageUri Uri of the selected image. If null, returns Result.success(null).
     * @return Result containing the HTTPS `secure_url` on success, or null if [imageUri] was null.
     */
    suspend fun uploadImage(
        context: Context,
        imageUri: Uri?,
    ): Result<String?> = withContext(Dispatchers.IO) {
        if (imageUri == null) {
            return@withContext Result.success(null)
        }

        if (isUnconfigured(cloudName) || isUnconfigured(uploadPreset)) {
            return@withContext Result.failure(
                IllegalStateException("Chưa cấu hình Cloudinary, xem docs/Cloudinary-Setup.md"),
            )
        }

        val contentResolver = context.contentResolver

        // 1. Validate MIME Type (Only JPG, PNG, WEBP allowed)
        val mimeType = contentResolver.getType(imageUri)?.lowercase().orEmpty()
        val isAllowedMimeType = mimeType == "image/jpeg" ||
            mimeType == "image/jpg" ||
            mimeType == "image/png" ||
            mimeType == "image/webp"

        if (!isAllowedMimeType && mimeType.isNotEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException("Only JPG, PNG, and WebP images are allowed."),
            )
        }

        // 2. Validate File Size (Max 5 MB)
        val bytes = try {
            contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalArgumentException("Unable to read selected image: ${e.localizedMessage}"),
            )
        }

        if (bytes == null || bytes.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException("Selected image file is empty."),
            )
        }

        if (bytes.size > MAX_FILE_SIZE_BYTES) {
            return@withContext Result.failure(
                IllegalArgumentException("Image size exceeds the 5 MB limit."),
            )
        }

        // 3. Build Multipart Request for Cloudinary Unsigned Upload API
        val uploadUrl = "https://api.cloudinary.com/v1_1/$cloudName/image/upload"
        val requestMediaType = (if (mimeType.isNotEmpty()) mimeType else "image/jpeg").toMediaTypeOrNull()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("upload_preset", uploadPreset)
            .addFormDataPart(
                "file",
                "upload.${getFileNameExtension(mimeType)}",
                bytes.toRequestBody(requestMediaType),
            )
            .build()

        val request = Request.Builder()
            .url(uploadUrl)
            .post(requestBody)
            .build()

        // 4. Execute Network Request & Handle Errors
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val errorMessage = parseCloudinaryError(responseBody)
                return@withContext Result.failure(
                    IOException("Upload failed (${response.code}): $errorMessage"),
                )
            }

            // 5. Parse secure_url from Cloudinary JSON response
            val jsonResponse = JSONObject(responseBody)
            val secureUrl = jsonResponse.optString("secure_url", "")

            if (secureUrl.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Cloudinary did not return a valid secure_url."),
                )
            }

            Result.success(secureUrl)
        } catch (e: UnknownHostException) {
            Result.failure(IOException("No internet connection. Please check your network and try again."))
        } catch (e: SocketTimeoutException) {
            Result.failure(IOException("Upload timed out. Please check your network connection."))
        } catch (e: IOException) {
            Result.failure(IOException("Network error while uploading image: ${e.localizedMessage}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseCloudinaryError(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            if (json.has("error")) {
                json.getJSONObject("error").optString("message", responseBody)
            } else {
                responseBody
            }
        } catch (_: Exception) {
            if (responseBody.isBlank()) "Unknown Cloudinary error" else responseBody
        }
    }

    private fun getFileNameExtension(mimeType: String): String {
        return when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
    }

    private fun isUnconfigured(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return true
        if (trimmed.contains("<") || trimmed.contains(">") || trimmed.contains("TÔI SẼ TỰ ĐIỀN")) return true
        val lower = trimmed.lowercase()
        return lower.contains("your_cloud_name") ||
            lower.contains("your_unsigned_upload_preset") ||
            lower.contains("your_cloud_name_here") ||
            lower.contains("your_unsigned_upload_preset_here")
    }

    companion object {
        const val MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024 // 5 MB
    }
}
