package com.example.lostlink.data

import com.example.lostlink.ui.home.HomePost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSavedPostsBackend : SavedPostsBackend {
    val availablePosts: MutableMap<String, HomePost> = mutableMapOf()
    val savedByUser: MutableMap<String, LinkedHashMap<String, SavedPostEntry>> = mutableMapOf()
    var saveGate: CompletableDeferred<Unit>? = null
    var saveCallCount = 0
    var failNextSaveWith: Throwable? = null
    var failNextUnsaveWith: Throwable? = null
    private val listeners = mutableMapOf<String, MutableList<(List<SavedPostEntry>) -> Unit>>()

    override fun observeSavedEntries(
        uid: String,
        onUpdate: (List<SavedPostEntry>) -> Unit,
        onError: (Throwable) -> Unit,
    ): () -> Unit {
        val list = listeners.getOrPut(uid) { mutableListOf() }
        list += onUpdate
        onUpdate(savedByUser[uid]?.values?.toList().orEmpty().reversed())
        return { list.remove(onUpdate) }
    }

    override suspend fun checkPostAvailableForSave(postId: String): Result<HomePost> {
        val post = availablePosts[postId]
            ?: return Result.failure(IllegalStateException("Bài đăng không tồn tại hoặc đã bị xóa."))
        if (post.isHidden) {
            return Result.failure(IllegalStateException("Bài đăng này đã bị ẩn và không còn khả dụng."))
        }
        if (!post.isVisibleOnFeed) {
            return Result.failure(IllegalStateException("Bài đăng này không còn khả dụng để lưu."))
        }
        return Result.success(post)
    }

    override suspend fun savePost(uid: String, post: HomePost): Result<Unit> {
        saveCallCount++
        saveGate?.await()
        failNextSaveWith?.let { err ->
            failNextSaveWith = null
            return Result.failure(err)
        }
        val map = savedByUser.getOrPut(uid) { linkedMapOf() }
        map[post.id] = SavedPostEntry(post.id, System.currentTimeMillis(), post.title)
        notify(uid)
        return Result.success(Unit)
    }

    override suspend fun unsavePost(uid: String, postId: String): Result<Unit> {
        failNextUnsaveWith?.let { err ->
            failNextUnsaveWith = null
            return Result.failure(err)
        }
        savedByUser[uid]?.remove(postId)
        notify(uid)
        return Result.success(Unit)
    }

    override suspend fun fetchAccessibleSavedPosts(entries: List<SavedPostEntry>): List<HomePost> {
        return entries.mapNotNull { entry ->
            availablePosts[entry.postId]?.takeIf { it.isVisibleOnFeed }
        }
    }

    private fun notify(uid: String) {
        val snapshot = savedByUser[uid]?.values?.toList().orEmpty().reversed()
        listeners[uid]?.forEach { it(snapshot) }
    }
}

class SavedPostsRepositoryTest {
    private fun samplePost(
        id: String = "post-1",
        status: String = "OPEN",
        moderationStatus: String = "VISIBLE",
    ) = HomePost(
        id = id,
        title = "Ví da đen",
        type = "LOST",
        location = "Thư viện",
        createdAt = 1_700_000_000_000L,
        status = status,
        moderationStatus = moderationStatus,
    )

    @Test
    fun toggleSave_savesAndUnsavesSynchronouslyAcrossObservers() = runTest {
        val backend = FakeSavedPostsBackend()
        backend.availablePosts["post-1"] = samplePost("post-1")
        val repo = SavedPostsRepository(backend = backend, activeUidProvider = { "uid-1" })

        val saveResult = repo.toggleSave("post-1", "Ví da đen")
        assertTrue(saveResult.isSuccess)
        assertEquals(true, saveResult.getOrNull())
        assertTrue("post-1" in repo.savedPostIds.value)

        val unsaveResult = repo.toggleSave("post-1")
        assertTrue(unsaveResult.isSuccess)
        assertEquals(false, unsaveResult.getOrNull())
        assertFalse("post-1" in repo.savedPostIds.value)
    }

