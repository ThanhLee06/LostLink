package com.example.lostlink.ui.home

import com.example.lostlink.ui.detail.PostDetail
import com.example.lostlink.ui.post.PostAreas
import com.example.lostlink.ui.post.PostCategories
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaxonomyAndReadProjectionTest {

    @Test
    fun canonicalCategoriesMatchContract() {
        assertEquals(
            listOf("wallet", "student_card", "electronics", "keys", "bag", "documents", "other"),
            PostCategories.all.map { it.id },
        )
        assertEquals(HomeCategory.entries.map { it.id }.toSet(), PostCategories.all.map { it.id }.toSet())
    }

    @Test
    fun canonicalAreasMatchContractAndExcludeDuplicateAuditoriumCase() {
        assertEquals(
            listOf(
                "campus_1", "campus_2", "campus_3", "dormitory",
                "a", "b", "c", "d", "e",
                "canteen", "library", "parking", "auditorium",
            ),
            PostAreas.all.map { it.id },
        )
        assertEquals(HomeArea.entries.map { it.id }.toSet(), PostAreas.all.map { it.id }.toSet())
        assertTrue(HomeArea.AUDITORIUM.id == "auditorium")
    }

    @Test
    fun legacyAliasesResolveCleanlyAndNeverMapDocumentsToStudentCard() {
        assertEquals("electronics", PostCategories.normalizeId("phone"))
        assertEquals("electronics", PostCategories.normalizeId("laptop"))
        assertEquals("documents", PostCategories.normalizeId("documents"))
        assertNotEquals("student_card", PostCategories.normalizeId("documents"))

        assertEquals("auditorium", PostAreas.normalizeId("Auditorium"))
        assertNull(PostAreas.normalizeId("Campus_99"))
        assertNull(PostCategories.normalizeId("unknown_category"))
    }

    @Test
    fun homePostNormalizesLegacyAuditoriumAndStatusAndIgnoresModerationReason() {
        val raw = mapOf<String, Any?>(
            "title" to "Laptop Dell",
            "type" to "LOST",
            "status" to "ACTIVE",
            "areaId" to "Auditorium",
            "categoryId" to "phone",
            "moderationStatus" to "VISIBLE",
            "moderationReason" to "SHOULD_NOT_BE_READ_FROM_POSTS",
            "createdAt" to Timestamp(1_794_000_000L, 0),
        )
        val post = HomePost.fromDocument("doc-1", raw)
        assertEquals("OPEN", post.status)
        assertEquals("auditorium", post.areaId)
        assertEquals("VISIBLE", post.moderationStatus)

        val detail = PostDetail.fromDocument("doc-1", raw)
        assertEquals("Điện thoại/Laptop", detail.category)
        assertNull(detail.moderationReason)

        val docsDetail = PostDetail.fromDocument(
            "doc-2",
            raw + mapOf("categoryId" to "documents", "category" to null),
        )
        assertEquals("Giấy tờ", docsDetail.category)
    }
}
