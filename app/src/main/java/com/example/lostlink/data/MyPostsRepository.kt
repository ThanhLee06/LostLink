package com.example.lostlink.data

import com.example.lostlink.ui.home.HomePost
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface MyPostsDataSource {
    fun observeMyPosts(authorId: String): Flow<List<HomePost>>
    suspend fun updatePostStatus(postId: String, newStatus: String): Result<Unit>
    suspend fun deletePost(postId: String): Result<Unit>
}

class MyPostsRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) : MyPostsDataSource {

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
        firestore.collection("posts").document(postId)
            .update("status", newStatus)
            .awaitTask()
        Unit
    }

    override suspend fun deletePost(postId: String): Result<Unit> = runCatching {
        firestore.collection("posts").document(postId)
            .delete()
            .awaitTask()
        Unit
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> cont.resume(result) }
    addOnFailureListener { error -> cont.resumeWithException(error) }
    addOnCanceledListener { cont.cancel() }
}

