package com.example.lostlink.ui.saved

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lostlink.data.SaveInProgressException
import com.example.lostlink.data.SavedPostEntry
import com.example.lostlink.data.SavedPostsRepository
import com.example.lostlink.ui.home.HomePost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SavedPostsState(
    val loading: Boolean = false,
    val posts: List<HomePost> = emptyList(),
    val savedPostIds: Set<String> = emptySet(),
    val pendingPostIds: Set<String> = emptySet(),
    val error: String? = null,
    val userMessage: String? = null,
)

class SavedPostsViewModel(
    private val repository: SavedPostsRepository = SavedPostsRepository.getInstance(),
) : ViewModel() {

    private val _state = MutableStateFlow(SavedPostsState())
    val state: StateFlow<SavedPostsState> = _state.asStateFlow()

    private var boundUid: String? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            repository.savedPostIds.collect { ids ->
                _state.update { it.copy(savedPostIds = ids) }
            }
        }
        viewModelScope.launch {
            repository.pendingPostIds.collect { pending ->
                _state.update { it.copy(pendingPostIds = pending) }
            }
        }
        viewModelScope.launch {
            repository.syncError.collect { err ->
                if (err != null) {
                    _state.update { it.copy(error = err, loading = false) }
                }
            }
        }
        viewModelScope.launch {
            repository.savedEntries.collectLatest { entries ->
                syncEntries(entries)
            }
        }
    }

    fun bindUser(uid: String?) {
        val clean = uid?.takeIf { it.isNotBlank() }
        if (clean == boundUid) return
        boundUid = clean
        repository.bindUser(clean)
        if (clean == null) {
            loadJob?.cancel()
            _state.value = SavedPostsState()
        } else {
            refresh()
        }
    }

    fun isBoundTo(uid: String?): Boolean = boundUid == uid?.takeIf { it.isNotBlank() }

    fun refresh() {
        if (boundUid == null) return
        loadPosts(repository.savedEntries.value, showLoading = true)
    }

    fun toggleSave(postId: String) {
        viewModelScope.launch {
            repository.toggleSave(postId).onFailure { err ->
                if (err is SaveInProgressException) return@onFailure
                _state.update {
                    it.copy(userMessage = err.localizedMessage ?: "Không thể cập nhật trạng thái lưu.")
                }
            }
        }
    }

    fun clearUserMessage() {
        _state.update { it.copy(userMessage = null) }
    }

    private fun syncEntries(entries: List<SavedPostEntry>) {
        if (boundUid == null) return
        if (entries.isEmpty()) {
            loadJob?.cancel()
            _state.update { it.copy(loading = false, posts = emptyList(), error = null) }
            return
        }
        val existingById = _state.value.posts.associateBy { it.id }
        val savedIdSet = entries.mapTo(HashSet()) { it.postId }
        val hasNewEntry = entries.any { it.postId !in existingById }
        if (!hasNewEntry) {
            val reordered = entries.mapNotNull { entry ->
                existingById[entry.postId]?.takeIf { it.id in savedIdSet }
            }
            _state.update { it.copy(posts = reordered, loading = false, error = null) }
            return
        }
        loadPosts(entries, showLoading = _state.value.posts.isEmpty())
    }

    private fun loadPosts(entries: List<SavedPostEntry>, showLoading: Boolean) {
        loadJob?.cancel()
        if (entries.isEmpty()) {
            _state.update { it.copy(loading = false, posts = emptyList(), error = null) }
            return
        }
        loadJob = viewModelScope.launch {
            if (showLoading) {
                _state.update { it.copy(loading = true, error = null) }
            }
            try {
                val accessiblePosts = repository.loadAccessibleSavedPosts(entries)
                _state.update { it.copy(loading = false, posts = accessiblePosts, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = e.localizedMessage ?: "Chưa thể tải danh sách tin đã lưu.",
                    )
                }
            }
        }
    }
}

