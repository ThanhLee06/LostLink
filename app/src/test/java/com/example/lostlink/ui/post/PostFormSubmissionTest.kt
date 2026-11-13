package com.example.lostlink.ui.post

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostFormSubmissionTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun prepareMain() = Dispatchers.setMain(dispatcher)
    @After fun restoreMain() = Dispatchers.resetMain()

    private fun ready(repo: FakePostCreationDataSource): PostFormViewModel = PostFormViewModel(repo).apply {
        openForUser("user-a", false)
        onTitleChange("Found wallet")
        onCategoryChange("wallet")
        onDescriptionChange("Black wallet found near the library")
        onAreaChoiceChange(AreaChoice.UNKNOWN)
    }

    @Test fun firstFoundFormSurvivesBindingAndRecomposition() {
        val vm = PostFormViewModel(FakePostCreationDataSource())
        vm.openForUser("user-a", false)
        vm.bindUser("user-a")
        assertEquals(PostType.FOUND, vm.state.value.type)
        vm.onTitleChange("Draft")
        vm.openForUser("user-a", true)
        assertEquals("Draft", vm.state.value.title)
        assertEquals(PostType.FOUND, vm.state.value.type)
    }

    @Test fun invalidFormDoesNotReadUploadOrWrite() = runTest(dispatcher) {
        val repo = FakePostCreationDataSource()
        val vm = PostFormViewModel(repo)
        vm.openForUser("user-a", true)
        vm.submitPost()
        advanceUntilIdle()
        assertFalse(vm.state.value.errors.isEmpty)
        assertEquals(0, repo.readCalls)
        assertTrue(repo.writtenIds.isEmpty())
        assertEquals(0, repo.uploadCalls)
    }

    @Test fun waitsForServerAndRejectsDuplicateClicksAndEdits() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakePostCreationDataSource().apply { write = { gate.await() } }
        val vm = ready(repo)
        val id = vm.state.value.clientPostId
        vm.submitPost()
        vm.submitPost()
        runCurrent()
        vm.onTitleChange("Must not replace the submitted draft")
        assertTrue(vm.state.value.isSubmitting)
        assertNull(vm.state.value.createdPostId)
        assertEquals("Found wallet", vm.state.value.title)
        assertEquals(listOf(id), repo.writtenIds)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(id, vm.state.value.createdPostId)
        assertFalse(vm.state.value.hasUnsavedChanges)
        assertTrue(vm.consumeCreatedPost(id))
        assertFalse(vm.consumeCreatedPost(id))
    }

    @Test fun uncertainWriteKeepsDraftAndRetryConfirmsExistingPost() = runTest(dispatcher) {
        val repo = FakePostCreationDataSource().apply { write = { throw IOException("Lost response") } }
        val vm = ready(repo)
        val id = vm.state.value.clientPostId
        vm.submitPost()
        advanceUntilIdle()
        assertEquals("Found wallet", vm.state.value.title)
        assertEquals(id, vm.state.value.clientPostId)
        assertTrue(vm.state.value.awaitingConfirmation)
        assertNull(vm.state.value.createdPostId)
        vm.clearDraft()
        vm.onDescriptionChange("Cannot change an uncertain submission")
        assertEquals(id, vm.state.value.clientPostId)
        assertEquals("Black wallet found near the library", vm.state.value.description)
        repo.exists = true
        vm.submitPost()
        advanceUntilIdle()
        assertEquals(id, vm.state.value.createdPostId)
        assertEquals(1, repo.writtenIds.size)
    }

    @Test fun retryUsesSameDocumentIdWhenServerConfirmsItDoesNotExist() = runTest(dispatcher) {
        val repo = FakePostCreationDataSource().apply { write = { throw IOException("Offline") } }
        val vm = ready(repo)
        val id = vm.state.value.clientPostId
        vm.submitPost()
        advanceUntilIdle()
        repo.write = {}
        vm.submitPost()
        advanceUntilIdle()
        assertEquals(listOf(id, id), repo.writtenIds)
        assertEquals(id, vm.state.value.createdPostId)
    }

    @Test fun failureBeforeWriteKeepsEditableDraftAndDoesNotClaimSuccess() = runTest(dispatcher) {
        val repo = FakePostCreationDataSource().apply { read = { throw IOException("Offline") } }
        val vm = ready(repo)
        val id = vm.state.value.clientPostId
        vm.submitPost()
        advanceUntilIdle()
        assertFalse(vm.state.value.isSubmitting)
        assertFalse(vm.state.value.awaitingConfirmation)
        assertNotNull(vm.state.value.submitError)
        assertNull(vm.state.value.createdPostId)
        assertTrue(repo.writtenIds.isEmpty())
        vm.onTitleChange("Corrected wallet")
        assertEquals(id, vm.state.value.clientPostId)
        assertEquals("Corrected wallet", vm.state.value.title)
    }

    @Test fun lateResultOfOldAccountCannotAffectNewAccount() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakePostCreationDataSource().apply { write = { withContext(NonCancellable) { gate.await() } } }
        val vm = ready(repo)
        vm.submitPost()
        runCurrent()
        repo.activeUid = "user-b"
        vm.openForUser("user-b", true)
        vm.onTitleChange("New account draft")
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("New account draft", vm.state.value.title)
        assertEquals(PostType.LOST, vm.state.value.type)
        assertNull(vm.state.value.createdPostId)
        assertNull(vm.state.value.submitError)
        assertFalse(vm.state.value.isSubmitting)
    }

    @Test fun invalidAccountCannotSubmit() = runTest(dispatcher) {
        val repo = FakePostCreationDataSource()
        val vm = ready(repo)
        repo.activeUid = "user-b"
        vm.submitPost()
        advanceUntilIdle()
        assertNotNull(vm.state.value.submitError)
        assertEquals(0, repo.readCalls)
        assertTrue(repo.writtenIds.isEmpty())
    }

    @Test fun newDraftGetsNewIdOnlyWhenDiscardedOrConfirmed() {
        val vm = ready(FakePostCreationDataSource())
        val id = vm.state.value.clientPostId
        vm.onTitleChange("Another title")
        assertEquals(id, vm.state.value.clientPostId)
        vm.clearDraft()
        assertNotEquals(id, vm.state.value.clientPostId)
        assertFalse(vm.state.value.hasUnsavedChanges)
    }
}
