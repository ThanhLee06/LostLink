package com.example.lostlink.ui.post

import com.example.lostlink.ui.home.HomePost
import org.junit.Assert.*
import org.junit.Test

class PostEditDraftTest {
    private val post = HomePost("id", "Ví", "FOUND", "Thư viện", 123,
        areaId = "library", areaChoice = "KNOWN", locationNote = "Tầng 2",
        timeMode = "EXACT", exactAtMillis = 100, categoryId = "wallet")

    @Test fun keepsIdentityTypeAndTimeForExistingEditScreen() {
        val draft = editPostDraft(post, "Ví nâu", "wallet", post.location, "Ví có nhiều ngăn")
        assertEquals(post.id, draft.clientPostId)
        assertEquals(PostType.FOUND, draft.type)
        assertEquals("library", draft.areaId)
        assertEquals("Tầng 2", draft.locationNote)
        assertEquals(100L, draft.exactAtMillis)
        assertTrue(PostFormValidator.validate(draft).isEmpty)
    }

    @Test fun changedFreeTextLocationDoesNotKeepStaleAreaId() {
        val draft = editPostDraft(post, "Ví", "wallet", "Nhà xe", "Ví có nhiều ngăn")
        assertEquals(AreaChoice.OTHER, draft.areaChoice)
        assertEquals("Nhà xe", draft.areaOtherText)
        assertNull(draft.areaId)
        assertEquals("", draft.locationNote)
    }

    @Test fun canonicalValidationRejectsShortDescriptionsAndNormalizesCategoryAliases() {
        val draft = editPostDraft(post, "Điện thoại", "phone", post.location, "ngắn")
        assertEquals("electronics", draft.categoryId)
        assertNotNull(PostFormValidator.validate(draft).description)
        assertNotNull(PostFormValidator.validate(draft.copy(title = "x".repeat(81))).title)
    }
}
