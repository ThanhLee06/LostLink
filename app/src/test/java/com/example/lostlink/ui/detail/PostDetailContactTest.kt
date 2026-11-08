package com.example.lostlink.ui.detail

import com.example.lostlink.ui.home.HomePost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostDetailContactTest {

    private fun samplePost(
        id: String = "post1",
        title: String = "Nhặt được ví da đen",
        authorId: String = "author123",
        authorName: String = "Nguyen Van A",
        contactEmail: String = "a.nguyen@ut.edu.vn",
        allowContact: Boolean = true
    ) = HomePost(
        id = id,
        title = title,
        type = "FOUND",
        location = "Thư viện Cơ sở 1",
        createdAt = System.currentTimeMillis(),
        authorId = authorId,
        authorName = authorName,
        contactEmail = contactEmail,
        allowContact = allowContact
    )

    @Test
    fun testViewerIsAuthor_shouldIdentifyAsAuthor() {
        val post = samplePost(authorId = "user_me")
        val currentUserId = "user_me"

        val isAuthor = !currentUserId.isNullOrBlank() && post.authorId.isNotBlank() && currentUserId == post.authorId
        assertTrue("Người xem trùng authorId thì phải là tác giả", isAuthor)
    }

    @Test
    fun testViewerIsNotAuthor_shouldAllowContactWhenPublic() {
        val post = samplePost(authorId = "user_other", allowContact = true, contactEmail = "other@ut.edu.vn")
        val currentUserId = "user_me"

        val isAuthor = !currentUserId.isNullOrBlank() && post.authorId.isNotBlank() && currentUserId == post.authorId
        assertFalse("Người xem khác authorId thì không phải là tác giả", isAuthor)

        val canShowContactButtons = !isAuthor && post.allowContact && post.contactEmail.isNotBlank()
        assertTrue("Hiển thị nút liên hệ khi không phải tác giả và email được công khai", canShowContactButtons)
    }

    @Test
    fun testViewerIsNotAuthor_shouldHideContactWhenPrivacyDisabled() {
        val post = samplePost(authorId = "user_other", allowContact = false, contactEmail = "other@ut.edu.vn")
        val currentUserId = "user_me"

        val isAuthor = !currentUserId.isNullOrBlank() && post.authorId.isNotBlank() && currentUserId == post.authorId
        assertFalse(isAuthor)

        val canShowContactButtons = !isAuthor && post.allowContact && post.contactEmail.isNotBlank()
        assertFalse("Không hiển thị nút liên hệ khi tác giả tắt quyền riêng tư", canShowContactButtons)
    }

    @Test
    fun testMailtoUriGeneration() {
        val post = samplePost(title = "Thẻ sinh viên Khoa CNTT", contactEmail = "sv@ut.edu.vn")
        val uri = "mailto:${post.contactEmail}"
        val subject = "LostLink - Liên hệ về bài đăng: ${post.title}"

        assertEquals("mailto:sv@ut.edu.vn", uri)
        assertTrue(subject.contains(post.title))
    }
}
