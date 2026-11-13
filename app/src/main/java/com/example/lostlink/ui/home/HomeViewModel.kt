package com.example.lostlink.ui.home

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lostlink.data.FirebaseAccountRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class HomeState(
    val allowed: Boolean = false,
    val posts: List<HomePost> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
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
    var state by mutableStateOf(HomeState())
        private set

    private var cursor: DocumentSnapshot? = null
    private var identity: String? = null
    private var session = 0
    private var feedRequest = 0
    private var refreshPending = false
    private var closed = false

    private val authListener = FirebaseAuth.IdTokenListener {
        val user = it.currentUser
        val next = user?.uid?.takeIf { accounts.isUserLoggedInAndVerified }
        if (!closed && next != identity) {
            identity = next
            session++
            feedRequest++
            refreshPending = false
            cursor = null
            state = HomeState(allowed = next != null)
            if (next != null) load()
        }
    }

    init { auth.addIdTokenListener(authListener) }

    fun refreshAfterPostCreated() {
        if (closed || !accounts.isUserLoggedInAndVerified || accounts.currentUserId != identity) return
        refreshPending = true
        refreshIfPending()
    }

    fun refreshAfterPostModified(postId: String? = null) {
        refreshAfterPostCreated()
    }

    fun removeDeletedPost(postId: String) {
        state = state.copy(
            posts = state.posts.filterNot { it.id == postId },
        )
        refreshAfterPostCreated()
    }

    fun removePost(postId: String) = removeDeletedPost(postId)

    fun updatePostStatus(postId: String, newStatus: String) {
        state = state.copy(posts = state.posts.map {
            if (it.id == postId) it.copy(status = newStatus) else it
        })
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
        auth.removeIdTokenListener(authListener)
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
