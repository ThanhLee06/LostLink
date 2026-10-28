package com.example.lostlink

import androidx.lifecycle.ViewModelStore
import com.example.lostlink.ui.home.*
import org.junit.Assert.*
import org.junit.Test

class FeedRefreshTest {
    private val old = HomePost("old", "Ví nâu", "LOST", "Thư viện", 100)
    private val fresh = old.copy(id = "new", title = "Chìa khóa", createdAt = 200)
    private val cursor = HomeCursor(100, 123, "old")

    @Test fun firstLoadAndEmptySuccessHaveDifferentStatesFromFailure() {
        val source = FakeSource()
        val model = HomeViewModel(source)
        assertTrue(model.state.loading)
        source.finish(0, HomePage(emptyList()))
        assertFalse(model.state.loading)
        assertTrue(model.state.posts.isEmpty())
        assertNull(model.state.error)
        model.load()
        source.fail(1)
        assertNotNull(model.state.error)
        assertFalse(model.state.loading)
    }

    @Test fun repeatedRefreshAndLoadMoreAreIgnoredWhileRequestIsRunning() {
        val source = FakeSource()
        val model = HomeViewModel(source)
        repeat(10) { model.load(); model.load(true) }
        assertEquals(1, source.requests.size)
        source.finish(0, HomePage(listOf(old), cursor))
        model.load()
        repeat(10) { model.load(); model.load(true) }
        assertEquals(2, source.requests.size)
    }

    @Test fun refreshReplacesOldPageAndDoesNotDuplicateIds() {
        val source = FakeSource()
        val model = HomeViewModel(source)
        source.finish(0, HomePage(listOf(old), cursor))
        model.load()
        assertEquals(listOf(old), model.state.posts)
        assertNull(source.requests[1].after)
        source.finish(1, HomePage(listOf(fresh, old, old)))
        assertEquals(listOf("new", "old"), model.state.posts.map { it.id })
        assertFalse(model.state.hasMore)
    }

    @Test fun failedRefreshKeepsOldPostsAndExplainsTheyAreNotUpdated() {
        val source = FakeSource()
        val model = HomeViewModel(source)
        source.finish(0, HomePage(listOf(old), cursor))
        model.load()
        source.fail(1)
        assertEquals(listOf(old), model.state.posts)
        assertTrue(model.state.error.orEmpty().contains("đã tải trước đó"))
        model.retry()
        assertNull(source.requests[2].after)
        source.finish(2, HomePage(listOf(fresh)))
        assertEquals(listOf(fresh), model.state.posts)
        assertNull(model.state.error)
    }

    @Test fun failedNextPageRetriesTheSameCursorWithoutDuplicates() {
        val source = FakeSource()
        val model = HomeViewModel(source)
        source.finish(0, HomePage(listOf(old), cursor))
        model.load(true)
        source.fail(1)
        model.retry()
        assertEquals(cursor, source.requests[2].after)
        source.finish(2, HomePage(listOf(old, fresh)))
        assertEquals(listOf("old", "new"), model.state.posts.map { it.id })
    }

    @Test fun oldAccountResponseCannotOverwriteNewAccount() {
        val source = FakeSource()
        val model = HomeViewModel(source)
        source.account("account-b")
        assertTrue(model.state.posts.isEmpty())
        source.finish(1, HomePage(listOf(fresh)))
        source.finish(0, HomePage(listOf(old)))
        assertEquals(listOf(fresh), model.state.posts)
    }

    @Test fun signOutClearsPostsAndRejectsLateResponses() {
        val source = FakeSource()
        val model = HomeViewModel(source)
        source.finish(0, HomePage(listOf(old), cursor))
        model.load()
        source.account(null)
        source.finish(1, HomePage(listOf(fresh)))
        assertFalse(model.state.allowed)
        assertTrue(model.state.posts.isEmpty())
        assertFalse(model.state.loading)
        assertFalse(model.state.hasMore)
    }

    @Test fun signOutAndSignInToSameUidStillInvalidatesOldRequest() {
        val source = FakeSource()
        val model = HomeViewModel(source)
        source.account(null)
        source.account("account-a")
        source.finish(1, HomePage(listOf(fresh)))
        source.finish(0, HomePage(listOf(old)))
        assertEquals(listOf(fresh), model.state.posts)
    }

    @Test fun authIdentityIsCheckedEvenBeforeAccountObserverRuns() {
        val source = FakeSource()
        val model = HomeViewModel(source)
        source.activeUserId = "account-b"
        source.finish(0, HomePage(listOf(old)))
        assertTrue(model.state.posts.isEmpty())
        source.account("account-b")
        source.finish(1, HomePage(listOf(fresh)))
        assertEquals(listOf(fresh), model.state.posts)
    }

    @Test fun clearingViewModelDetachesObserverAndIgnoresCompletion() {
        val source = FakeSource()
        val model = HomeViewModel(source)
        val store = ViewModelStore()
        store.put("feed", model)
        store.clear()
        assertTrue(source.detached)
        source.finish(0, HomePage(listOf(old)))
        assertTrue(model.state.posts.isEmpty())
    }

    private class FakeSource : HomeFeedSource {
        override var activeUserId: String? = "account-a"
        var detached = false
        private var observer: ((String?) -> Unit)? = null
        data class Request(val after: HomeCursor?, val complete: (Result<HomePage>) -> Unit)
        val requests = mutableListOf<Request>()
        override fun observeAccount(onChanged: (String?) -> Unit): () -> Unit {
            observer = onChanged
            onChanged(activeUserId)
            return { detached = true; observer = null }
        }
        override fun loadPage(after: HomeCursor?, onComplete: (Result<HomePage>) -> Unit) {
            requests += Request(after, onComplete)
        }
        fun account(uid: String?) { activeUserId = uid; observer?.invoke(uid) }
        fun finish(index: Int, page: HomePage) = requests[index].complete(Result.success(page))
        fun fail(index: Int) = requests[index].complete(Result.failure(IllegalStateException("Offline")))
    }
}