    @Test
    fun toggleSave_blocksRapidRepeatedClicksWhilePending() = runTest {
        val backend = FakeSavedPostsBackend()
        backend.availablePosts["post-1"] = samplePost("post-1")
        val gate = CompletableDeferred<Unit>()
        backend.saveGate = gate
        val repo = SavedPostsRepository(backend = backend, activeUidProvider = { "uid-1" })

        val first = async { repo.toggleSave("post-1") }
        kotlinx.coroutines.yield()
        assertTrue("post-1" in repo.pendingPostIds.value)

        val second = repo.toggleSave("post-1")
        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull() is SaveInProgressException)

        gate.complete(Unit)
        assertTrue(first.await().isSuccess)
        assertFalse("post-1" in repo.pendingPostIds.value)
        assertEquals(1, backend.saveCallCount)
    }

    @Test
    fun toggleSave_rollsBackOptimisticStateOnFirebaseError() = runTest {
        val backend = FakeSavedPostsBackend()
        backend.availablePosts["post-1"] = samplePost("post-1")
        backend.failNextSaveWith = IllegalStateException("Network error")
        val repo = SavedPostsRepository(backend = backend, activeUidProvider = { "uid-1" })

        val saveFailure = repo.toggleSave("post-1")
        assertTrue(saveFailure.isFailure)
        assertFalse("post-1" in repo.savedPostIds.value)
        assertFalse("post-1" in repo.pendingPostIds.value)

        assertTrue(repo.toggleSave("post-1").isSuccess)
        assertTrue("post-1" in repo.savedPostIds.value)

        backend.failNextUnsaveWith = IllegalStateException("Network error")
        val unsaveFailure = repo.toggleSave("post-1")
        assertTrue(unsaveFailure.isFailure)
        assertTrue("post-1" in repo.savedPostIds.value)
    }

    @Test
    fun toggleSave_rejectsDeletedHiddenAndClosedPosts() = runTest {
        val backend = FakeSavedPostsBackend()
        backend.availablePosts["hidden"] = samplePost("hidden", moderationStatus = "HIDDEN")
        backend.availablePosts["closed"] = samplePost("closed", status = "CLOSED")
        val repo = SavedPostsRepository(backend = backend, activeUidProvider = { "uid-1" })

        assertTrue(repo.toggleSave("deleted").isFailure)
        assertTrue(repo.toggleSave("hidden").isFailure)
        assertTrue(repo.toggleSave("closed").isFailure)
        assertTrue(repo.savedPostIds.value.isEmpty())
    }

    @Test
    fun loadAccessibleSavedPosts_filtersOutDeletedHiddenAndClosedPosts() = runTest {
        val backend = FakeSavedPostsBackend()
        backend.availablePosts["valid"] = samplePost("valid")
        backend.availablePosts["hidden"] = samplePost("hidden", moderationStatus = "HIDDEN")
        backend.availablePosts["closed"] = samplePost("closed", status = "CLOSED")
        backend.savedByUser["uid-1"] = linkedMapOf(
            "valid" to SavedPostEntry("valid", 4L),
            "deleted" to SavedPostEntry("deleted", 3L),
            "hidden" to SavedPostEntry("hidden", 2L),
            "closed" to SavedPostEntry("closed", 1L),
        )
        val repo = SavedPostsRepository(backend = backend, activeUidProvider = { "uid-1" })
        repo.bindUser("uid-1")

        val posts = repo.loadAccessibleSavedPosts()
        assertEquals(listOf("valid"), posts.map { it.id })
    }

    @Test
    fun bindUser_isolatesSavedPostsPerAccount() = runTest {
        val backend = FakeSavedPostsBackend()
        backend.savedByUser["uid-1"] = linkedMapOf("post-a" to SavedPostEntry("post-a", 1L))
        backend.savedByUser["uid-2"] = linkedMapOf("post-b" to SavedPostEntry("post-b", 1L))
        val repo = SavedPostsRepository(backend = backend, activeUidProvider = { "uid-1" })

        repo.bindUser("uid-1")
        assertEquals(setOf("post-a"), repo.savedPostIds.value)

        repo.bindUser("uid-2")
        assertEquals(setOf("post-b"), repo.savedPostIds.value)

        repo.bindUser(null)
        assertTrue(repo.savedPostIds.value.isEmpty())
    }
}

