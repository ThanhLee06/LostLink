package com.example.lostlink.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.example.lostlink.ui.home.HomePost
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

interface MyPostsDataSource {
    fun observeMyPosts(authorId: String): Flow<List<HomePost>>
    suspend fun updatePostStatus(postId: String, newStatus: String): Result<Unit>
    suspend fun deletePost(postId: String): Result<Unit>
}

class MyPostsRepository(
    context: Context,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) : MyPostsDataSource {
    private val mutations = PostRepository(context.applicationContext)
    private fun currentUid() = requireNotNull(FirebaseAuth.getInstance().currentUser?.uid) {
        "Bạn chưa đăng nhập."
    }


    override fun observeMyPosts(authorId: String): Flow<List<HomePost>> = callbackFlow {
        if (authorId.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val registration: ListenerRegistration = firestore.collection("posts")
            .whereEqualTo("authorId", authorId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val posts = snapshot.documents.mapNotNull { doc ->
                        runCatching {
                            HomePost.fromDocument(doc.id, doc.data.orEmpty())
                        }.getOrNull()
                    }.sortedByDescending { it.createdAt }

                    trySend(posts)
                }
            }

        awaitClose { registration.remove() }
    }

    override suspend fun updatePostStatus(postId: String, newStatus: String): Result<Unit> = runCatching {
        mutations.updatePostStatus(currentUid(), postId, newStatus)
        Unit
    }

    override suspend fun deletePost(postId: String): Result<Unit> = runCatching {
        mutations.deletePost(currentUid(), postId)
        Unit
    }
}
