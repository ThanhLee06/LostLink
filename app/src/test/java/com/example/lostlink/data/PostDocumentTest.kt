package com.example.lostlink.data

import com.example.lostlink.ui.home.HomePost
import com.example.lostlink.ui.post.*
import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test

class PostDocumentTest {
    private val draft = PostFormState(type = PostType.FOUND, title = " Wallet ", categoryId = "wallet",
        description = " A black leather wallet ", areaChoice = AreaChoice.UNKNOWN)
    private fun author(allow: Boolean) = AuthorPostInfo.buildPayload("owner", "Student", "owner@ut.edu.vn", allow)

    @Test fun newlyCreatedPostCanBeReadByHomeAndDetailContract() {
        val timestamp = Timestamp(1_794_000_000L, 0)
        val data = PostDocument.build(draft, author(true), "https://res.cloudinary.com/test/image/upload/wallet.jpg", timestamp)
        val post = HomePost.fromDocument("post-id", data)
        assertEquals("Wallet", post.title)
        assertEquals("FOUND", post.type)
        assertEquals("owner", post.authorId)
        assertEquals("owner@ut.edu.vn", post.contactEmail)
        assertTrue(post.allowContact)
        assertEquals(1, post.images.size)
        assertEquals(timestamp.toDate().time, post.createdAt)
    }

    @Test fun privateAuthorEmailIsNotStoredInPublicPost() {
        val data = PostDocument.build(draft, author(false), null, Timestamp(1, 0))
        assertEquals("", data["contactEmail"])
        assertEquals(false, data["allowContact"])
        assertFalse(data.values.contains("owner@ut.edu.vn"))
        assertEquals(emptyList<String>(), data["imageUrls"])
    }

    @Test fun uncertainTimeAndAreaAreNotInvented() {
        val data = PostDocument.build(draft.copy(timeMode = TimeMode.DESCRIBED, timeNote = " Khoảng sáng nay "), author(false), null, Timestamp(1, 0))
        assertEquals("Khoảng sáng nay", data["occurredAt"])
        assertEquals("DESCRIBED", data["timeMode"])
        assertNull(data["exactAtMillis"])
        assertEquals("", data["areaId"])
        assertEquals("Không rõ khu vực", data["location"])
    }

    @Test fun exactTimeUsesVietnamTimezoneAndRetainsOriginalValue() {
        val data = PostDocument.build(draft.copy(timeMode = TimeMode.EXACT, exactAtMillis = 0), author(false), null, Timestamp(1, 0))
        assertEquals("07:00 - 01/01/1970", data["occurredAt"])
        assertEquals(0L, data["exactAtMillis"])
    }

    @Test fun postingTimestampIsProvidedByServerIndependentlyOfOccurrence() {
        val marker = Any()
        val data = PostDocument.build(draft.copy(timeMode = TimeMode.EXACT, exactAtMillis = 0), author(false), null, marker)
        assertSame(marker, data["createdAt"])
        assertEquals("OPEN", data["status"])
    }
}
