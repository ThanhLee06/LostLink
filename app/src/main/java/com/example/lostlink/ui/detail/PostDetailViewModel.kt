package com.example.lostlink.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

enum class DetailStatus { IDLE, LOADING, READY, NOT_FOUND, ACCESS_DENIED, NETWORK_ERROR, INVALID_ID, INVALID_DATA, ERROR }

data class PostDetailState(
    val id: String? = null,
    val status: DetailStatus = DetailStatus.IDLE,
    val detail: PostDetail? = null,
) {
    val canRetry get() = status in setOf(DetailStatus.NETWORK_ERROR, DetailStatus.ERROR, DetailStatus.INVALID_DATA)
}

fun isValidPostId(id: String): Boolean = id.isNotBlank() && id !in listOf(".", "..") &&
    '/' !in id && id.toByteArray(Charsets.UTF_8).size <= 1_500 && !id.matches(Regex("__.*__"))

/** Both the account session and selected document must still match a completed read. */
class PostDetailViewModel(private val source: PostDetailSource = FirebasePostDetailSource()) : ViewModel() {
    var state by mutableStateOf(PostDetailState())
        private set
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
            val id = state.id
            state = PostDetailState(id = id)
            if (id != null) open(id)
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
        state = PostDetailState(id, DetailStatus.LOADING)

        fun complete(result: Result<PostDetail?>) {
            if (closed || activeRequest != version || session != accountSession ||
                identity != account || source.activeUserId != account) return
            activeRequest = null
            result.onSuccess { detail ->
                state = when {
                    detail == null -> PostDetailState(id, DetailStatus.NOT_FOUND)
                    detail.post.id != id -> PostDetailState(id, DetailStatus.INVALID_DATA)
                    else -> PostDetailState(id, DetailStatus.READY, detail)
                }
            }.onFailure { error ->
                val status = when ((error as? PostReadException)?.failure) {
                    PostReadFailure.ACCESS_DENIED -> DetailStatus.ACCESS_DENIED
                    PostReadFailure.NETWORK -> DetailStatus.NETWORK_ERROR
                    PostReadFailure.INVALID_DATA -> DetailStatus.INVALID_DATA
                    else -> DetailStatus.ERROR
                }
                state = PostDetailState(id, status)
            }
        }
        try { source.read(id, ::complete) }
        catch (error: Exception) { complete(Result.failure(error)) }
    }

    fun retry() { if (state.canRetry) state.id?.let(::open) }

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
