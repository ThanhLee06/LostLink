package com.example.lostlink.ui.post

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object ImagePickerValidator {
    const val MAX_IMAGE_SIZE_BYTES = 5 * 1024 * 1024L

    private val ALLOWED_MIME_TYPES = setOf(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/webp"
    )

    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }


    fun validateImage(context: Context, uri: Uri): ValidationResult {
        return try {
            val contentResolver = context.contentResolver


            val inputStream = contentResolver.openInputStream(uri)
                ?: return ValidationResult.Invalid("Không thể đọc tệp ảnh đã chọn. Vui lòng thử lại.")


            val mimeType = contentResolver.getType(uri)?.lowercase()
            if (mimeType != null && mimeType.isNotEmpty() && mimeType !in ALLOWED_MIME_TYPES) {
                inputStream.close()
                return ValidationResult.Invalid("Định dạng ảnh không được hỗ trợ. Chỉ chấp nhận ảnh JPG, PNG hoặc WebP.")
            }


            var fileSize = -1L
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    fileSize = cursor.getLong(sizeIndex)
                }
            }

            if (fileSize <= 0) {
                               fileSize = inputStream.available().toLong()
            }
            inputStream.close()

            if (fileSize > MAX_IMAGE_SIZE_BYTES) {
                val sizeInMb = String.format(java.util.Locale.US, "%.1f", fileSize / (1024.0 * 1024.0))
                return ValidationResult.Invalid("Ảnh quá lớn ($sizeInMb MB). Vui lòng chọn ảnh dưới 5 MB.")
            }

            ValidationResult.Valid
        } catch (e: SecurityException) {
            ValidationResult.Invalid("Không có quyền truy cập vào ảnh này. Vui lòng chọn ảnh khác.")
        } catch (e: Exception) {
            ValidationResult.Invalid("Không thể mở tệp ảnh: ${e.localizedMessage ?: "Lỗi không xác định"}")
        }
    }
}
