package com.example.lostlink.ui.myposts

import android.content.Context
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
    private val repositoryProvider: () -> MyPostsDataSource = { error("Use MyPostsViewModel.factory(context)") }
) : ViewModel() {

    private val repository by lazy { repositoryProvider() }
    private val _state = MutableStateFlow(MyPostsState())
    val state: StateFlow<MyPostsState> = _state.asStateFlow()

    private var generation = 0L
    private var currentAuthorId: String? = null
    private var observeJob: Job? = null

    fun bindUser(authorId: String?, force: Boolean = false) {
        if (!force && authorId == currentAuthorId && observeJob?.isActive == true) return
        val changed = authorId != currentAuthorId
        val version = ++generation
        currentAuthorId = authorId
        observeJob?.cancel()

        if (authorId.isNullOrBlank()) {
            _state.value = MyPostsState(loading = false, error = "Chưa đăng nhập.")
            return
        }

        _state.update { if (changed) MyPostsState() else it.copy(loading = true, error = null) }
        observeJob = viewModelScope.launch {
            repository.observeMyPosts(authorId)
                .catch { err ->
                    if (version != generation) return@catch
                    _state.update {
                        it.copy(loading = false, error = err.localizedMessage ?: "Không thể tải tin của bạn.")
                    }
                }
                .collect { list ->
                    if (version != generation) return@collect
                    _state.update {
                        it.copy(loading = false, posts = list, error = null)
                    }
                }
        }
    }

    fun isBoundTo(authorId: String?) = currentAuthorId == authorId

    fun selectTab(tab: MyPostsTab) {
        _state.update { it.copy(selectedTab = tab) }
    }

    fun retry() { if (!_state.value.actionLoading) bindUser(currentAuthorId, force = true) }

    fun markStatus(post: HomePost, newStatus: String, onDone: (() -> Unit)? = null) = mutate(
        { repository.updatePostStatus(post.id, newStatus) }, "Đã cập nhật trạng thái.", onDone)

    fun deletePost(postId: String, onDone: (() -> Unit)? = null) = mutate(
        { repository.deletePost(postId) }, "Đã xóa bài đăng.", onDone)

    private fun mutate(operation: suspend () -> Result<Unit>, message: String, onDone: (() -> Unit)?) {
        if (_state.value.actionLoading || currentAuthorId.isNullOrBlank()) return
        val version = generation
        _state.update { it.copy(actionLoading = true, userMessage = null) }
        viewModelScope.launch {
            val result = try { operation() } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Result.failure(error)
            }
            result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            if (version != generation) return@launch
            _state.update { it.copy(actionLoading = false,
                userMessage = if (result.isSuccess) message else
                    result.exceptionOrNull()?.localizedMessage ?: "Thao tác thất bại.") }
            if (result.isSuccess) onDone?.invoke()
        }
    }

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { MyPostsViewModel { MyPostsRepository(context.applicationContext) } }
        }
    }

    fun clearUserMessage() {
        _state.update { it.copy(userMessage = null) }
    }
}

