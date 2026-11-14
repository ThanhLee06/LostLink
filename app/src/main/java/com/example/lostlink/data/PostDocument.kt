package com.example.lostlink.data

import com.example.lostlink.ui.post.AreaChoice
import com.example.lostlink.ui.post.PostAreas
import com.example.lostlink.ui.post.PostCategories
import com.example.lostlink.ui.post.PostFormState
import com.example.lostlink.ui.post.TimeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Canonical Firestore write contract shared with HomePost, PostDetail, Backend, and Rules. */
object PostDocument {
    val ALLOWED_STATUSES = setOf("OPEN", "RESOLVED", "CLOSED")
    val ALLOWED_EVENT_CHANGE_TYPES = setOf("CREATE", "UPDATE", "STATUS", "DELETE")

    private fun sharedContentFields(draft: PostFormState, media: UploadedMedia?): Map<String, Any?> {
        val canonicalCategory = requireNotNull(PostCategories.find(draft.categoryId)) {
            "Danh mục không hợp lệ: ${draft.categoryId}"
        }
        val canonicalArea = when (draft.areaChoice) {
            AreaChoice.KNOWN -> requireNotNull(PostAreas.find(draft.areaId)) {
                "Khu vực không hợp lệ: ${draft.areaId}"
            }
            else -> null
        }
        val areaLabel = when (draft.areaChoice) {
            AreaChoice.KNOWN -> canonicalArea!!.label
            AreaChoice.OTHER -> draft.areaOtherText.trim()
            AreaChoice.UNKNOWN -> "Không rõ khu vực"
        }
        val note = draft.locationNote.trim()
        val location = if (note.isEmpty()) areaLabel else "$areaLabel ($note)"
        val format = SimpleDateFormat("HH:mm - dd/MM/yyyy", Locale.forLanguageTag("vi-VN")).apply {
            timeZone = TimeZone.getTimeZone("GMT+07:00")
        }
        fun formatAt(value: Long?) = value?.let { format.format(Date(it)) }.orEmpty()
        val occurredAt = when (draft.timeMode) {
            TimeMode.EXACT -> formatAt(draft.exactAtMillis)
            TimeMode.RANGE -> "${formatAt(draft.rangeStartMillis)} – ${formatAt(draft.rangeEndMillis)}"
            TimeMode.DESCRIBED -> draft.timeNote.trim()
            TimeMode.UNKNOWN -> ""
        }

        val mediaAssetIds = listOfNotNull(media?.assetId)
        val imageUrl = media?.secureUrl
        val imageUrls = listOfNotNull(imageUrl)
        val imagePublicId = media?.publicId?.trim()?.takeIf { it.isNotEmpty() }

        return mapOf(
            "title" to draft.title.trim(),
            "categoryId" to canonicalCategory.id,
            "category" to canonicalCategory.label,
            "description" to draft.description.trim(),
            "areaChoice" to draft.areaChoice.name,
            "areaId" to (canonicalArea?.id ?: ""),
            "areaOtherText" to if (draft.areaChoice == AreaChoice.OTHER) draft.areaOtherText.trim() else "",
            "location" to location,
            "locationNote" to note,
            "occurredAt" to occurredAt,
            "timeMode" to draft.timeMode.name,
            "exactAtMillis" to if (draft.timeMode == TimeMode.EXACT) draft.exactAtMillis else null,
            "rangeStartMillis" to if (draft.timeMode == TimeMode.RANGE) draft.rangeStartMillis else null,
            "rangeEndMillis" to if (draft.timeMode == TimeMode.RANGE) draft.rangeEndMillis else null,
            "timeNote" to if (draft.timeMode == TimeMode.DESCRIBED) draft.timeNote.trim() else "",
            "mediaAssetIds" to mediaAssetIds,
            "imageUrl" to imageUrl,
            "imageUrls" to imageUrls,
            "imagePublicId" to imagePublicId,
        )
    }

    /** Canonical posts/{postId} payload for initial creation (version=1, status=OPEN, moderationStatus=VISIBLE). */
    fun buildCreate(
        draft: PostFormState,
        author: Map<String, Any>,
        media: UploadedMedia?,
        createdAt: Any,
    ): Map<String, Any?> = sharedContentFields(draft, media) + mapOf(
        "type" to draft.type.name,
        "status" to "OPEN",
        "moderationStatus" to "VISIBLE",
        "version" to 1L,
        "createdAt" to createdAt,
        "updatedAt" to createdAt,
    ) + author

    /**
     * Canonical partial field map for posts/{postId} update().
     * Only touches editable keys allowed by firestore.rules (never immutable keys or moderationReason/embedding).
     */
    fun buildUpdateFields(
        draft: PostFormState,
        nextVersion: Long,
        media: UploadedMedia?,
        updatedAt: Any,
    ): Map<String, Any?> {
        require(nextVersion >= 2L) { "Phiên bản cập nhật phải >= 2." }
        return sharedContentFields(draft, media) + mapOf(
            "updatedAt" to updatedAt,
            "version" to nextVersion,
        )
    }

    /** Canonical partial field map for status-only transition (`OPEN` | `RESOLVED` | `CLOSED`). */
    fun buildStatusUpdateFields(
        newStatus: String,
        nextVersion: Long,
        updatedAt: Any,
    ): Map<String, Any?> {
        require(newStatus in ALLOWED_STATUSES) { "Trạng thái không hợp lệ: $newStatus" }
        require(nextVersion >= 2L) { "Phiên bản cập nhật phải >= 2." }
        return mapOf(
            "status" to newStatus,
            "updatedAt" to updatedAt,
            "version" to nextVersion,
        )
    }

    /** Initial PENDING job sub-map required by firestore.rules for post_events/{eventId}. */
    fun buildInitialJobState(): Map<String, Any?> = mapOf(
        "status" to "PENDING",
        "attempts" to 0L,
        "claimToken" to null,
        "lockOwner" to null,
        "lockExpiresAt" to null,
        "nextRetryAt" to null,
        "lastError" to null,
    )

    /** Canonical post_events/{postId}_v{postVersion} payload (3 independent job maps, no root status). */
    fun buildEventPayload(
        postId: String,
        authorId: String,
        postVersion: Long,
        changeType: String,
        createdAt: Any,
        imageAssetRefs: List<String> = emptyList(),
    ): Map<String, Any?> {
        require(postId.isNotBlank()) { "postId không được để trống." }
        require(authorId.isNotBlank()) { "authorId không được để trống." }
        require(postVersion >= 1L) { "postVersion phải >= 1." }
        require(changeType in ALLOWED_EVENT_CHANGE_TYPES) { "changeType không hợp lệ: $changeType" }
        val cleanedRefs = imageAssetRefs.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(1)
        return mapOf(
            "postId" to postId,
            "authorId" to authorId,
            "postVersion" to postVersion,
            "changeType" to changeType,
            "createdAt" to createdAt,
            "imageAssetRefs" to cleanedRefs,
            "searchJob" to buildInitialJobState(),
            "notifyJob" to buildInitialJobState(),
            "mediaCleanupJob" to buildInitialJobState(),
        )
    }

    /** Canonical post_tombstones/{postId} payload written atomically with post deletion. */
    fun buildTombstone(authorId: String, deletedAt: Any): Map<String, Any?> = mapOf(
        "authorId" to authorId,
        "deletedAt" to deletedAt,
    )
}
