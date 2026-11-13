package com.example.lostlink.data

import com.example.lostlink.ui.home.HomePost
import com.example.lostlink.ui.post.*
import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test

class PostDocumentTest {
    private val draft = PostFormState(
        type = PostType.FOUND,
        title = " Wallet ",
        categoryId = "wallet",
        description = " A black leather wallet ",
        areaChoice = AreaChoice.UNKNOWN,
    )

    private fun author(allow: Boolean) = AuthorPostInfo.buildPayload("owner", "Student", "owner@ut.edu.vn", allow)

    private val sampleMedia = UploadedMedia(
        assetId = "asset-123",
        secureUrl = "https://res.cloudinary.com/test/image/upload/wallet.jpg",
        publicId = "lostlink/posts/post-id/asset-123",
    )

    @Test fun newlyCreatedPostCanBeReadByHomeAndDetailContract() {
        val timestamp = Timestamp(1_794_000_000L, 0)
        val data = PostDocument.buildCreate(draft, author(true), sampleMedia, timestamp)
        val post = HomePost.fromDocument("post-id", data)
        assertEquals("Wallet", post.title)
        assertEquals("FOUND", post.type)
        assertEquals("owner", post.authorId)
        assertEquals("owner@ut.edu.vn", post.contactEmail)
        assertTrue(post.allowContact)
        assertEquals(1, post.images.size)
        assertEquals(listOf("asset-123"), post.mediaAssetIds)
        assertEquals("lostlink/posts/post-id/asset-123", post.imagePublicId)
        assertEquals(timestamp.toDate().time, post.createdAt)
        assertEquals("VISIBLE", post.moderationStatus)
        assertEquals(1, post.version)
        assertFalse(data.containsKey("moderationReason"))
        assertFalse(data.containsKey("embedding"))
    }

    @Test fun privateAuthorEmailIsNotStoredInPublicPost() {
        val data = PostDocument.buildCreate(draft, author(false), null, Timestamp(1, 0))
        assertEquals("", data["contactEmail"])
        assertEquals(false, data["allowContact"])
        assertFalse(data.values.contains("owner@ut.edu.vn"))
        assertEquals(emptyList<String>(), data["mediaAssetIds"])
        assertNull(data["imageUrl"])
        assertEquals(emptyList<String>(), data["imageUrls"])
        assertNull(data["imagePublicId"])
    }

    @Test fun uncertainTimeAndAreaAreNotInvented() {
        val data = PostDocument.buildCreate(
            draft.copy(timeMode = TimeMode.DESCRIBED, timeNote = " Khoảng sáng nay "),
            author(false),
            null,
            Timestamp(1, 0),
        )
        assertEquals("Khoảng sáng nay", data["occurredAt"])
        assertEquals("DESCRIBED", data["timeMode"])
        assertNull(data["exactAtMillis"])
        assertEquals("", data["areaId"])
        assertEquals("Không rõ khu vực", data["location"])
    }

    @Test fun exactTimeUsesVietnamTimezoneAndRetainsOriginalValue() {
        val data = PostDocument.buildCreate(
            draft.copy(timeMode = TimeMode.EXACT, exactAtMillis = 0),
            author(false),
            null,
            Timestamp(1, 0),
        )
        assertEquals("07:00 - 01/01/1970", data["occurredAt"])
        assertEquals(0L, data["exactAtMillis"])
    }

    @Test fun postingTimestampIsProvidedByServerIndependentlyOfOccurrence() {
        val marker = Any()
        val data = PostDocument.buildCreate(
            draft.copy(timeMode = TimeMode.EXACT, exactAtMillis = 0),
            author(false),
            null,
            marker,
        )
        assertSame(marker, data["createdAt"])
        assertSame(marker, data["updatedAt"])
        assertEquals("OPEN", data["status"])
        assertEquals("VISIBLE", data["moderationStatus"])
        assertEquals(1L, data["version"])
    }

    @Test fun buildUpdateFieldsExcludesImmutableAndRestrictedKeys() {
        val marker = Any()
        val update = PostDocument.buildUpdateFields(
            draft.copy(
                areaChoice = AreaChoice.KNOWN,
                areaId = "auditorium",
                title = "Updated Title",
            ),
            nextVersion = 2L,
            media = null,
            updatedAt = marker,
        )
        assertEquals(2L, update["version"])
        assertSame(marker, update["updatedAt"])
        assertEquals("auditorium", update["areaId"])
        assertFalse(update.containsKey("type"))
        assertFalse(update.containsKey("status"))
        assertFalse(update.containsKey("moderationStatus"))
        assertFalse(update.containsKey("moderationReason"))
        assertFalse(update.containsKey("embedding"))
        assertFalse(update.containsKey("authorId"))
        assertFalse(update.containsKey("createdAt"))
    }

    @Test fun buildStatusUpdateFieldsOnlyTouchesStatusUpdatedAtAndVersion() {
        val marker = Any()
        val update = PostDocument.buildStatusUpdateFields("RESOLVED", 3L, marker)
        assertEquals(setOf("status", "updatedAt", "version"), update.keys)
        assertEquals("RESOLVED", update["status"])
        assertEquals(3L, update["version"])
        assertSame(marker, update["updatedAt"])
    }

    @Test fun buildEventPayloadInitializesThreePendingJobMapsWithoutRootStatus() {
        val marker = Any()
        val event = PostDocument.buildEventPayload(
            postId = "post-1",
            authorId = "uid-1",
            postVersion = 2L,
            changeType = "DELETE",
            createdAt = marker,
            imageAssetRefs = listOf("asset-123"),
        )
        assertFalse(event.containsKey("status"))
        assertEquals("post-1", event["postId"])
        assertEquals("uid-1", event["authorId"])
        assertEquals(2L, event["postVersion"])
        assertEquals("DELETE", event["changeType"])
        assertSame(marker, event["createdAt"])
        assertEquals(listOf("asset-123"), event["imageAssetRefs"])

        for (jobKey in listOf("searchJob", "notifyJob", "mediaCleanupJob")) {
            @Suppress("UNCHECKED_CAST")
            val job = event[jobKey] as Map<String, Any?>
            assertEquals("PENDING", job["status"])
            assertEquals(0L, job["attempts"])
            assertNull(job["claimToken"])
            assertNull(job["lockOwner"])
            assertNull(job["lockExpiresAt"])
            assertNull(job["nextRetryAt"])
            assertNull(job["lastError"])
        }
    }

    @Test fun canonicalWriterRejectsLegacyPhoneCategoryAndCapitalAuditorium() {
        assertThrows(IllegalArgumentException::class.java) {
            PostDocument.buildCreate(
                draft.copy(categoryId = "phone"),
                author(false),
                null,
                Timestamp(1, 0),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PostDocument.buildCreate(
                draft.copy(areaChoice = AreaChoice.KNOWN, areaId = "Auditorium"),
                author(false),
                null,
                Timestamp(1, 0),
            )
        }
    }
}
