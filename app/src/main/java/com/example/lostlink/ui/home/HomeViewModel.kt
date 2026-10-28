package com.example.lostlink.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

data class HomeState(
    val allowed: Boolean = false,
    val posts: List<HomePost> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val loadingMore: Boolean = false,
    val retryMore: Boolean = false,
)

/** All source callbacks are delivered on the main thread. */
class HomeViewModel(private val source: HomeFeedSource = FirebaseHomeFeedSource()) : ViewModel() {
    var state by mutableStateOf(HomeState())
        private set
    private var cursor: HomeCursor? = null
    private var identity: String? = null
    private var session = 0L
    private var requestNumber = 0L
    private var activeRequest: Long? = null
    private var closed = false
    private val stopObserving = source.observeAccount { uid ->
        if (!closed) {
            // Even a sign-out/sign-in cycle to the same UID starts a fresh session.
            session++
            activeRequest = null
            identity = uid
            cursor = null
            state = HomeState(allowed = uid != null)
            if (uid != null) load()
        }
    }

    fun retry() = load(more = state.retryMore)

    fun load(more: Boolean = false) {
        val uid = identity ?: return
        if (closed || state.loading || source.activeUserId != uid) return
        if (more && (!state.hasMore || cursor == null)) return
        val request = ++requestNumber
        val accountSession = session
        activeRequest = request
        val after = if (more) cursor else null
        state = state.copy(loading = true, loadingMore = more, error = null)

        fun complete(result: Result<HomePage>) {
            if (closed || activeRequest != request || accountSession != session ||
                identity != uid || source.activeUserId != uid) return
            activeRequest = null
            result.onSuccess { page ->
                cursor = page.next
                state = state.copy(
                    posts = ((if (more) state.posts else emptyList()) + page.posts).distinctBy { it.id },
                    loading = false, loadingMore = false, hasMore = page.next != null,
                    error = null, retryMore = false,
                )
            }.onFailure {
                val message = when {
                    state.posts.isEmpty() -> "Chưa tải được tin. Kiểm tra kết nối và thử lại."
                    more -> "Chưa tải được thêm tin. Danh sách đã tải vẫn được giữ lại."
                    else -> "Chưa cập nhật được. Bạn đang xem danh sách đã tải trước đó."
                }
                // Keep both the previous posts and cursor, so a failed page can be retried.
                state = state.copy(loading = false, loadingMore = false, error = message, retryMore = more)
            }
        }
        try {
            source.loadPage(after, ::complete)
        } catch (error: Exception) {
            complete(Result.failure(error))
        }
    }

    override fun onCleared() {
        closed = true
        session++
        activeRequest = null
        stopObserving()
    }
}
