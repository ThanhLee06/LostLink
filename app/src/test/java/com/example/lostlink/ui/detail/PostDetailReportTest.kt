package com.example.lostlink.ui.detail

import com.example.lostlink.data.AlreadyReportedException
import com.example.lostlink.data.PostReportBackend
import com.example.lostlink.data.PostReportRepository
import com.example.lostlink.data.PostReportValidator
import com.example.lostlink.data.PostUnavailableForReportException
import com.example.lostlink.data.ReportInProgressException
import com.example.lostlink.data.ReportReason
import com.example.lostlink.data.ReportStatus
import com.example.lostlink.data.SavedPostEntry
import com.example.lostlink.data.SavedPostResolution
import com.example.lostlink.data.SavedPostsBackend
import com.example.lostlink.data.SavedPostsRepository
import com.example.lostlink.data.SelfReportNotAllowedException
import com.example.lostlink.data.UnavailablePostReason
import com.example.lostlink.data.UserReportInfo
import com.example.lostlink.ui.home.HomePost
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakePostDetailSource(
    override var activeUserId: String? = "student-2",
) : PostDetailSource {
    val posts: MutableMap<String, PostDetail> = mutableMapOf()
    private var accountListener: ((String?) -> Unit)? = null

    override fun observeAccount(onChanged: (String?) -> Unit): () -> Unit {
        accountListener = onChanged
        onChanged(activeUserId)
        return { accountListener = null }
    }

    override fun read(id: String, onComplete: (Result<PostDetail?>) -> Unit) {
        onComplete(Result.success(posts[id]))
    }
}

private class NoOpSavedPostsBackend : SavedPostsBackend {
    override fun observeSavedEntries(
        uid: String,
        onUpdate: (List<SavedPostEntry>) -> Unit,
        onError: (Throwable) -> Unit,
    ): () -> Unit {
        onUpdate(emptyList())
        return {}
    }

    override fun observePost(
        postId: String,
        onResult: (SavedPostResolution) -> Unit,
    ): () -> Unit = {}

    override suspend fun checkPostAvailableForSave(postId: String): Result<HomePost> =
        Result.failure(IllegalStateException("Not used"))

    override suspend fun savePost(uid: String, post: HomePost): Result<Unit> =
        Result.success(Unit)

    override suspend fun unsavePost(uid: String, postId: String): Result<Unit> =
        Result.success(Unit)
}

private class FakePostReportBackend : PostReportBackend {
    data class StoredReport(
        val postId: String,
        val reporterUid: String,
        val reason: ReportReason,
        val description: String?,
        val status: ReportStatus = ReportStatus.PENDING,
        val publicNote: String? = null,
    )

    val posts: MutableMap<String, HomePost> = mutableMapOf()
    val reportsByDocId: MutableMap<String, StoredReport> = mutableMapOf()
    var createGate: CompletableDeferred<Unit>? = null
    var createCallCount = 0

    override suspend fun hasUserReportedPost(uid: String, postId: String): Result<Boolean> {
        val docId = PostReportValidator.buildReportId(postId, uid)
        return Result.success(docId in reportsByDocId)
    }

    override suspend fun getUserReport(uid: String, postId: String): Result<UserReportInfo?> {
        val docId = PostReportValidator.buildReportId(postId, uid)
        val stored = reportsByDocId[docId] ?: return Result.success(null)
        return Result.success(
            UserReportInfo(
                postId = stored.postId,
                reporterUid = stored.reporterUid,
                reason = stored.reason,
                description = stored.description,
                status = stored.status,
                publicNote = stored.publicNote,
            )
        )
    }

    override suspend fun checkPostReportable(uid: String, postId: String): Result<Unit> {
        val post = posts[postId]
            ?: return Result.failure(PostUnavailableForReportException(UnavailablePostReason.DELETED))
        if (post.authorId == uid) {
            return Result.failure(SelfReportNotAllowedException())
        }
        if (post.isHidden) {
            return Result.failure(PostUnavailableForReportException(UnavailablePostReason.HIDDEN))
        }
        return Result.success(Unit)
    }

