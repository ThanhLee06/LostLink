package com.example.lostlink.ui.home

import com.google.firebase.Timestamp
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HomePost(
    val id: String,
    val title: String,
    val type: String,
    val location: String,
    val createdAt: Long,
    val description: String = "",
    val occurredAt: String = "",
    val images: List<String> = emptyList(),
    val authorName: String = "",
    val contactPhone: String = "",
    val pickupLocation: String = "",
    val status: String = "OPEN",
    val categoryId: String = "",
    val category: String = "",
    val areaId: String = "",
    val contactEmail: String = "",
    val allowContact: Boolean = false,
    val authorId: String = "",
) {
    val isLost get() = type == "LOST"
    val typeLabel get() = if (isLost) "Mất" else "Tìm thấy"
    val isResolved get() = status.equals("RESOLVED", true) || status.equals("CLOSED", true) || status.equals("COMPLETED", true) || status.equals("HOAN_TAT", true)
    val isOpened get() = !isResolved

    companion object {
        fun fromDocument(id: String, data: Map<String, Any?>): HomePost {
            fun text(key: String) = (data[key] as? String)?.trim().orEmpty()
            val title = text("title")
            val type = text("type").uppercase(Locale.ROOT)
            require(id.isNotBlank() && title.isNotBlank() && type in listOf("LOST", "FOUND"))
            val createdAt = (data["createdAt"] as? Timestamp)?.toDate()?.time
                ?: error("Missing posting timestamp")
            val photos = (data["imageUrls"] as? List<*>)?.filterIsInstance<String>().orEmpty() +
                listOf(text("imageUrl"), text("imageUri"))
            val contactEmail = text("contactEmail")

            val allowContact = (data["allowContact"] as? Boolean)
                ?: (data["allowEmailContact"] as? Boolean)
                ?: contactEmail.isNotBlank()

            val authorName = text("authorName")
            val authorId = text("authorId")
            return HomePost(
                id = id,
                title = title,
                type = type,
                location = text("location").ifBlank { "Chưa cập nhật địa điểm" },
                createdAt = createdAt,
                description = text("description"),
                occurredAt = text("occurredAt"),
                images = photos.distinct().filter(::isPublicImage),
                authorName = authorName,
                contactPhone = text("contactPhone"),
                pickupLocation = text("pickupLocation"),
                status = text("status").ifBlank { "OPEN" },
                categoryId = text("categoryId"),
                category = text("category"),
                areaId = text("areaId"),
                contactEmail = contactEmail,
                allowContact = allowContact,
                authorId = authorId
            )
        }

        private fun isPublicImage(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)
    }
}

fun postedTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - timestamp).coerceAtLeast(0) / 60_000)
    return when {
        minutes < 1 -> "Vừa xong"
        minutes < 60 -> "$minutes phút trước"
        minutes < 1_440 -> "${minutes / 60} giờ trước"
        minutes < 10_080 -> "${minutes / 1_440} ngày trước"
        else -> SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.forLanguageTag("vi-VN")).format(Date(timestamp))
    }
}
