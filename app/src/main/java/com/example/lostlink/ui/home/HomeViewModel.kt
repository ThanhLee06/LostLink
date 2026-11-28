package com.example.lostlink.ui.home

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lostlink.data.BackendApiClient
import com.example.lostlink.data.FirebaseAccountRepository
import com.example.lostlink.data.SmartSearchPage
import com.example.lostlink.data.SmartSearchRequest
import com.example.lostlink.data.friendlySearchError
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SmartSearchState(
    val active: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val slowLoading: Boolean = false,
    val posts: List<HomePost> = emptyList(),
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
    val error: String? = null,
    val searchMode: String = "KEYWORD",
)

data class HomeState(
    val allowed: Boolean = false,
    val posts: List<HomePost> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val search: SmartSearchState = SmartSearchState(),
)

internal data class FeedPageResult<D>(
    val posts: List<HomePost>,
    val cursorDoc: D?,
    val hasMore: Boolean,
    val consumedCount: Int,
    val malformedCount: Int,
)

internal fun <D> parseFeedPage(
    rawDocs: List<D>,
    pageSize: Int = HomeViewModel.PAGE_SIZE,
    extractId: (D) -> String,
    extractData: (D) -> Map<String, Any?>,
    onMalformed: (String) -> Unit = {},
): FeedPageResult<D> {
    val hasMore = rawDocs.size > pageSize
    val consumed = rawDocs.take(pageSize)
    var malformed = 0
    val posts = consumed.mapNotNull { doc ->
        val id = extractId(doc)
        runCatching { HomePost.fromDocument(id, extractData(doc)) }
            .onFailure {
                malformed++
                onMalformed(id)
            }
            .getOrNull()
    }.filter { it.isVisibleOnFeed }
    // Cursor is always taken from the last raw consumed doc (excluding the lookahead document)
    // so an unreadable/filtered window never traps pagination on the same page.
    val cursorDoc = consumed.lastOrNull()
    return FeedPageResult(
        posts = posts,
        cursorDoc = cursorDoc,
        hasMore = hasMore,
        consumedCount = consumed.size,
        malformedCount = malformed,
    )
}

internal fun <D> applyFeedPageToState(
    previous: HomeState,
    more: Boolean,
    page: FeedPageResult<D>,
): HomeState {
    val merged = ((if (more) previous.posts else emptyList()) + page.posts).distinctBy { it.id }
    val error = if (merged.isEmpty() && !page.hasMore && page.malformedCount > 0) {
        "Dữ liệu bài đăng chưa đúng định dạng. Vui lòng thử lại sau."
    } else {
        null
    }
    return previous.copy(
        posts = merged,
        loading = false,
        hasMore = page.hasMore,
        error = error,
    )
}

/** Reads public feed posts (moderationStatus=VISIBLE, status in [OPEN, RESOLVED]) and smoke search. */
class HomeViewModel : ViewModel() {
    val accounts = FirebaseAccountRepository()
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val apiClient = BackendApiClient()
    internal var searchExecutor: suspend (SmartSearchRequest) -> Result<SmartSearchPage> = { apiClient.searchPosts(it) }
    var state by mutableStateOf(HomeState())
        private set

    private var cursor: DocumentSnapshot? = null
    private var identity: String? = null
    private var session = 0
    private var feedRequest = 0
    private var searchRequest = 0
    private var searchJob: Job? = null
    private var slowHintJob: Job? = null
    private var lastSearchRequest: SmartSearchRequest? = null
    private var refreshPending = false
    private var closed = false

    private val authListener = FirebaseAuth.IdTokenListener {
        val user = it.currentUser
        val next = user?.uid?.takeIf { accounts.isUserLoggedInAndVerified }
        if (!closed && next != identity) {
            identity = next
            session++
            feedRequest++
            cancelSearchJobs()
            lastSearchRequest = null
            refreshPending = false
            cursor = null
            state = HomeState(allowed = next != null)
            if (next != null) load()
        }
    }

    init { auth.addIdTokenListener(authListener) }

    private fun cancelSearchJobs() {
        searchRequest++
        searchJob?.cancel()
        searchJob = null
        slowHintJob?.cancel()
        slowHintJob = null
    }

