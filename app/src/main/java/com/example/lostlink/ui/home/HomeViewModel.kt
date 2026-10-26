package com.example.lostlink.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.lostlink.data.FirebaseAccountRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source

data class HomeState(
    val allowed: Boolean = false,
    val posts: List<HomePost> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
)

/** Reads only posts. Account operations remain in the shared FirebaseAccountRepository. */
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
    private val authListener = FirebaseAuth.IdTokenListener {
        val user = it.currentUser
        val next = user?.uid?.takeIf { accounts.isUserLoggedInAndVerified }
        if (next != identity) {
            identity = next
            session++
            feedRequest++
            cursor = null
            state = HomeState(allowed = next != null)
            if (next != null) load()
        }
    }

    init { auth.addIdTokenListener(authListener) }

    fun load(more: Boolean = false) {
        if (!accounts.isUserLoggedInAndVerified) return
        if (more && (state.loading || !state.hasMore)) return
        val version = ++feedRequest
        val accountSession = session
        val after = if (more) cursor else null
        state = state.copy(loading = true, error = null)
        // ST11 lists posts of both types. Document ID stabilizes equal timestamps.
        var query = firestore.collection("posts")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING).limit(21)
        if (after != null) query = query.startAfter(after)
        query.get(Source.SERVER).addOnCompleteListener { task ->
            if (version != feedRequest || accountSession != session || !accounts.isUserLoggedInAndVerified) return@addOnCompleteListener
            if (!task.isSuccessful) {
                state = state.copy(loading = false, error = "Chưa tải được tin. Vui lòng kiểm tra kết nối và thử lại.")
                return@addOnCompleteListener
            }
            val documents = task.result.documents
            runCatching { documents.take(20).map { HomePost.fromDocument(it.id, it.data.orEmpty()) } }
                .onSuccess { posts ->
                    cursor = documents.take(20).lastOrNull()
                    state = state.copy(
                        posts = ((if (more) state.posts else emptyList()) + posts).distinctBy { it.id },
                        loading = false, hasMore = documents.size > 20, error = null,
                    )
                }.onFailure {
                    state = state.copy(loading = false, error = "Một bài đăng thiếu thông tin bắt buộc. Vui lòng thử lại sau.")
                }
        }
    }

    override fun onCleared() { auth.removeIdTokenListener(authListener) }
}
