package com.example.lostlink.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lostlink.data.SaveInProgressException
import com.example.lostlink.data.SavedPostsRepository
import kotlinx.coroutines.launch

enum class DetailStatus { IDLE, LOADING, READY, NOT_FOUND, ACCESS_DENIED, NETWORK_ERROR, INVALID_ID, INVALID_DATA, ERROR }

data class PostDetailState(
    val id: String? = null,
    val status: DetailStatus = DetailStatus.IDLE,
    val detail: PostDetail? = null,
    val isSaved: Boolean = false,
    val isSavePending: Boolean = false,
) {
    val canRetry get() = status in setOf(DetailStatus.NETWORK_ERROR, DetailStatus.ERROR, DetailStatus.INVALID_DATA)
}

fun isValidPostId(id: String): Boolean = id.isNotBlank() && id !in listOf(".", "..") &&
    '/' !in id && id.toByteArray(Charsets.UTF_8).size <= 1_500 && !id.matches(Regex("__.*__"))

class PostDetailViewModel(
    private val source: PostDetailSource = FirebasePostDetailSource(),
    private val savedPosts: SavedPostsRepository = SavedPostsRepository.getInstance(),
) : ViewModel() {
    var state by mutableStateOf(PostDetailState())
        private set
    val currentUserId: String? get() = source.activeUserId ?: identity
    private var identity: String? = null
    private var session = 0L
    private var request = 0L
    private var activeRequest: Long? = null
    private var closed = false
    private val stopObserving = source.observeAccount { account ->
        if (!closed) {
            session++
            activeRequest = null
            identity = account
            savedPosts.bindUser(account)
            val id = state.id
            state = PostDetailState(
                id = id,
                isSaved = id != null && id in savedPosts.savedPostIds.value,
                isSavePending = id != null && id in savedPosts.pendingPostIds.value,
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
    }

    fun open(id: String) {
        if (closed || (state.id == id && state.status == DetailStatus.LOADING)) return
        activeRequest = null
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
        )

        fun complete(result: Result<PostDetail?>) {
            if (closed || activeRequest != version || session != accountSession ||
                identity != account || source.activeUserId != account) return
            activeRequest = null
            result.onSuccess { detail ->
                val saved = id in savedPosts.savedPostIds.value
                val pending = id in savedPosts.pendingPostIds.value
                state = when {
                    detail == null -> PostDetailState(id, DetailStatus.NOT_FOUND, isSaved = saved, isSavePending = pending)
                    detail.post.id != id -> PostDetailState(id, DetailStatus.INVALID_DATA, isSaved = saved, isSavePending = pending)
                    else -> PostDetailState(id, DetailStatus.READY, detail, isSaved = saved, isSavePending = pending)
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

    fun clearSelection() {
        activeRequest = null
        state = PostDetailState()
    }

    override fun onCleared() {
        closed = true
        activeRequest = null
        session++
        stopObserving()
    }
}
