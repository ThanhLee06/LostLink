package com.example.lostlink.ui.home

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedPaginationTest {

    private data class RawDoc(val id: String, val data: Map<String, Any?>)

    private fun validDoc(id: String, status: String = "OPEN", moderationStatus: String = "VISIBLE") = RawDoc(
        id = id,
        data = mapOf(
            "title" to "Title $id",
            "type" to "LOST",
            "location" to "Thư viện",
            "status" to status,
            "moderationStatus" to moderationStatus,
            "createdAt" to Timestamp(1_794_000_000L, 0),
        ),
    )

    private fun malformedDoc(id: String) = RawDoc(
        id = id,
        data = mapOf("title" to "", "type" to "INVALID"),
    )

    @Test
    fun pageRetainsLookaheadAndSetsCursorFromLastConsumedDoc() {
        val rawDocs = (1..21).map { idx -> validDoc("doc-$idx") }
        val page = parseFeedPage(
            rawDocs = rawDocs,
            pageSize = 20,
            extractId = { it.id },
            extractData = { it.data },
        )
        assertEquals(20, page.posts.size)
        assertTrue(page.hasMore)
        assertEquals("doc-20", page.cursorDoc?.id)
        assertFalse(page.posts.any { it.id == "doc-21" })
    }

    @Test
    fun malformedPageAdvancesCursorAcrossEmptyValidResultAndRetainsHasMore() {
        // 20 malformed docs + 1 lookahead doc #21 -> valid list is empty, yet cursor MUST advance to doc-20
        val rawDocs = (1..20).map { malformedDoc("bad-$it") } + validDoc("lookahead-21")
        val page = parseFeedPage(
            rawDocs = rawDocs,
            pageSize = 20,
            extractId = { it.id },
            extractData = { it.data },
        )
        assertTrue(page.posts.isEmpty())
        assertEquals(20, page.malformedCount)
        assertTrue(page.hasMore)
        assertEquals("bad-20", page.cursorDoc?.id)

        val nextState = applyFeedPageToState(HomeState(allowed = true, loading = true), more = false, page = page)
        assertFalse(nextState.loading)
        assertTrue(nextState.hasMore)
        assertNull(nextState.error)
        assertTrue(nextState.posts.isEmpty())
    }

    @Test
    fun allMalformedTerminalPageProducesErrorInsteadOfClaimingEmptyFeed() {
        val rawDocs = listOf(malformedDoc("bad-1"), malformedDoc("bad-2"))
        val page = parseFeedPage(
            rawDocs = rawDocs,
            pageSize = 20,
            extractId = { it.id },
            extractData = { it.data },
        )
        assertFalse(page.hasMore)
        assertEquals(2, page.malformedCount)
        val nextState = applyFeedPageToState(HomeState(allowed = true, loading = true), more = false, page = page)
        assertNotNull(nextState.error)
        assertFalse(nextState.hasMore)
    }

    @Test
    fun filtersOutClosedAndHiddenDocsEvenIfMixedIntoPage() {
        val rawDocs = listOf(
            validDoc("open-1", "OPEN", "VISIBLE"),
            validDoc("resolved-1", "RESOLVED", "VISIBLE"),
            validDoc("closed-1", "CLOSED", "VISIBLE"),
            validDoc("hidden-1", "OPEN", "HIDDEN"),
            malformedDoc("bad-1"),
        )
        val page = parseFeedPage(
            rawDocs = rawDocs,
            pageSize = 20,
            extractId = { it.id },
            extractData = { it.data },
        )
        assertEquals(listOf("open-1", "resolved-1"), page.posts.map { it.id })
        assertEquals("bad-1", page.cursorDoc?.id)
        assertEquals(1, page.malformedCount)
        assertFalse(page.hasMore)
    }
}
