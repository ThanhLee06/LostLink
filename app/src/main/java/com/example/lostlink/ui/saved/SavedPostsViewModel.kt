package com.example.lostlink.ui.saved

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lostlink.data.SaveInProgressException
import com.example.lostlink.data.SavedPostEntry
import com.example.lostlink.data.SavedPostResolution
import com.example.lostlink.data.SavedPostsRepository
import com.example.lostlink.ui.home.HomePost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SavedPostItem {
    val postId: String

    data class Available(val post: HomePost) : SavedPostItem {
        override val postId: String get() = post.id
    }

    data class Unavailable(override val postId: String) : SavedPostItem
}

data class SavedPostsState(
    val loading: Boolean = false,
    val items: List<SavedPostItem> = emptyList(),
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

    private val lock = Any()
    private var boundUid: String? = null
    private val postListeners = mutableMapOf<String, () -> Unit>()
    private val resolutions = mutableMapOf<String, SavedPostResolution>()

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
            repository.savedEntries.collect { entries ->
                syncPostListeners(entries)
            }
        }
    }

    fun bindUser(uid: String?) {
        val clean = uid?.takeIf { it.isNotBlank() }
        synchronized(lock) {
            if (clean == boundUid) return
            boundUid = clean
            clearPostListenersLocked()
        }
        repository.bindUser(clean)
        if (clean == null) {
            _state.value = SavedPostsState()
        } else {
            syncPostListeners(repository.savedEntries.value)
        }
    }

    fun isBoundTo(uid: String?): Boolean = synchronized(lock) {
        boundUid == uid?.takeIf { it.isNotBlank() }
    }

    fun refresh() {
        val entries = synchronized(lock) {
            if (boundUid == null) return
            clearPostListenersLocked()
            repository.savedEntries.value
        }
        syncPostListeners(entries)
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

    fun removeSavedPost(postId: String) {
        viewModelScope.launch {
            repository.unsave(postId).onFailure { err ->
                if (err is SaveInProgressException) return@onFailure
                _state.update {
                    it.copy(userMessage = err.localizedMessage ?: "Không thể gỡ bài viết khỏi danh sách.")
                }
            }
        }
    }

    fun clearUserMessage() {
        _state.update { it.copy(userMessage = null) }
    }

    private fun syncPostListeners(entries: List<SavedPostEntry>) {
        synchronized(lock) {
            if (boundUid == null) return
            val targetIds = entries.mapTo(HashSet()) { it.postId }

            val removedIds = postListeners.keys.filter { it !in targetIds }
            removedIds.forEach { id ->
                postListeners.remove(id)?.invoke()
                resolutions.remove(id)
            }

            entries.forEach { entry ->
                val postId = entry.postId
                if (postId !in postListeners) {
                    val activeUid = boundUid
                    postListeners[postId] = {}
                    postListeners[postId] = repository.observePost(postId) { resolution ->
                        synchronized(lock) {
                            if (boundUid != activeUid || postId !in postListeners) return@observePost
                            resolutions[postId] = resolution
                            rebuildStateLocked(repository.savedEntries.value)
                        }
                    }
                }
            }

            rebuildStateLocked(entries)
        }
    }

    private fun rebuildStateLocked(entries: List<SavedPostEntry>) {
        if (entries.isEmpty()) {
            _state.update { it.copy(loading = false, items = emptyList(), error = repository.syncError.value) }
            return
        }

        val items = mutableListOf<SavedPostItem>()
        var firstError: SavedPostResolution.Error? = null
        var waitingForAny = false

        for (entry in entries) {
            when (val res = resolutions[entry.postId]) {
                null -> waitingForAny = true
                is SavedPostResolution.Available -> items += SavedPostItem.Available(res.post)
                is SavedPostResolution.Unavailable -> items += SavedPostItem.Unavailable(res.postId)
                is SavedPostResolution.Error -> {
                    if (firstError == null) firstError = res
                }
            }
        }

        val syncErr = repository.syncError.value
        val displayError = syncErr ?: firstError?.message
        _state.update {
            it.copy(
                loading = waitingForAny && items.isEmpty() && displayError == null,
                items = items,
                error = displayError,
            )
        }
    }

    private fun clearPostListenersLocked() {
        postListeners.values.forEach { it.invoke() }
        postListeners.clear()
        resolutions.clear()
    }

    override fun onCleared() {
        synchronized(lock) {
            boundUid = null
            clearPostListenersLocked()
        }
        super.onCleared()
    }
}