    override suspend fun createReport(
        uid: String,
        postId: String,
        reason: ReportReason,
        description: String?,
    ): Result<Unit> {
        createCallCount++
        createGate?.await()
        // Re-check post availability & duplicate after gate to simulate concurrent deletion/hide/duplicate
        checkPostReportable(uid, postId).onFailure { return Result.failure(it) }
        val docId = PostReportValidator.buildReportId(postId, uid)
        if (docId in reportsByDocId) {
            return Result.failure(AlreadyReportedException())
        }
        reportsByDocId[docId] = StoredReport(
            postId = postId,
            reporterUid = uid,
            reason = reason,
            description = description,
        )
        return Result.success(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PostDetailReportTest {
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
        authorId: String = "student-1",
        moderationStatus: String = "VISIBLE",
    ) = HomePost(
        id = id,
        title = "Nhặt được thẻ sinh viên UTH",
        type = "FOUND",
        location = "Thư viện Cơ sở 1",
        createdAt = 1_700_000_000_000L,
        authorId = authorId,
        authorName = "Nguyễn Văn A",
        moderationStatus = moderationStatus,
    )

    @Test
    fun reportReasonEnum_matchesStep0CanonicalContract() {
        val codes = ReportReason.entries.map { it.code }
        assertEquals(
            listOf("INAPPROPRIATE", "WRONG_INFO", "SPAM", "OTHER"),
            codes,
        )
        assertEquals(ReportReason.INAPPROPRIATE, ReportReason.fromCode("inappropriate"))
        assertEquals(ReportReason.WRONG_INFO, ReportReason.fromCode("WRONG_INFO"))
        assertEquals(ReportReason.SPAM, ReportReason.fromCode("spam"))
        assertEquals(ReportReason.OTHER, ReportReason.fromCode("OTHER"))
        assertNull(ReportReason.fromCode("UNKNOWN_REASON"))
    }

    @Test
    fun validator_enforcesReasonSelectionAnd500CharDescriptionLimit() {
        val missingReason = PostReportValidator.validate(null, "Mô tả ngắn")
        assertFalse(missingReason.isValid)
        assertNotNull(missingReason.reasonError)

        val validMax = PostReportValidator.validate(ReportReason.SPAM, "a".repeat(500))
        assertTrue(validMax.isValid)

        val overLimit = PostReportValidator.validate(ReportReason.SPAM, "a".repeat(501))
        assertFalse(overLimit.isValid)
        assertNotNull(overLimit.descriptionError)

        val payload = PostReportValidator.buildCreatePayload(
            postId = "post-1",
            reporterUid = "student-2",
            reason = ReportReason.WRONG_INFO,
            description = "  Sai địa điểm nhặt đồ  ",
            createdAtMarker = "SERVER_TIMESTAMP",
        )
        assertEquals("post-1_student-2", PostReportValidator.buildReportId("post-1", "student-2"))
        assertEquals("post-1_student-2", PostReportValidator.buildReportId("  post-1  ", "  student-2  "))
        assertEquals(
            setOf("postId", "reporterUid", "reason", "description", "status", "createdAt"),
            payload.keys,
        )
        assertEquals("post-1", payload["postId"])
        assertEquals("student-2", payload["reporterUid"])
        assertEquals("WRONG_INFO", payload["reason"])
        assertEquals("Sai địa điểm nhặt đồ", payload["description"])
        assertEquals("PENDING", payload["status"])
        assertEquals("SERVER_TIMESTAMP", payload["createdAt"])
        assertFalse("publicNote" in payload)
    }

    @Test
    fun canShowReportOption_hidesReportForOwnPostAndShowsForOtherUsersPost() {
        val ownPost = samplePost(id = "post-own", authorId = "student-1")
        val ownState = PostDetailState(
            id = "post-own",
            status = DetailStatus.READY,
            detail = PostDetail(post = ownPost, category = "Thẻ sinh viên"),
        )
        assertFalse("Phải ẩn tùy chọn báo cáo đối với bài đăng của chính mình", ownState.canShowReportOption("student-1"))

        val otherPost = samplePost(id = "post-other", authorId = "student-1")
        val otherState = PostDetailState(
            id = "post-other",
            status = DetailStatus.READY,
            detail = PostDetail(post = otherPost, category = "Thẻ sinh viên"),
        )
        assertTrue("Phải hiển thị tùy chọn báo cáo đối với bài của người khác", otherState.canShowReportOption("student-2"))
    }

    @Test
    fun submitReport_blocksRapidDuplicateSubmissionsWithLoadingLock() = runTest(testDispatcher) {
        val backend = FakePostReportBackend()
        backend.posts["post-1"] = samplePost(id = "post-1", authorId = "student-1")
        val gate = CompletableDeferred<Unit>()
        backend.createGate = gate
        val repo = PostReportRepository(backend = backend, activeUidProvider = { "student-2" })

        val first = async {
            repo.submitReport(
                postId = "post-1",
                authorIdHint = "student-1",
                reason = ReportReason.SPAM,
                rawDescription = "Đăng trùng lặp",
            )
        }
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue("post-1" in repo.pendingPostIds.value)

        // Rapid second click while first is in-flight must be locked out
        val second = repo.submitReport(
            postId = "post-1",
            authorIdHint = "student-1",
            reason = ReportReason.SPAM,
            rawDescription = "Đăng trùng lặp",
        )
        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull() is ReportInProgressException)

        gate.complete(Unit)
        assertTrue(first.await().isSuccess)
        assertFalse("post-1" in repo.pendingPostIds.value)
        assertTrue("post-1" in repo.reportedPostIds.value)
        assertEquals(1, backend.createCallCount)
    }

    @Test
    fun viewModel_showsClearMessageWhenUserAlreadyReportedPostPreviously() = runTest(testDispatcher) {
        val post = samplePost(id = "post-1", authorId = "student-1")
        val detailSource = FakePostDetailSource(activeUserId = "student-2").apply {
            posts["post-1"] = PostDetail(post = post, category = "Thẻ sinh viên")
        }
        val reportBackend = FakePostReportBackend().apply {
            posts["post-1"] = post
            reportsByDocId["post-1_student-2"] = FakePostReportBackend.StoredReport(
                postId = "post-1",
                reporterUid = "student-2",
                reason = ReportReason.INAPPROPRIATE,
                description = "Đã báo cáo từ trước",
            )
        }
        val vm = PostDetailViewModel(
            source = detailSource,
            savedPosts = SavedPostsRepository(NoOpSavedPostsBackend(), { "student-2" }),
            reports = PostReportRepository(reportBackend, { "student-2" }),
        )

        vm.open("post-1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.isReported)
        var alreadyReportedToast: String? = null
        vm.openReportDialog { msg -> alreadyReportedToast = msg }

        assertFalse("Không mở lại form khi người dùng đã báo cáo trước đó", vm.state.isReportDialogOpen)
        assertNotNull(alreadyReportedToast)
        assertTrue(alreadyReportedToast!!.contains("đã báo cáo", ignoreCase = true))
        assertEquals(alreadyReportedToast, vm.state.reportNoticeMessage)
    }

    @Test
    fun viewModel_handlesPostDeletedWhileSubmittingReportCleanly() = runTest(testDispatcher) {
        val post = samplePost(id = "post-1", authorId = "student-1")
        val detailSource = FakePostDetailSource(activeUserId = "student-2").apply {
            posts["post-1"] = PostDetail(post = post, category = "Thẻ sinh viên")
        }
        val reportBackend = FakePostReportBackend().apply {
            posts["post-1"] = post
        }
        val vm = PostDetailViewModel(
            source = detailSource,
            savedPosts = SavedPostsRepository(NoOpSavedPostsBackend(), { "student-2" }),
            reports = PostReportRepository(reportBackend, { "student-2" }),
        )

        vm.open("post-1")
        testDispatcher.scheduler.advanceUntilIdle()

        vm.openReportDialog()
        vm.selectReportReason(ReportReason.WRONG_INFO)
        vm.updateReportDescription("Thông tin không đúng")

        // Simulate the post being deleted right before the user presses submit
        reportBackend.posts.remove("post-1")

        var unavailablePostId: String? = null
        var unavailableMsg: String? = null
        vm.submitReport(
            onPostUnavailable = { id, msg ->
                unavailablePostId = id
                unavailableMsg = msg
            }
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("post-1", unavailablePostId)
        assertNotNull(unavailableMsg)
        assertTrue(unavailableMsg!!.contains("xóa", ignoreCase = true))
        assertEquals(DetailStatus.NOT_FOUND, vm.state.status)
        assertFalse(vm.state.isReportDialogOpen)
        assertFalse(vm.state.isReportPending)
        assertEquals(unavailableMsg, vm.state.unavailableMessage)
    }

    @Test
    fun viewModel_handlesPostHiddenWhileSubmittingReportCleanly() = runTest(testDispatcher) {
        val post = samplePost(id = "post-1", authorId = "student-1")
        val detailSource = FakePostDetailSource(activeUserId = "student-2").apply {
            posts["post-1"] = PostDetail(post = post, category = "Thẻ sinh viên")
        }
        val reportBackend = FakePostReportBackend().apply {
            posts["post-1"] = post
        }
        val vm = PostDetailViewModel(
            source = detailSource,
            savedPosts = SavedPostsRepository(NoOpSavedPostsBackend(), { "student-2" }),
            reports = PostReportRepository(reportBackend, { "student-2" }),
        )

        vm.open("post-1")
        testDispatcher.scheduler.advanceUntilIdle()

        vm.openReportDialog()
        vm.selectReportReason(ReportReason.INAPPROPRIATE)

        // Simulate Admin hiding the post right while user is filling/submitting the report
        reportBackend.posts["post-1"] = post.copy(moderationStatus = "HIDDEN")

        var unavailableMsg: String? = null
        vm.submitReport(
            onPostUnavailable = { _, msg ->
                unavailableMsg = msg
            }
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(unavailableMsg)
        assertTrue(unavailableMsg!!.contains("ẩn", ignoreCase = true))
        assertEquals(DetailStatus.NOT_FOUND, vm.state.status)
        assertFalse(vm.state.isReportDialogOpen)
        assertEquals(unavailableMsg, vm.state.unavailableMessage)
    }

    @Test
    fun st49_reporterReceivesAccuratePendingAndAdminProcessedReportStatus() = runTest(testDispatcher) {
        val post = samplePost(id = "post-1", authorId = "student-1")
        val detailSource = FakePostDetailSource(activeUserId = "student-2").apply {
            posts["post-1"] = PostDetail(post = post, category = "Thẻ sinh viên")
        }
        val reportBackend = FakePostReportBackend().apply {
            posts["post-1"] = post
            reportsByDocId["post-1_student-2"] = FakePostReportBackend.StoredReport(
                postId = "post-1",
                reporterUid = "student-2",
                reason = ReportReason.SPAM,
                description = "Tin spam",
                status = ReportStatus.REJECTED,
                publicNote = "Đã xác minh bài viết hợp lệ.",
            )
        }
        val vm = PostDetailViewModel(
            source = detailSource,
            savedPosts = SavedPostsRepository(NoOpSavedPostsBackend(), { "student-2" }),
            reports = PostReportRepository(reportBackend, { "student-2" }),
        )

        vm.open("post-1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.isReported)
        val info = vm.state.reportInfo
        assertNotNull(info)
        assertEquals(ReportStatus.REJECTED, info!!.status)
        assertTrue(info.status.isProcessed)
        assertEquals(ReportReason.SPAM, info.reason)
        assertEquals("Đã xác minh bài viết hợp lệ.", info.publicNote)
    }
}