    fun updateSearchCriteria(
        query: String,
        filter: HomePostFilter,
        categories: List<String>,
        areas: List<String>,
        timeFilter: HomeTimeFilter,
        immediate: Boolean = false,
    ) {
        if (closed || !accounts.isUserLoggedInAndVerified || accounts.currentUserId != identity) return
        val trimmed = query.trim()
        val hasCriteria = trimmed.isNotEmpty() || filter != HomePostFilter.ALL ||
            categories.isNotEmpty() || areas.isNotEmpty() || timeFilter != HomeTimeFilter.ALL
        if (!hasCriteria) {
            cancelSearchJobs()
            lastSearchRequest = null
            state = state.copy(search = SmartSearchState())
            return
        }
        val req = SmartSearchRequest(
            query = trimmed,
            type = filter.takeIf { it != HomePostFilter.ALL }?.name,
            categoryIds = categories,
            areaIds = areas,
            timeFilter = timeFilter.name,
        )
        if (!immediate && req == lastSearchRequest && state.search.active) {
            return
        }
        lastSearchRequest = req
        cancelSearchJobs()
        val reqVer = searchRequest
        val accountSession = session
        // Bật trạng thái loading ngay để giữ bộ lọc và tránh giật UI trong lúc chờ debounce
        state = state.copy(
            search = state.search.copy(
                active = true,
                loading = true,
                loadingMore = false,
                slowLoading = false,
                error = null,
            )
        )
        searchJob = viewModelScope.launch {
            if (!immediate && trimmed.isNotEmpty()) delay(350L)
            executeSearch(req, append = false, reqVer = reqVer, accountSession = accountSession)
        }
    }

    fun loadMoreSearch() {
        val base = lastSearchRequest ?: return
        val cursorToken = state.search.nextCursor ?: return
        if (closed || state.search.loading || state.search.loadingMore || !state.search.hasMore) return
        val req = base.copy(cursor = cursorToken)
        cancelSearchJobs()
        val reqVer = searchRequest
        val accountSession = session
        state = state.copy(
            search = state.search.copy(
                active = true,
                loading = false,
                loadingMore = true,
                slowLoading = false,
                error = null,
            )
        )
        searchJob = viewModelScope.launch {
            executeSearch(req, append = true, reqVer = reqVer, accountSession = accountSession)
        }
    }

    fun retrySearch() {
        val req = lastSearchRequest ?: return
        if (closed || state.search.loading || state.search.loadingMore) return
        if (state.search.error != null && state.search.posts.isNotEmpty() && state.search.hasMore && state.search.nextCursor != null) {
            loadMoreSearch()
            return
        }
        cancelSearchJobs()
        val reqVer = searchRequest
        val accountSession = session
        state = state.copy(
            search = state.search.copy(
                active = true,
                loading = true,
                loadingMore = false,
                slowLoading = false,
                error = null,
            )
        )
        searchJob = viewModelScope.launch {
            executeSearch(req, append = false, reqVer = reqVer, accountSession = accountSession)
        }
    }

    private suspend fun executeSearch(req: SmartSearchRequest, append: Boolean, reqVer: Int, accountSession: Int) {
        slowHintJob?.cancel()
        slowHintJob = viewModelScope.launch {
            delay(2500L)
            if (!closed && reqVer == searchRequest && accountSession == session &&
                (state.search.loading || state.search.loadingMore)
            ) {
                state = state.copy(search = state.search.copy(slowLoading = true))
            }
        }
        val result = searchExecutor(req)
        slowHintJob?.cancel()
        if (closed || reqVer != searchRequest || accountSession != session ||
            !accounts.isUserLoggedInAndVerified || accounts.currentUserId != identity
        ) {
            return
        }
        result.fold(
            onSuccess = { page ->
                val merged = if (append) (state.search.posts + page.posts).distinctBy { it.id } else page.posts
                state = state.copy(
                    search = SmartSearchState(
                        active = true,
                        loading = false,
                        loadingMore = false,
                        slowLoading = false,
                        posts = merged,
                        hasMore = page.hasMore,
                        nextCursor = page.nextCursor,
                        error = null,
                        searchMode = page.searchMode,
                    )
                )
            },
            onFailure = { err ->
                state = state.copy(
                    search = state.search.copy(
                        active = true,
                        loading = false,
                        loadingMore = false,
                        slowLoading = false,
                        posts = if (append) state.search.posts else emptyList(),
                        hasMore = if (append) state.search.hasMore else false,
                        nextCursor = if (append) state.search.nextCursor else null,
                        error = friendlySearchError(err),
                    )
                )
            },
        )
    }

