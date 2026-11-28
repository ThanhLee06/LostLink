package com.example.lostlink.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lostlink.data.AlreadyReportedException
import com.example.lostlink.data.PostReportRepository
import com.example.lostlink.data.PostReportValidator
import com.example.lostlink.data.PostUnavailableForReportException
import com.example.lostlink.data.ReportInProgressException
import com.example.lostlink.data.ReportReason
import com.example.lostlink.data.ReportStatus
import com.example.lostlink.data.ReportValidationException
import com.example.lostlink.data.SaveInProgressException
import com.example.lostlink.data.SavedPostsRepository
import com.example.lostlink.data.SelfReportNotAllowedException
import com.example.lostlink.data.UserReportInfo
import kotlinx.coroutines.launch

enum class DetailStatus { IDLE, LOADING, READY, NOT_FOUND, ACCESS_DENIED, NETWORK_ERROR, INVALID_ID, INVALID_DATA, ERROR }

data class PostDetailState(
    val id: String? = null,
    val status: DetailStatus = DetailStatus.IDLE,
    val detail: PostDetail? = null,
    val isSaved: Boolean = false,
    val isSavePending: Boolean = false,
    val isReported: Boolean = false,
    val reportInfo: UserReportInfo? = null,
    val isReportPending: Boolean = false,
    val isReportDialogOpen: Boolean = false,
    val selectedReportReason: ReportReason? = null,
    val reportDescription: String = "",
    val reportValidationError: String? = null,
    val reportSubmitError: String? = null,
    val reportNoticeMessage: String? = null,
    val unavailableMessage: String? = null,
) {
    val canRetry get() = status in setOf(DetailStatus.NETWORK_ERROR, DetailStatus.ERROR, DetailStatus.INVALID_DATA)

    val reportDescriptionLength: Int get() = reportDescription.length

    val isReportDescriptionTooLong: Boolean
        get() = reportDescription.length > PostReportValidator.MAX_DESCRIPTION_LENGTH

    fun canShowReportOption(currentUserId: String?): Boolean {
        val post = detail?.post ?: return false
        if (status != DetailStatus.READY || post.isHidden) return false
        if (currentUserId.isNullOrBlank()) return false
        if (post.authorId.isBlank()) return false
        return post.authorId != currentUserId
    }
}

fun isValidPostId(id: String): Boolean = id.isNotBlank() && id !in listOf(".", "..") &&
    '/' !in id && id.toByteArray(Charsets.UTF_8).size <= 1_500 && !id.matches(Regex("__.*__"))

