package com.example.lostlink.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.lostlink.ui.post.AuthorPostInfo
import com.example.lostlink.ui.post.PostFormState
import com.example.lostlink.ui.post.PostFormValidator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

data class PostModerationInfo(
    val postId: String,
    val moderationStatus: String,
    val reason: String?,
)

interface PostCreationDataSource {
    fun isActiveAccount(uid: String): Boolean
    suspend fun postExists(uid: String, postId: String): Boolean
    suspend fun uploadImage(uid: String, postId: String, uri: Uri): UploadedMedia
    suspend fun createPost(uid: String, postId: String, draft: PostFormState, media: UploadedMedia?)
    suspend fun updatePost(
        uid: String,
        postId: String,
        draft: PostFormState,
        media: UploadedMedia?,
        keepExistingMedia: Boolean,
    )
}

/** Coordinates signed media uploads and atomic Firestore post + outbox mutations. */
class PostRepository(
    context: Context,
    authProvider: () -> FirebaseAuth = { FirebaseAuth.getInstance() },
    firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
    apiClientProvider: ((FirebaseAuth) -> BackendApiClient) = { auth -> BackendApiClient(auth = auth) },
) : PostCreationDataSource {
    private val appContext = context.applicationContext
    private val auth by lazy(authProvider)
    private val firestore by lazy(firestoreProvider)
    private val apiClient by lazy { apiClientProvider(auth) }
    private val images by lazy { CloudinaryRepository(apiClient = apiClient) }

    override fun isActiveAccount(uid: String): Boolean {
        val user = auth.currentUser ?: return false
        return user.uid == uid && user.isEmailVerified &&
            FirebaseAccountRepository.isAllowedEmail(user.email.orEmpty())
    }

    private fun requireAccount(uid: String) {
        if (!isActiveAccount(uid)) {
            throw SecurityException("Phiên đăng nhập đã thay đổi. Vui lòng đăng nhập lại.")
        }
    }

    override suspend fun postExists(uid: String, postId: String): Boolean = withTimeout(30_000) {
        requireAccount(uid)
        val doc = firestore.collection("posts").document(postId).get(Source.SERVER).await()
        requireAccount(uid)
        if (doc.exists() && doc.getString("authorId") != uid) {
            throw SecurityException("Bài đăng không thuộc tài khoản này.")
        }
        doc.exists()
    }

    override suspend fun uploadImage(uid: String, postId: String, uri: Uri): UploadedMedia =
        withTimeout(45_000) {
            requireAccount(uid)
            val uploaded = images.uploadSignedImage(appContext, postId, uri).getOrThrow()
                ?: error("Chưa nhận được thông tin ảnh đã tải lên.")
            requireAccount(uid)
            uploaded
        }

    override suspend fun createPost(
        uid: String,
        postId: String,
        draft: PostFormState,
        media: UploadedMedia?,
    ) {
        require(PostFormValidator.validate(draft).isEmpty) { "Thông tin bài đăng chưa hợp lệ." }
        require(postId == draft.clientPostId && postId.isNotBlank())
        requireAccount(uid)
        val user = requireNotNull(auth.currentUser)
        val fallbackName = user.displayName.orEmpty()
        val email = user.email.orEmpty()
        val postRef = firestore.collection("posts").document(postId)
        val eventRef = firestore.collection("post_events").document("${postId}_v1")
        val tombstoneRef = firestore.collection("post_tombstones").document(postId)
        val profileRef = firestore.collection("users").document(uid)

        withTimeout(30_000) {
            firestore.runTransaction { transaction ->
                requireAccount(uid)
                val existing = transaction.get(postRef)
                if (existing.exists()) {
                    if (existing.getString("authorId") != uid) {
                        throw SecurityException("Bài đăng không thuộc tài khoản này.")
                    }
                    return@runTransaction
                }

                // Read before writes inside Firestore transaction
                val tombstone = transaction.get(tombstoneRef)
                if (tombstone.exists()) {
                    throw IllegalStateException("Mã bài đăng này đã bị xóa trước đó và không thể dùng lại.")
                }
                val profile = transaction.get(profileRef)
                val author = AuthorPostInfo.buildPayload(
                    uid = uid,
                    displayName = profile.getString("displayName")?.takeIf { it.isNotBlank() } ?: fallbackName,
                    email = email,
                    allowEmailContact = profile.getBoolean("allowEmailContact") ?: true,
                )
                val nowMarker = FieldValue.serverTimestamp()
                val postPayload = PostDocument.buildCreate(draft, author, media, nowMarker)
                val eventPayload = PostDocument.buildEventPayload(
                    postId = postId,
                    authorId = uid,
                    postVersion = 1L,
                    changeType = "CREATE",
                    createdAt = nowMarker,
                    imageAssetRefs = listOfNotNull(media?.assetId),
                )
                transaction.set(postRef, postPayload)
                transaction.set(eventRef, eventPayload)
            }.await()
        }
        requireAccount(uid)
        triggerWakeBestEffort()
    }

    override suspend fun updatePost(
        uid: String,
        postId: String,
        draft: PostFormState,
        media: UploadedMedia?,
        keepExistingMedia: Boolean,
    ) {
        require(PostFormValidator.validate(draft).isEmpty) { "Thông tin bài đăng chưa hợp lệ." }
        require(postId == draft.clientPostId && postId.isNotBlank())
        requireAccount(uid)
        val postRef = firestore.collection("posts").document(postId)

        withTimeout(30_000) {
            firestore.runTransaction { transaction ->
                requireAccount(uid)
                val existing = transaction.get(postRef)
                if (!existing.exists()) {
                    throw IllegalStateException("Bài đăng không tồn tại hoặc đã bị xóa.")
                }
                if (existing.getString("authorId") != uid) {
                    throw SecurityException("Bài đăng không thuộc tài khoản này.")
                }
                if (existing.getString("moderationStatus") == "HIDDEN") {
                    throw IllegalStateException("Bài đăng đang bị ẩn bởi quản trị viên nên không thể chỉnh sửa.")
                }
                val existingType = existing.getString("type").orEmpty()
                if (existingType != draft.type.name) {
                    throw IllegalStateException("Không thể thay đổi loại tin (Mất/Nhặt) khi chỉnh sửa.")
                }

                val oldAssets = (existing.get("mediaAssetIds") as? List<*>)
                    ?.filterIsInstance<String>()
                    .orEmpty()
                val finalMedia: UploadedMedia? = when {
                    media != null -> media
                    keepExistingMedia -> {
                        val existingAssetId = oldAssets.firstOrNull()?.takeIf { it.isNotBlank() }
                        val existingUrl = existing.getString("imageUrl")?.takeIf { it.startsWith("https://") }
                        val existingPublicId = existing.getString("imagePublicId")?.takeIf { it.isNotBlank() }
                        if (existingAssetId == null && existingUrl != null) {
                            throw IllegalStateException("Ảnh cũ chưa có thông tin sở hữu. Vui lòng chọn ảnh thay thế hoặc gỡ ảnh trước khi lưu.")
                        }
                        if (existingAssetId != null && existingUrl != null) {
                            UploadedMedia(
                                assetId = existingAssetId,
                                secureUrl = existingUrl,
                                publicId = existingPublicId ?: "",
                            )
                        } else {
                            null
                        }
                    }
                    else -> null
                }

                val nextAssetSet = setOfNotNull(finalMedia?.assetId)
                val removedAssets = oldAssets.filter { it !in nextAssetSet }.take(1)
                val prevVersion = (existing.getLong("version") ?: 1L).coerceAtLeast(1L)
                val nextVersion = prevVersion + 1L
                val nowMarker = FieldValue.serverTimestamp()

                val updateFields = PostDocument.buildUpdateFields(draft, nextVersion, finalMedia, nowMarker)
                val eventRef = firestore.collection("post_events").document("${postId}_v$nextVersion")
                val eventPayload = PostDocument.buildEventPayload(
                    postId = postId,
                    authorId = uid,
                    postVersion = nextVersion,
                    changeType = "UPDATE",
                    createdAt = nowMarker,
                    imageAssetRefs = removedAssets,
                )
                transaction.update(postRef, updateFields)
                transaction.set(eventRef, eventPayload)
            }.await()
        }
        requireAccount(uid)
        triggerWakeBestEffort()
    }

    suspend fun updatePostStatus(uid: String, postId: String, newStatus: String) {
        require(newStatus in PostDocument.ALLOWED_STATUSES) { "Trạng thái không hợp lệ: $newStatus" }
        require(postId.isNotBlank())
        requireAccount(uid)
        val postRef = firestore.collection("posts").document(postId)
        var changed = false

        withTimeout(30_000) {
            firestore.runTransaction { transaction ->
                requireAccount(uid)
                val existing = transaction.get(postRef)
                if (!existing.exists()) {
                    throw IllegalStateException("Bài đăng không tồn tại hoặc đã bị xóa.")
                }
                if (existing.getString("authorId") != uid) {
                    throw SecurityException("Bài đăng không thuộc tài khoản này.")
                }
                if (existing.getString("moderationStatus") == "HIDDEN") {
                    throw IllegalStateException("Bài đăng đang bị ẩn bởi quản trị viên nên không thể đổi trạng thái.")
                }
                if (existing.getString("status") == newStatus) {
                    changed = false
                    return@runTransaction
                }

                val prevVersion = (existing.getLong("version") ?: 1L).coerceAtLeast(1L)
                val nextVersion = prevVersion + 1L
                val nowMarker = FieldValue.serverTimestamp()

                val updateFields = PostDocument.buildStatusUpdateFields(newStatus, nextVersion, nowMarker)
                val eventRef = firestore.collection("post_events").document("${postId}_v$nextVersion")
                val eventPayload = PostDocument.buildEventPayload(
                    postId = postId,
                    authorId = uid,
                    postVersion = nextVersion,
                    changeType = "STATUS",
                    createdAt = nowMarker,
                    imageAssetRefs = emptyList(),
                )
                transaction.update(postRef, updateFields)
                transaction.set(eventRef, eventPayload)
                changed = true
            }.await()
        }
        requireAccount(uid)
        if (changed) {
            triggerWakeBestEffort()
        }
    }

    suspend fun deletePost(uid: String, postId: String) {
        require(postId.isNotBlank())
        requireAccount(uid)
        val postRef = firestore.collection("posts").document(postId)
        val tombstoneRef = firestore.collection("post_tombstones").document(postId)
        var deleted = false

        withTimeout(30_000) {
            firestore.runTransaction { transaction ->
                requireAccount(uid)
                val existing = transaction.get(postRef)
                if (!existing.exists()) {
                    deleted = false
                    return@runTransaction
                }
                if (existing.getString("authorId") != uid) {
                    throw SecurityException("Bài đăng không thuộc tài khoản này.")
                }

                val prevVersion = (existing.getLong("version") ?: 1L).coerceAtLeast(1L)
                val deleteVersion = prevVersion + 1L
                val oldAssets = (existing.get("mediaAssetIds") as? List<*>)
                    ?.filterIsInstance<String>()
                    .orEmpty()
                    .take(1)
                val nowMarker = FieldValue.serverTimestamp()

                val eventRef = firestore.collection("post_events").document("${postId}_v$deleteVersion")
                val eventPayload = PostDocument.buildEventPayload(
                    postId = postId,
                    authorId = uid,
                    postVersion = deleteVersion,
                    changeType = "DELETE",
                    createdAt = nowMarker,
                    imageAssetRefs = oldAssets,
                )
                transaction.delete(postRef)
                transaction.set(eventRef, eventPayload)
                transaction.set(tombstoneRef, PostDocument.buildTombstone(uid, nowMarker))
                deleted = true
            }.await()
        }
        requireAccount(uid)
        if (deleted) {
            triggerWakeBestEffort()
        }
    }

    suspend fun readPostModeration(uid: String, postId: String): PostModerationInfo? {
        if (!isActiveAccount(uid) || postId.isBlank()) return null
        return try {
            val doc = firestore.collection("post_moderations")
                .document(postId)
                .get(Source.SERVER)
                .await()
            if (!doc.exists() || !isActiveAccount(uid)) return null
            if (doc.getString("authorId") != uid) return null
            PostModerationInfo(
                postId = postId,
                moderationStatus = doc.getString("moderationStatus").orEmpty().ifBlank { "VISIBLE" },
                reason = doc.getString("reason")?.trim()?.takeIf { it.isNotEmpty() },
            )
        } catch (err: FirebaseFirestoreException) {
            if (err.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ||
                err.code == FirebaseFirestoreException.Code.NOT_FOUND
            ) {
                null
            } else {
                Log.w("PostRepository", "Failed to read moderation info for postId=$postId code=${err.code}")
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Triggers worker sweep right after commit. Uses short HTTP timeouts and never
     * throws so an offline/slow FastAPI instance never turns a committed write into failure.
     */
    private suspend fun triggerWakeBestEffort() {
        apiClient.wakeWorker().onFailure { err ->
            Log.d("PostRepository", "Best-effort /events/wake skipped: ${err.javaClass.simpleName}")
        }
    }
}
