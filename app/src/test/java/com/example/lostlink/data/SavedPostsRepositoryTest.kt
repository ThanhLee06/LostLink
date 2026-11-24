package com.example.lostlink.data

import com.example.lostlink.ui.home.HomePost
import com.example.lostlink.ui.saved.SavedPostItem
import com.example.lostlink.ui.saved.SavedPostsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeSavedPostsBackend : SavedPostsBackend {
    val availablePosts: MutableMap<String, HomePost> = mutableMapOf()
    val savedByUser: MutableMap<String, LinkedHashMap<String, SavedPostEntry>> = mutableMapOf()
    var saveGate: CompletableDeferred<Unit>? = null
    var saveCallCount = 0
    var failNextSaveWith: Throwable? = null
    var failNextUnsaveWith: Throwable? = null
    private val entryListeners = mutableMapOf<String, MutableList<(List<SavedPostEntry>) -> Unit>>()
    private val postListeners = mutableMapOf<String, MutableList<(SavedPostResolution) -> Unit>>()

    override fun observeSavedEntries(
        uid: String,
        onUpdate: (List<SavedPostEntry>) -> Unit,
        onError: (Throwable) -> Unit,
    ): () -> Unit {
        val list = entryListeners.getOrPut(uid) { mutableListOf() }
        list += onUpdate
        onUpdate(savedByUser[uid]?.values?.toList().orEmpty().reversed())
        return { list.remove(onUpdate) }
    }

    override fun observePost(
        postId: String,
        onResult: (SavedPostResolution) -> Unit,
    ): () -> Unit {
        val list = postListeners.getOrPut(postId) { mutableListOf() }
        list += onResult
        onResult(resolvePost(postId))
        return { list.remove(onResult) }
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
        if (post.id !in map) {
            map[post.id] = SavedPostEntry(post.id, System.currentTimeMillis())
        }
        notifyEntries(uid)
        return Result.success(Unit)
    }

    override suspend fun unsavePost(uid: String, postId: String): Result<Unit> {
        failNextUnsaveWith?.let { err ->
            failNextUnsaveWith = null
            return Result.failure(err)
        }
        savedByUser[uid]?.remove(postId)
        notifyEntries(uid)
        return Result.success(Unit)
    }

    fun updatePost(post: HomePost) {
        availablePosts[post.id] = post
        val resolution = resolvePost(post.id)
        postListeners[post.id]?.forEach { it(resolution) }
    }

    fun deletePost(postId: String) {
        availablePosts.remove(postId)
        postListeners[postId]?.forEach { it(SavedPostResolution.Unavailable(postId)) }
    }

    fun emitPostError(postId: String, message: String) {
        postListeners[postId]?.forEach { it(SavedPostResolution.Error(postId, message)) }
    }

    private fun resolvePost(postId: String): SavedPostResolution {
        val post = availablePosts[postId] ?: return SavedPostResolution.Unavailable(postId)
        return if (post.isVisibleOnFeed) SavedPostResolution.Available(post)
        else SavedPostResolution.Unavailable(postId)
    }

    private fun notifyEntries(uid: String) {
        val snapshot = savedByUser[uid]?.values?.toList().orEmpty().reversed()
        entryListeners[uid]?.forEach { it(snapshot) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SavedPostsRepositoryTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun samplePost(
        id: String = "post-1",
        title: String = "Ví da đen",
        status: String = "OPEN",
        moderationStatus: String = "VISIBLE",
    ) = HomePost(
        id = id,
        title = title,
        type = "LOST",
        location = "Thư viện",
        createdAt = 1_700_000_000_000L,
        status = status,
        moderationStatus = moderationStatus,
    )

    @Test
    fun toggleSave_savesAndUnsavesSynchronouslyAcrossObservers() = runTest(testDispatcher) {
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
    fun toggleSave_blocksRapidRepeatedClicksWhilePending() = runTest(testDispatcher) {
        val backend = FakeSavedPostsBackend()
        backend.availablePosts["post-1"] = samplePost("post-1")
        val gate = CompletableDeferred<Unit>()
        backend.saveGate = gate
        val repo = SavedPostsRepository(backend = backend, activeUidProvider = { "uid-1" })

        val first = async { repo.toggleSave("post-1") }
        testDispatcher.scheduler.advanceUntilIdle()
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
    fun toggleSave_rollsBackOptimisticStateOnFirebaseError() = runTest(testDispatcher) {
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
    fun toggleSave_rejectsDeletedHiddenAndClosedPosts() = runTest(testDispatcher) {
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
    fun savedPostsViewModel_syncsEditsResolvedAndUnavailableInRealTime() = runTest(testDispatcher) {
        val backend = FakeSavedPostsBackend()
        backend.availablePosts["p1"] = samplePost("p1", title = "Tiêu đề cũ", status = "OPEN")
        backend.availablePosts["p2"] = samplePost("p2", title = "Bài bị ẩn", moderationStatus = "HIDDEN")
        backend.savedByUser["uid-1"] = linkedMapOf(
            "p2" to SavedPostEntry("p2", 1L),
            "p1" to SavedPostEntry("p1", 2L),
        )
        val repo = SavedPostsRepository(backend = backend, activeUidProvider = { "uid-1" })
        val vm = SavedPostsViewModel(repository = repo)

        vm.bindUser("uid-1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, vm.state.value.items.size)
        assertTrue(vm.state.value.items[0] is SavedPostItem.Available)
        assertEquals("Tiêu đề cũ", (vm.state.value.items[0] as SavedPostItem.Available).post.title)
        assertTrue(vm.state.value.items[1] is SavedPostItem.Unavailable)

        // Author edits title & marks RESOLVED
        backend.updatePost(samplePost("p1", title = "Tiêu đề mới", status = "RESOLVED"))
        testDispatcher.scheduler.advanceUntilIdle()
        val updated = vm.state.value.items[0] as SavedPostItem.Available
        assertEquals("Tiêu đề mới", updated.post.title)
        assertTrue(updated.post.isResolved)

        // Admin hides p1 -> immediately becomes Unavailable (no leaked data)
        backend.updatePost(samplePost("p1", title = "Tiêu đề mới", moderationStatus = "HIDDEN"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.items[0] is SavedPostItem.Unavailable)

        // Remove unavailable p2 -> only removes from savedPosts, original untouched
        vm.removeSavedPost("p2")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("p1"), vm.state.value.items.map { it.postId })
    }

    @Test
    fun savedPostsViewModel_distinguishesNetworkErrorFromUnavailable() = runTest(testDispatcher) {
        val backend = FakeSavedPostsBackend()
        backend.availablePosts["p1"] = samplePost("p1")
        backend.savedByUser["uid-1"] = linkedMapOf("p1" to SavedPostEntry("p1", 1L))
        val repo = SavedPostsRepository(backend = backend, activeUidProvider = { "uid-1" })
        val vm = SavedPostsViewModel(repository = repo)

        vm.bindUser("uid-1")
        testDispatcher.scheduler.advanceUntilIdle()

        backend.emitPostError("p1", "Không thể kết nối máy chủ.")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Không thể kết nối máy chủ.", vm.state.value.error)
        assertTrue(vm.state.value.items.isEmpty())
    }
}
