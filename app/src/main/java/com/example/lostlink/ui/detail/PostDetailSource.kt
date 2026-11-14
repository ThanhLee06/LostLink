package com.example.lostlink.ui.detail

import com.example.lostlink.data.FirebaseAccountRepository
import com.example.lostlink.ui.home.HomePost
import com.example.lostlink.ui.post.PostCategories
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.util.Locale

/** Detail-only projection; moderationReason is sourced strictly from post_moderations/{postId} for the owner. */
data class PostDetail(
    val post: HomePost,
    val category: String = "",
    val moderationReason: String? = null,
) {
    companion object {
        fun fromDocument(id: String, fields: Map<String, Any?>): PostDetail {
            fun text(key: String) = (fields[key] as? String)?.trim().orEmpty()
            val post = HomePost.fromDocument(id, fields + ("type" to text("type").uppercase(Locale.ROOT)))
            val rawCatId = text("categoryId")
            val rawCatLabel = text("category")
            val category = PostCategories.findByIdOrAlias(rawCatId)?.label
                ?: PostCategories.findByIdOrAlias(rawCatLabel)?.label
                ?: rawCatLabel
            return PostDetail(post = post, category = category, moderationReason = null)
        }
    }
}

enum class PostReadFailure { ACCESS_DENIED, NETWORK, INVALID_DATA, OTHER }
class PostReadException(val failure: PostReadFailure, cause: Throwable? = null) : Exception(cause)

interface PostDetailSource {
    val activeUserId: String?
    fun observeAccount(onChanged: (String?) -> Unit): () -> Unit
    fun read(id: String, onComplete: (Result<PostDetail?>) -> Unit)
}

class FirebasePostDetailSource : PostDetailSource {
    private val auth = FirebaseAuth.getInstance()
    private val accounts = FirebaseAccountRepository()
    private val firestore = FirebaseFirestore.getInstance()
    override val activeUserId: String?
        get() = auth.currentUser?.uid?.takeIf { accounts.isUserLoggedInAndVerified }

    override fun observeAccount(onChanged: (String?) -> Unit): () -> Unit {
        var initialized = false
        var lastIdentity: String? = null
        val accountListener = FirebaseAuth.AuthStateListener {
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
        auth.addAuthStateListener(accountListener)
        auth.addIdTokenListener(tokenListener)
        return { auth.removeAuthStateListener(accountListener); auth.removeIdTokenListener(tokenListener) }
    }

    override fun read(id: String, onComplete: (Result<PostDetail?>) -> Unit) {
        val callerUid = activeUserId
        if (callerUid == null) {
            onComplete(Result.failure(PostReadException(PostReadFailure.ACCESS_DENIED)))
            return
        }
        firestore.collection("posts").document(id).get(Source.SERVER).addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                val failure = when ((task.exception as? FirebaseFirestoreException)?.code) {
                    FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    FirebaseFirestoreException.Code.UNAUTHENTICATED -> PostReadFailure.ACCESS_DENIED
                    FirebaseFirestoreException.Code.UNAVAILABLE,
                    FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                    FirebaseFirestoreException.Code.CANCELLED -> PostReadFailure.NETWORK
                    else -> PostReadFailure.OTHER
                }
                onComplete(Result.failure(PostReadException(failure, task.exception)))
            } else {
                val document = task.result
                if (!document.exists()) {
                    onComplete(Result.success(null))
                    return@addOnCompleteListener
                }
                val parsed = runCatching {
                    try {
                        PostDetail.fromDocument(document.id, document.data.orEmpty())
                    } catch (error: Exception) {
                        throw PostReadException(PostReadFailure.INVALID_DATA, error)
                    }
                }
                val baseDetail = parsed.getOrNull()
                if (baseDetail != null && baseDetail.post.isHidden && baseDetail.post.authorId == callerUid) {
                    // Author reads moderation reason strictly from post_moderations/{postId}
                    firestore.collection("post_moderations").document(id).get(Source.SERVER)
                        .addOnCompleteListener { modTask ->
                            val reason = if (modTask.isSuccessful && modTask.result.exists()) {
                                modTask.result.getString("reason")?.trim()?.takeIf { it.isNotEmpty() }
                            } else {
                                null
                            }
                            onComplete(Result.success(baseDetail.copy(moderationReason = reason)))
                        }
                } else {
                    onComplete(parsed)
                }
            }
        }
    }
}
