package com.example.lostlink.data

import android.content.Context
import android.net.Uri
import com.example.lostlink.ui.post.AuthorPostInfo
import com.example.lostlink.ui.post.PostFormState
import com.example.lostlink.ui.post.PostFormValidator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

interface PostCreationDataSource {
    fun isActiveAccount(uid: String): Boolean
    suspend fun postExists(uid: String, postId: String): Boolean
    suspend fun uploadImage(uid: String, uri: Uri): String
    suspend fun createPost(uid: String, postId: String, draft: PostFormState, imageUrl: String?)
}

/** Uses a stable document ID and an atomic create-or-confirm transaction on every retry. */
class PostRepository(
    context: Context,
    authProvider: () -> FirebaseAuth = { FirebaseAuth.getInstance() },
    firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
) : PostCreationDataSource {
    private val appContext = context.applicationContext
    private val auth by lazy(authProvider)
    private val firestore by lazy(firestoreProvider)
    private val images by lazy { CloudinaryRepository() }

    override fun isActiveAccount(uid: String): Boolean {
        val user = auth.currentUser ?: return false
        return user.uid == uid && user.isEmailVerified &&
            FirebaseAccountRepository.isAllowedEmail(user.email.orEmpty())
    }

    private fun requireAccount(uid: String) {
        if (!isActiveAccount(uid)) throw SecurityException("Phiên đăng nhập đã thay đổi. Vui lòng đăng nhập lại.")
    }

    override suspend fun postExists(uid: String, postId: String): Boolean = withTimeout(30_000) {
        requireAccount(uid)
        val doc = firestore.collection("posts").document(postId).get(Source.SERVER).await()
        requireAccount(uid)
        if (doc.exists() && doc.getString("authorId") != uid) throw SecurityException("Bài đăng không thuộc tài khoản này.")
        doc.exists()
    }

    override suspend fun uploadImage(uid: String, uri: Uri): String = withTimeout(45_000) {
        requireAccount(uid)
        val url = images.uploadImage(appContext, uri).getOrThrow()
            ?: error("Chưa nhận được địa chỉ ảnh đã tải lên.")
        requireAccount(uid)
        url
    }

    override suspend fun createPost(uid: String, postId: String, draft: PostFormState, imageUrl: String?) {
        require(PostFormValidator.validate(draft).isEmpty) { "Thông tin bài đăng chưa hợp lệ." }
        require(postId == draft.clientPostId && postId.isNotBlank())
        requireAccount(uid)
        val user = requireNotNull(auth.currentUser)
        val fallbackName = user.displayName.orEmpty()
        val email = user.email.orEmpty()
        val ref = firestore.collection("posts").document(postId)
        val profileRef = firestore.collection("users").document(uid)
        withTimeout(30_000) {
            firestore.runTransaction { transaction ->
                requireAccount(uid)
                val existing = transaction.get(ref)
                if (existing.exists()) {
                    if (existing.getString("authorId") != uid) throw SecurityException("Bài đăng không thuộc tài khoản này.")
                } else {
                    // Read privacy and create the post atomically. A concurrent settings
                    // change retries the transaction instead of publishing a stale email.
                    val profile = transaction.get(profileRef)
                    val author = AuthorPostInfo.buildPayload(
                        uid = uid,
                        displayName = profile.getString("displayName")?.takeIf { it.isNotBlank() } ?: fallbackName,
                        email = email,
                        allowEmailContact = profile.getBoolean("allowEmailContact") ?: true,
                    )
                    val payload = PostDocument.build(draft, author, imageUrl, FieldValue.serverTimestamp())
                    transaction.set(ref, payload)
                }
                Unit
            }.await()
        }
        requireAccount(uid)
    }
}
