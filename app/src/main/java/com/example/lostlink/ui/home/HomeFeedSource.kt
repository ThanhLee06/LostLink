package com.example.lostlink.ui.home

import com.example.lostlink.data.FirebaseAccountRepository
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source

data class HomeCursor(val seconds: Long, val nanoseconds: Int, val id: String)
data class HomePage(val posts: List<HomePost>, val next: HomeCursor? = null)

/** Small boundary for testing delayed responses without reading a live Firebase project. */
interface HomeFeedSource {
    val activeUserId: String?
    fun observeAccount(onChanged: (String?) -> Unit): () -> Unit
    fun loadPage(after: HomeCursor?, onComplete: (Result<HomePage>) -> Unit)
}

class FirebaseHomeFeedSource : HomeFeedSource {
    private val accounts = FirebaseAccountRepository()
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    override val activeUserId: String?
        get() = auth.currentUser?.uid?.takeIf { accounts.isUserLoggedInAndVerified }

    override fun observeAccount(onChanged: (String?) -> Unit): () -> Unit {
        var initialized = false
        var lastIdentity: String? = null
        val authListener = FirebaseAuth.AuthStateListener {
            initialized = true
            lastIdentity = activeUserId
            onChanged(lastIdentity)
        }
        val tokenListener = FirebaseAuth.IdTokenListener {
            val next = activeUserId
            if (initialized && next != lastIdentity) {
                lastIdentity = next
                onChanged(next)
            }
        }
        auth.addAuthStateListener(authListener)
        auth.addIdTokenListener(tokenListener)
        return {
            auth.removeAuthStateListener(authListener)
            auth.removeIdTokenListener(tokenListener)
        }
    }

    override fun loadPage(after: HomeCursor?, onComplete: (Result<HomePage>) -> Unit) {
        check(activeUserId != null) { "An eligible account is required" }
        var query = firestore.collection("posts")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING).limit(21)
        if (after != null) query = query.startAfter(Timestamp(after.seconds, after.nanoseconds), after.id)
        query.get(Source.SERVER).addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                onComplete(Result.failure(task.exception ?: IllegalStateException("Feed read failed")))
            } else {
                onComplete(runCatching {
                    val documents = task.result.documents
                    val visible = documents.take(20)
                    val posts = visible.map { HomePost.fromDocument(it.id, it.data.orEmpty()) }
                    val next = if (documents.size > 20) {
                        val last = visible.last()
                        val time = requireNotNull(last.getTimestamp("createdAt"))
                        HomeCursor(time.seconds, time.nanoseconds, last.id)
                    } else null
                    HomePage(posts, next)
                })
            }
        }
    }
}
