package com.example.lostlink.ui.myposts

import com.example.lostlink.data.MyPostsDataSource
import com.example.lostlink.ui.home.HomePost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MyPostsActionsTest {
    private val dispatcher = StandardTestDispatcher()
    private val a = HomePost("a", "Ví", "LOST", "Thư viện", 1, authorId = "A")
    private val b = a.copy(id = "b", authorId = "B")
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    private class Source(val a: HomePost, val b: HomePost) : MyPostsDataSource {
        var subscriptions = 0
        var failRead = false
        var writes = 0
        var result: Result<Unit> = Result.success(Unit)
        var gate: CompletableDeferred<Unit>? = null
        override fun observeMyPosts(authorId: String): Flow<List<HomePost>> = flow {
            subscriptions++
            if (failRead) error("network")
            emitAll(MutableStateFlow(listOf(if (authorId == "A") a else b)))
        }
        override suspend fun updatePostStatus(postId: String, newStatus: String): Result<Unit> {
            writes++
            gate?.await()
            return result
        }
        override suspend fun deletePost(postId: String) = updatePostStatus(postId, "DELETED")
    }

    @Test fun failedMutationDoesNotNotifyHomeAndConsecutiveClicksAreBlocked() = runTest(dispatcher) {
        val source = Source(a, b).apply {
            result = Result.failure(IllegalStateException("denied"))
            gate = CompletableDeferred()
        }
        val vm = MyPostsViewModel { source }
        vm.bindUser("A")
        runCurrent()
        var notifications = 0
        vm.deletePost("a") { notifications++ }
        vm.deletePost("a") { notifications++ }
        runCurrent()
        assertEquals(1, source.writes)
        source.gate!!.complete(Unit)
        runCurrent()
        assertEquals(0, notifications)
        assertEquals(listOf(a), vm.state.value.posts)
        assertEquals("denied", vm.state.value.userMessage)
        assertFalse(vm.state.value.actionLoading)
    }

    @Test fun accountSwitchClearsOldPostsAndRejectsLateMutationCallback() = runTest(dispatcher) {
        val source = Source(a, b).apply { gate = CompletableDeferred() }
        val vm = MyPostsViewModel { source }
        vm.bindUser("A")
        runCurrent()
        var notifications = 0
        vm.markStatus(a, "CLOSED") { notifications++ }
        runCurrent()
        vm.bindUser("B")
        assertTrue(vm.state.value.posts.isEmpty())
        runCurrent()
        source.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(b), vm.state.value.posts)
        assertEquals(0, notifications)
        vm.bindUser(null)
        assertTrue(vm.state.value.posts.isEmpty())
    }

    @Test fun retryResubscribesAfterListenerFailureAndSuccessNotifiesOnce() = runTest(dispatcher) {
        val source = Source(a, b).apply { failRead = true }
        val vm = MyPostsViewModel { source }
        vm.bindUser("A")
        runCurrent()
        assertNotNull(vm.state.value.error)
        source.failRead = false
        vm.retry()
        runCurrent()
        assertEquals(2, source.subscriptions)
        assertNull(vm.state.value.error)
        var notifications = 0
        vm.markStatus(a, "RESOLVED") { notifications++ }
        runCurrent()
        assertEquals(1, notifications)
    }
}
