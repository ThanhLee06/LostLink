package com.example.lostlink.ui.post

import com.example.lostlink.ui.home.HomePost
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class AuthorPostInfoTest {

    @Test
    fun buildPayload_whenAllowEmailContactIsTrue_includesEmail() {
        val payload = AuthorPostInfo.buildPayload(
            uid = "user123",
            displayName = "Minh Anh",
            email = "2151050001@ut.edu.vn",
            allowEmailContact = true
        )

        assertEquals("user123", payload["authorId"])
        assertEquals("Minh Anh", payload["authorName"])
        assertEquals("2151050001@ut.edu.vn", payload["contactEmail"])
        assertEquals(true, payload["allowContact"])
    }

    @Test
    fun buildPayload_whenAllowEmailContactIsFalse_hidesEmail() {
        val payload = AuthorPostInfo.buildPayload(
            uid = "user123",
            displayName = "Minh Anh",
            email = "2151050001@ut.edu.vn",
            allowEmailContact = false
        )

        assertEquals("user123", payload["authorId"])
        assertEquals("Minh Anh", payload["authorName"])
        assertEquals("", payload["contactEmail"])
        assertEquals(false, payload["allowContact"])
    }

    @Test
    fun buildPayload_whenDisplayNameIsBlank_usesEmailPrefix() {
        val payload = AuthorPostInfo.buildPayload(
            uid = "user123",
            displayName = "   ",
            email = "2151050001@ut.edu.vn",
            allowEmailContact = true
        )

        assertEquals("2151050001", payload["authorName"])
    }

    @Test
    fun homePost_fromDocument_parsesAuthorAndContactInfo() {
        val docData = mapOf(
            "title" to "Ví da",
            "type" to "LOST",
            "createdAt" to Timestamp(Date()),
            "authorId" to "uid1",
            "authorName" to "Hoàng Nam",
            "contactEmail" to "nam@ut.edu.vn",
            "allowContact" to true
        )

        val post = HomePost.fromDocument("post1", docData)

        assertEquals("Hoàng Nam", post.authorName)
        assertEquals("nam@ut.edu.vn", post.contactEmail)
        assertTrue(post.allowContact)
        assertEquals("uid1", post.authorId)
    }

    @Test
    fun homePost_fromDocument_whenContactDisabled_respectsPrivacy() {
        val docData = mapOf(
            "title" to "Ví da",
            "type" to "LOST",
            "createdAt" to Timestamp(Date()),
            "authorId" to "uid1",
            "authorName" to "Hoàng Nam",
            "contactEmail" to "",
            "allowContact" to false
        )

        val post = HomePost.fromDocument("post1", docData)

        assertEquals("Hoàng Nam", post.authorName)
        assertEquals("", post.contactEmail)
        assertFalse(post.allowContact)
    }
}
