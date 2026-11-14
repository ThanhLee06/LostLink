package com.example.lostlink.ui.myposts

import com.example.lostlink.data.MyPostsDataSource
import com.example.lostlink.ui.home.HomePost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MyPostsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun prepareMain() = Dispatchers.setMain(dispatcher)
    @After fun restoreMain() = Dispatchers.resetMain()

    private class FakeMyPostsDataSource(
        var postsToEmit: List<HomePost> = emptyList()
    ) : MyPostsDataSource {
        var lastUpdatedPostId: String? = null
        var lastUpdatedStatus: String? = null
        var lastDeletedPostId: String? = null

        override fun observeMyPosts(authorId: String): Flow<List<HomePost>> {
            return flowOf(postsToEmit)
        }

        override suspend fun updatePostStatus(postId: String, newStatus: String): Result<Unit> {
            lastUpdatedPostId = postId
            lastUpdatedStatus = newStatus
            return Result.success(Unit)
        }

        override suspend fun deletePost(postId: String): Result<Unit> {
            lastDeletedPostId = postId
            return Result.success(Unit)
        }
    }

    private fun createSamplePost(id: String, title: String, status: String = "OPEN"): HomePost {
        return HomePost(
            id = id,
            title = title,
            type = "LOST",
            location = "Thư viện",
            createdAt = System.currentTimeMillis(),
            status = status
        )
    }

    @Test
    fun state_filtersOpenAndResolvedPostsCorrectly() {
        val openPost1 = createSamplePost("1", "Chìa khoá", "OPEN")
        val openPost2 = createSamplePost("2", "Thẻ SV", "open")
        val resolvedPost = createSamplePost("3", "Ví tiền", "RESOLVED")
        val closedPost = createSamplePost("4", "Điện thoại", "CLOSED")

        val state = MyPostsState(
            posts = listOf(openPost1, openPost2, resolvedPost, closedPost)
        )

        assertEquals(2, state.openPosts.size)
        assertTrue(state.openPosts.contains(openPost1))
        assertTrue(state.openPosts.contains(openPost2))

        assertEquals(2, state.resolvedPosts.size)
        assertTrue(state.resolvedPosts.contains(resolvedPost))
        assertTrue(state.resolvedPosts.contains(closedPost))

        // Tab OPEN trả về openPosts
        assertEquals(2, state.currentList.size)

        // Tab RESOLVED trả về resolvedPosts
        val resolvedTabState = state.copy(selectedTab = MyPostsTab.RESOLVED)
        assertEquals(2, resolvedTabState.currentList.size)
    }

    @Test
    fun bindUser_loadsPostsSuccessfully() = runTest(dispatcher) {
        val p1 = createSamplePost("p1", "Balo", "OPEN")
        val fakeRepo = FakeMyPostsDataSource(listOf(p1))
        val vm = MyPostsViewModel(repositoryProvider = { fakeRepo })

        vm.bindUser("user123")
        advanceUntilIdle()

        val currentState = vm.state.value
        assertFalse(currentState.loading)
        assertEquals(1, currentState.posts.size)
        assertEquals("p1", currentState.posts[0].id)
    }

    @Test
    fun selectTab_updatesSelectedTab() {
        val vm = MyPostsViewModel()
        assertEquals(MyPostsTab.OPEN, vm.state.value.selectedTab)

        vm.selectTab(MyPostsTab.RESOLVED)
        assertEquals(MyPostsTab.RESOLVED, vm.state.value.selectedTab)
    }

    @Test
    fun markStatus_updatesPostStatusViaRepository() = runTest(dispatcher) {
        val fakeRepo = FakeMyPostsDataSource()
        val vm = MyPostsViewModel(repositoryProvider = { fakeRepo })
        vm.bindUser("user123")
        advanceUntilIdle()
        val post = createSamplePost("100", "Ví", "OPEN")

        vm.markStatus(post, "RESOLVED")
        advanceUntilIdle()

        assertEquals("100", fakeRepo.lastUpdatedPostId)
        assertEquals("RESOLVED", fakeRepo.lastUpdatedStatus)
    }

    @Test
    fun deletePost_callsRepositoryDelete() = runTest(dispatcher) {
        val fakeRepo = FakeMyPostsDataSource()
        val vm = MyPostsViewModel(repositoryProvider = { fakeRepo })
        vm.bindUser("user123")
        advanceUntilIdle()

        vm.deletePost("200")
        advanceUntilIdle()

        assertEquals("200", fakeRepo.lastDeletedPostId)
    }
}
