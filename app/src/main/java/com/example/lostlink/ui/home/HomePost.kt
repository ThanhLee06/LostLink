package com.example.lostlink.ui.home

import com.example.lostlink.ui.post.PostAreas
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
    val moderationStatus: String = "VISIBLE", // VISIBLE | HIDDEN (moderationReason never read from posts)
    val version: Int = 1,
    val areaChoice: String = "",
    val areaOtherText: String = "",
    val locationNote: String = "",
    val timeMode: String = "",
    val exactAtMillis: Long? = null,
    val rangeStartMillis: Long? = null,
    val rangeEndMillis: Long? = null,
    val timeNote: String = "",
    val mediaAssetIds: List<String> = emptyList(),
    val imagePublicId: String? = null,
) {
    val isLost get() = type == "LOST"
    val typeLabel get() = if (isLost) "Mất" else "Tìm thấy"
    val isActive get() = status.equals("OPEN", true) || status.equals("ACTIVE", true)
    val isResolved get() = status.equals("RESOLVED", true) || status.equals("CLOSED", true) || status.equals("COMPLETED", true) || status.equals("HOAN_TAT", true)
    val isClosed get() = status.equals("CLOSED", true)
    val isCompleted get() = isResolved
    val isOpened get() = !isResolved
    val isHidden get() = moderationStatus.equals("HIDDEN", true)
    val isVisibleOnFeed get() = !isHidden && (isActive || isResolved)

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
            val rawStatus = text("status").uppercase(Locale.ROOT)
            val normalizedStatus = when (rawStatus) {
                "", "ACTIVE" -> "OPEN"
                else -> rawStatus
            }
            val rawModerationStatus = text("moderationStatus").uppercase(Locale.ROOT).ifBlank { "VISIBLE" }
            val rawAreaId = text("areaId")
            val normalizedAreaId = PostAreas.normalizeId(rawAreaId) ?: rawAreaId
            val assetIds = (data["mediaAssetIds"] as? List<*>)
                ?.filterIsInstance<String>()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

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
                status = normalizedStatus,
                moderationStatus = rawModerationStatus,
                version = (data["version"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1,
                categoryId = text("categoryId"),
                category = text("category"),
                areaId = normalizedAreaId,
                contactEmail = contactEmail,
                allowContact = allowContact,
                authorId = authorId,
                areaChoice = text("areaChoice"),
                areaOtherText = text("areaOtherText"),
                locationNote = text("locationNote"),
                timeMode = text("timeMode"),
                exactAtMillis = (data["exactAtMillis"] as? Number)?.toLong(),
                rangeStartMillis = (data["rangeStartMillis"] as? Number)?.toLong(),
                rangeEndMillis = (data["rangeEndMillis"] as? Number)?.toLong(),
                timeNote = text("timeNote"),
                mediaAssetIds = assetIds,
                imagePublicId = text("imagePublicId").takeIf { it.isNotEmpty() },
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
