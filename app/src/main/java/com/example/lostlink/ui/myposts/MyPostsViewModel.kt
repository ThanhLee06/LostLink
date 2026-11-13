package com.example.lostlink.ui.myposts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lostlink.data.MyPostsDataSource
import com.example.lostlink.data.MyPostsRepository
import com.example.lostlink.ui.home.HomePost
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MyPostsViewModel(
    private val repositoryProvider: () -> MyPostsDataSource = { MyPostsRepository() }
) : ViewModel() {

    private val repository by lazy { repositoryProvider() }
    private val _state = MutableStateFlow(MyPostsState())
    val state: StateFlow<MyPostsState> = _state.asStateFlow()

    private var currentAuthorId: String? = null
    private var observeJob: Job? = null

    fun bindUser(authorId: String?) {
        if (authorId == currentAuthorId && observeJob != null) return
        currentAuthorId = authorId
        observeJob?.cancel()

        if (authorId.isNullOrBlank()) {
            _state.update { it.copy(loading = false, posts = emptyList(), error = "Chưa đăng nhập.") }
            return
        }

        _state.update { it.copy(loading = true, error = null) }
        observeJob = viewModelScope.launch {
            repository.observeMyPosts(authorId)
                .catch { err ->
                    _state.update {
                        it.copy(loading = false, error = err.localizedMessage ?: "Không thể tải tin của bạn.")
                    }
                }
                .collect { list ->
                    _state.update {
                        it.copy(loading = false, posts = list, error = null)
                    }
                }
        }
    }

    fun selectTab(tab: MyPostsTab) {
        _state.update { it.copy(selectedTab = tab) }
    }

    fun markStatus(post: HomePost, newStatus: String) {
        viewModelScope.launch {
            _state.update { it.copy(actionLoading = true) }
            val result = repository.updatePostStatus(post.id, newStatus)
            if (result.isSuccess) {
                val msg = if (newStatus == "RESOLVED") "Đã đánh dấu bài viết đã giải quyết!" else "Đã mở lại bài viết!"
                _state.update { it.copy(actionLoading = false, userMessage = msg) }
            } else {
                _state.update {
                    it.copy(
                        actionLoading = false,
                        userMessage = result.exceptionOrNull()?.localizedMessage ?: "Cập nhật trạng thái thất bại."
                    )
                }
            }
        }
    }

    fun deletePost(postId: String) {
        viewModelScope.launch {
            _state.update { it.copy(actionLoading = true) }
            val result = repository.deletePost(postId)
            if (result.isSuccess) {
                _state.update { it.copy(actionLoading = false, userMessage = "Đã xoá bài đăng thành công.") }
            } else {
                _state.update {
                    it.copy(
                        actionLoading = false,
                        userMessage = result.exceptionOrNull()?.localizedMessage ?: "Xoá bài đăng thất bại."
                    )
                }
            }
        }
    }

    fun clearUserMessage() {
        _state.update { it.copy(userMessage = null) }
    }
}