class PostDetailViewModel(
    private val source: PostDetailSource = FirebasePostDetailSource(),
    private val savedPosts: SavedPostsRepository = SavedPostsRepository.getInstance(),
    private val reports: PostReportRepository = PostReportRepository.getInstance(),
) : ViewModel() {
    var state by mutableStateOf(PostDetailState())
        private set
    val currentUserId: String? get() = source.activeUserId ?: identity
    private var identity: String? = null
    private var session = 0L
    private var request = 0L
    private var activeRequest: Long? = null
    private var isSubmittingReport = false
    private var closed = false
    private val stopObserving = source.observeAccount { account ->
        if (!closed) {
            session++
            activeRequest = null
            isSubmittingReport = false
            identity = account
            savedPosts.bindUser(account)
            reports.bindUser(account)
            val id = state.id
            state = PostDetailState(
                id = id,
                isSaved = id != null && id in savedPosts.savedPostIds.value,
                isSavePending = id != null && id in savedPosts.pendingPostIds.value,
                isReported = id != null && id in reports.reportedPostIds.value,
                reportInfo = id?.let { reports.userReports.value[it] },
                isReportPending = id != null && id in reports.pendingPostIds.value,
            )
            if (id != null) open(id)
        }
    }

    init {
        viewModelScope.launch {
            savedPosts.savedPostIds.collect { ids ->
                val currentId = state.id
                if (!closed && currentId != null) {
                    state = state.copy(isSaved = currentId in ids)
                }
            }
        }
        viewModelScope.launch {
            savedPosts.pendingPostIds.collect { pending ->
                val currentId = state.id
                if (!closed && currentId != null) {
                    state = state.copy(isSavePending = currentId in pending)
                }
            }
        }
        viewModelScope.launch {
            reports.reportedPostIds.collect { ids ->
                val currentId = state.id
                if (!closed && currentId != null) {
                    state = state.copy(isReported = currentId in ids)
                }
            }
        }
        viewModelScope.launch {
            reports.userReports.collect { map ->
                val currentId = state.id
                if (!closed && currentId != null) {
                    state = state.copy(
                        reportInfo = map[currentId],
                        isReported = (currentId in reports.reportedPostIds.value) || (currentId in map),
                    )
                }
            }
        }
        viewModelScope.launch {
            reports.pendingPostIds.collect { pending ->
                val currentId = state.id
                if (!closed && currentId != null) {
                    state = state.copy(isReportPending = isSubmittingReport || (currentId in pending))
                }
            }
        }
    }

    fun open(id: String) {
        if (closed || (state.id == id && state.status == DetailStatus.LOADING)) return
        activeRequest = null
        isSubmittingReport = false
        if (!isValidPostId(id)) {
            state = PostDetailState(id, DetailStatus.INVALID_ID)
            return
        }
        val account = identity
        if (account == null || source.activeUserId != account) {
            state = PostDetailState(id, DetailStatus.ACCESS_DENIED)
            return
        }
        val version = ++request
        val accountSession = session
        activeRequest = version
        state = PostDetailState(
            id = id,
            status = DetailStatus.LOADING,
            isSaved = id in savedPosts.savedPostIds.value,
            isSavePending = id in savedPosts.pendingPostIds.value,
            isReported = id in reports.reportedPostIds.value,
            reportInfo = reports.userReports.value[id],
            isReportPending = id in reports.pendingPostIds.value,
        )

        fun complete(result: Result<PostDetail?>) {
            if (closed || activeRequest != version || session != accountSession ||
                identity != account || source.activeUserId != account) return
            activeRequest = null
            result.onSuccess { detail ->
                val saved = id in savedPosts.savedPostIds.value
                val pending = id in savedPosts.pendingPostIds.value
                val reported = id in reports.reportedPostIds.value
                val reportInfo = reports.userReports.value[id]
                val reportPending = id in reports.pendingPostIds.value
                state = when {
                    detail == null -> PostDetailState(
                        id = id,
                        status = DetailStatus.NOT_FOUND,
                        isSaved = saved,
                        isSavePending = pending,
                        isReported = reported,
                        reportInfo = reportInfo,
                        isReportPending = reportPending,
                    )
                    detail.post.id != id -> PostDetailState(
                        id = id,
                        status = DetailStatus.INVALID_DATA,
                        isSaved = saved,
                        isSavePending = pending,
                        isReported = reported,
                        reportInfo = reportInfo,
                        isReportPending = reportPending,
                    )
                    else -> PostDetailState(
                        id = id,
                        status = DetailStatus.READY,
                        detail = detail,
                        isSaved = saved,
                        isSavePending = pending,
                        isReported = reported,
                        reportInfo = reportInfo,
                        isReportPending = reportPending,
                    )
                }
                if (detail != null && detail.post.id == id && !detail.post.isHidden &&
                    detail.post.authorId.isNotBlank() && detail.post.authorId != account
                ) {
                    viewModelScope.launch {
                        reports.fetchUserReport(id).onSuccess { fetchedInfo ->
                            if (!closed && state.id == id && session == accountSession && fetchedInfo != null) {
                                state = state.copy(isReported = true, reportInfo = fetchedInfo)
                            }
                        }
                    }
                }
            }.onFailure { error ->
                val status = when ((error as? PostReadException)?.failure) {
                    PostReadFailure.ACCESS_DENIED -> DetailStatus.ACCESS_DENIED
                    PostReadFailure.NETWORK -> DetailStatus.NETWORK_ERROR
                    PostReadFailure.INVALID_DATA -> DetailStatus.INVALID_DATA
                    else -> DetailStatus.ERROR
                }
                state = PostDetailState(
                    id = id,
                    status = status,
                    isSaved = id in savedPosts.savedPostIds.value,
                    isSavePending = id in savedPosts.pendingPostIds.value,
                    isReported = id in reports.reportedPostIds.value,
                    isReportPending = id in reports.pendingPostIds.value,
                )
            }
        }
        try { source.read(id, ::complete) }
        catch (error: Exception) { complete(Result.failure(error)) }
    }

    fun retry() { if (state.canRetry) state.id?.let(::open) }

    fun toggleSave(onError: (String) -> Unit = {}) {
        val detail = state.detail ?: return
        if (closed || state.status != DetailStatus.READY) return
        val postId = state.id ?: return
        viewModelScope.launch {
            savedPosts.toggleSave(postId, detail.post.title).onFailure { err ->
                if (err is SaveInProgressException) return@onFailure
                onError(err.localizedMessage ?: "Không thể cập nhật trạng thái lưu.")
            }
        }
    }

    fun openReportDialog(onAlreadyReported: (String) -> Unit = {}) {
        if (closed || state.status != DetailStatus.READY) return
        val activeUid = currentUserId
        if (!state.canShowReportOption(activeUid)) return
        if (state.isReported) {
            val msg = "Bạn đã báo cáo bài viết này trước đó. Quản trị viên đang xem xét."
            state = state.copy(
                isReportDialogOpen = false,
                reportNoticeMessage = msg,
                reportValidationError = null,
                reportSubmitError = null,
            )
            onAlreadyReported(msg)
            return
        }
        state = state.copy(
            isReportDialogOpen = true,
            reportValidationError = null,
            reportSubmitError = null,
        )
    }

    fun dismissReportDialog() {
        if (closed || state.isReportPending || isSubmittingReport) return
        state = state.copy(
            isReportDialogOpen = false,
            reportValidationError = null,
            reportSubmitError = null,
        )
    }

    fun selectReportReason(reason: ReportReason) {
        if (closed || state.isReportPending || isSubmittingReport) return
        val descErr = PostReportValidator.validate(reason, state.reportDescription).descriptionError
        state = state.copy(
            selectedReportReason = reason,
            reportValidationError = descErr,
            reportSubmitError = null,
        )
    }

    fun updateReportDescription(description: String) {
        if (closed || state.isReportPending || isSubmittingReport) return
        val descErr = PostReportValidator.validate(
            state.selectedReportReason ?: ReportReason.OTHER,
            description,
        ).descriptionError
        state = state.copy(
            reportDescription = description,
            reportValidationError = descErr,
            reportSubmitError = null,
        )
    }

    fun dismissReportNotice() {
        if (closed) return
        state = state.copy(reportNoticeMessage = null)
    }

    fun submitReport(
        onSuccess: (String) -> Unit = {},
        onAlreadyReported: (String) -> Unit = {},
        onPostUnavailable: (postId: String, message: String) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {},
    ) {
        if (closed || state.status != DetailStatus.READY) return
        if (state.isReportPending || isSubmittingReport) return
        val detail = state.detail ?: return
        val postId = state.id ?: return
        val activeUid = currentUserId
        if (!state.canShowReportOption(activeUid)) return

        if (state.isReported) {
            val msg = "Bạn đã báo cáo bài viết này trước đó. Quản trị viên đang xem xét."
            state = state.copy(
                isReportDialogOpen = false,
                reportNoticeMessage = msg,
                reportValidationError = null,
                reportSubmitError = null,
            )
            onAlreadyReported(msg)
            return
        }

        val validation = PostReportValidator.validate(state.selectedReportReason, state.reportDescription)
        if (!validation.isValid) {
            state = state.copy(
                reportValidationError = validation.firstErrorMessage,
                reportSubmitError = null,
            )
            return
        }

        val reason = state.selectedReportReason
        val rawDesc = state.reportDescription
        val requestSession = session

        isSubmittingReport = true
        state = state.copy(
            isReportPending = true,
            reportValidationError = null,
            reportSubmitError = null,
        )

        viewModelScope.launch {
            try {
                val result = reports.submitReport(
                    postId = postId,
                    authorIdHint = detail.post.authorId,
                    reason = reason,
                    rawDescription = rawDesc,
                )
                if (closed || session != requestSession || state.id != postId) return@launch

                result.fold(
                    onSuccess = {
                        val msg = "Đã gửi báo cáo thành công. Cảm ơn bạn đã góp phần giữ cộng đồng UTH an toàn."
                        isSubmittingReport = false
                        state = state.copy(
                            isReported = true,
                            isReportPending = false,
                            isReportDialogOpen = false,
                            selectedReportReason = null,
                            reportDescription = "",
                            reportValidationError = null,
                            reportSubmitError = null,
                            reportNoticeMessage = msg,
                        )
                        onSuccess(msg)
                    },
                    onFailure = { err ->
                        when (err) {
                            is ReportInProgressException -> {
                                // Keep pending lock active until original request finishes
                            }
                            is AlreadyReportedException -> {
                                val msg = err.message
                                    ?: "Bạn đã báo cáo bài viết này trước đó. Quản trị viên đang xem xét."
                                isSubmittingReport = false
                                state = state.copy(
                                    isReported = true,
                                    isReportPending = false,
                                    isReportDialogOpen = false,
                                    reportValidationError = null,
                                    reportSubmitError = null,
                                    reportNoticeMessage = msg,
                                )
                                onAlreadyReported(msg)
                            }
                            is PostUnavailableForReportException -> {
                                val msg = err.message
                                    ?: "Bài đăng này vừa bị xóa hoặc bị ẩn nên không thể gửi báo cáo."
                                isSubmittingReport = false
                                state = PostDetailState(
                                    id = postId,
                                    status = DetailStatus.NOT_FOUND,
                                    unavailableMessage = msg,
                                )
                                onPostUnavailable(postId, msg)
                            }
                            is ReportValidationException -> {
                                isSubmittingReport = false
                                state = state.copy(
                                    isReportPending = false,
                                    reportValidationError = err.message,
                                )
                            }
                            is SelfReportNotAllowedException -> {
                                val msg = err.message ?: "Bạn không thể báo cáo bài đăng của chính mình."
                                isSubmittingReport = false
                                state = state.copy(
                                    isReportPending = false,
                                    isReportDialogOpen = false,
                                    reportValidationError = null,
                                    reportSubmitError = null,
                                )
                                onError(msg)
                            }
                            else -> {
                                val msg = err.localizedMessage ?: "Không thể gửi báo cáo. Vui lòng thử lại."
                                isSubmittingReport = false
                                state = state.copy(
                                    isReportPending = false,
                                    reportSubmitError = msg,
                                )
                                onError(msg)
                            }
                        }
                    },
                )
            } finally {
                if (!closed && session == requestSession && state.id == postId && isSubmittingReport) {
                    isSubmittingReport = false
                    state = state.copy(isReportPending = false)
                }
            }
        }
    }

    fun clearSelection() {
        activeRequest = null
        isSubmittingReport = false
        state = PostDetailState()
    }

    override fun onCleared() {
        closed = true
        activeRequest = null
        isSubmittingReport = false
        session++
        stopObserving()
    }
}