    fun refreshAfterPostCreated() {
        if (closed || !accounts.isUserLoggedInAndVerified || accounts.currentUserId != identity) return
        refreshPending = true
        refreshIfPending()
        val req = lastSearchRequest
        if (state.search.active && req != null) {
            cancelSearchJobs()
            val reqVer = searchRequest
            val accountSession = session
            state = state.copy(
                search = state.search.copy(
                    active = true,
                    loading = true,
                    loadingMore = false,
                    slowLoading = false,
                    error = null,
                )
            )
            searchJob = viewModelScope.launch {
                executeSearch(req, append = false, reqVer = reqVer, accountSession = accountSession)
            }
        }
    }

    fun refreshAfterPostModified(postId: String? = null) {
        refreshAfterPostCreated()
    }

    fun removeDeletedPost(postId: String) {
        state = state.copy(
            posts = state.posts.filterNot { it.id == postId },
            search = state.search.copy(
                posts = state.search.posts.filterNot { it.id == postId },
            ),
        )
        refreshAfterPostCreated()
    }

    fun removePost(postId: String) = removeDeletedPost(postId)

    fun updatePostStatus(postId: String, newStatus: String) {
        val nextSearchPosts = if (newStatus.equals("CLOSED", ignoreCase = true)) {
            state.search.posts.filterNot { it.id == postId }
        } else {
            state.search.posts.map { if (it.id == postId) it.copy(status = newStatus) else it }
        }
        state = state.copy(
            posts = state.posts.map {
                if (it.id == postId) it.copy(status = newStatus) else it
            },
            search = state.search.copy(posts = nextSearchPosts),
        )
    }

    private fun refreshIfPending() {
        if (refreshPending && !state.loading) {
            refreshPending = false
            load()
        }
    }

    fun load(more: Boolean = false) {
        if (closed || state.loading || !accounts.isUserLoggedInAndVerified || accounts.currentUserId != identity) return
        if (more && !state.hasMore) return
        val version = ++feedRequest
        val accountSession = session
        val after = if (more) cursor else null
        state = state.copy(loading = true, error = null)

        // Uses composite index: (moderationStatus ASC, status ASC, createdAt DESC, __name__ DESC)
        var query = firestore.collection("posts")
            .whereEqualTo("moderationStatus", "VISIBLE")
            .whereIn("status", listOf("OPEN", "RESOLVED"))
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
            .limit((PAGE_SIZE + 1).toLong())
        if (after != null) query = query.startAfter(after)

        query.get(Source.SERVER).addOnCompleteListener { task ->
            if (closed || version != feedRequest || accountSession != session ||
                !accounts.isUserLoggedInAndVerified || accounts.currentUserId != identity) {
                return@addOnCompleteListener
            }
            if (!task.isSuccessful) {
                state = state.copy(
                    loading = false,
                    error = "Chưa tải được tin. Vui lòng kiểm tra kết nối và thử lại.",
                )
                refreshIfPending()
                return@addOnCompleteListener
            }
            val page = parseFeedPage(
                rawDocs = task.result.documents,
                pageSize = PAGE_SIZE,
                extractId = { it.id },
                extractData = { it.data.orEmpty() },
                onMalformed = { docId ->
                    runCatching { Log.w("HomeViewModel", "Skipping malformed post docId=$docId") }
                },
            )
            cursor = page.cursorDoc
            state = applyFeedPageToState(state, more = more, page = page)
            refreshIfPending()
        }
    }

    override fun onCleared() {
        closed = true
        refreshPending = false
        feedRequest++
        cancelSearchJobs()
        auth.removeIdTokenListener(authListener)
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
