package com.example.lostlink.data

import android.util.Log
import com.example.lostlink.ui.home.HomePost
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

data class SavedPostEntry(
    val postId: String,
    val savedAtMillis: Long,
    val titleSnapshot: String = "",
)

class SaveInProgressException : IllegalStateException("Đang xử lý lưu bài đăng.")

interface SavedPostsBackend {
    fun observeSavedEntries(
        uid: String,
        onUpdate: (List<SavedPostEntry>) -> Unit,
        onError: (Throwable) -> Unit,
    ): () -> Unit

    suspend fun checkPostAvailableForSave(postId: String): Result<HomePost>
    suspend fun savePost(uid: String, post: HomePost): Result<Unit>
    suspend fun unsavePost(uid: String, postId: String): Result<Unit>
    suspend fun fetchAccessibleSavedPosts(entries: List<SavedPostEntry>): List<HomePost>
}

class FirestoreSavedPostsBackend(
    firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
    authProvider: () -> FirebaseAuth = { FirebaseAuth.getInstance() },
) : SavedPostsBackend {
    private val firestore by lazy(firestoreProvider)
    private val auth by lazy(authProvider)

    override fun observeSavedEntries(
        uid: String,
        onUpdate: (List<SavedPostEntry>) -> Unit,
        onError: (Throwable) -> Unit,
    ): () -> Unit {
        val registration = firestore.collection("users")
            .document(uid)
            .collection("savedPosts")
            .orderBy("savedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    logFirestoreError("observeSavedEntries", "users/$uid/savedPosts", uid, null, error)
                    onError(error)
                    return@addSnapshotListener
                }
                val entries = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    val postId = (doc.getString("postId") ?: doc.id).trim()
                    if (postId.isBlank()) return@mapNotNull null
                    val savedAt = doc.getTimestamp("savedAt")?.toDate()?.time ?: 0L
                    SavedPostEntry(
                        postId = postId,
                        savedAtMillis = savedAt,
                    )
                }
                onUpdate(entries)
            }
        return { registration.remove() }
    }

    override suspend fun checkPostAvailableForSave(postId: String): Result<HomePost> {
        return try {
            val doc = withTimeout(TIMEOUT_MS) {
                firestore.collection("posts").document(postId).get(Source.SERVER).await()
            }
            if (!doc.exists()) {
                return Result.failure(IllegalStateException("Bài đăng không tồn tại hoặc đã bị xóa."))
            }
            val post = HomePost.fromDocument(doc.id, doc.data.orEmpty())
            when {
                post.isHidden -> Result.failure(IllegalStateException("Bài đăng này đã bị ẩn và không còn khả dụng."))
                !post.isVisibleOnFeed -> Result.failure(IllegalStateException("Bài đăng này không còn khả dụng để lưu."))
                else -> Result.success(post)
            }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Result.failure(IllegalStateException("Kiểm tra bài đăng quá thời gian chờ. Vui lòng thử lại.", e))
            } else {
                throw e
            }
        } catch (e: Exception) {
            logFirestoreError("checkPostAvailableForSave", "posts/$postId", null, postId, e)
            Result.failure(IllegalStateException(mapFirestoreError(e, "Không thể kiểm tra trạng thái bài đăng."), e))
        }
    }

    override suspend fun savePost(uid: String, post: HomePost): Result<Unit> {
        val path = "users/$uid/savedPosts/${post.id}"
        return try {
            val itemRef = firestore.collection("users")
                .document(uid)
                .collection("savedPosts")
                .document(post.id)
            val alreadyExists = withTimeout(TIMEOUT_MS) {
                itemRef.get(Source.SERVER).await().exists()
            }
            if (alreadyExists) {
                runCatching { Log.d(TAG, "savePost skipped (already exists): path=$path") }
                return Result.success(Unit)
            }
            // Firestore Rules require ONLY 'postId' (matching doc ID) and 'savedAt' (serverTimestamp).
            val payload = mapOf(
                "postId" to post.id,
                "savedAt" to FieldValue.serverTimestamp(),
            )
            withTimeout(TIMEOUT_MS) {
                itemRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Result.failure(IllegalStateException("Lưu bài đăng quá thời gian chờ. Vui lòng thử lại.", e))
            } else {
                throw e
            }
        } catch (e: Exception) {
            logFirestoreError("savePost", path, uid, post.id, e)
            Result.failure(IllegalStateException(mapFirestoreError(e, "Không thể lưu bài đăng. Vui lòng thử lại."), e))
        }
    }

    override suspend fun unsavePost(uid: String, postId: String): Result<Unit> {
        val path = "users/$uid/savedPosts/$postId"
        return try {
            withTimeout(TIMEOUT_MS) {
                firestore.collection("users")
                    .document(uid)
                    .collection("savedPosts")
                    .document(postId)
                    .delete()
                    .await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Result.failure(IllegalStateException("Bỏ lưu bài đăng quá thời gian chờ. Vui lòng thử lại.", e))
            } else {
                throw e
            }
        } catch (e: Exception) {
            logFirestoreError("unsavePost", path, uid, postId, e)
            Result.failure(IllegalStateException(mapFirestoreError(e, "Không thể bỏ lưu bài đăng. Vui lòng thử lại."), e))
        }
    }

    override suspend fun fetchAccessibleSavedPosts(entries: List<SavedPostEntry>): List<HomePost> = coroutineScope {
        entries.map { entry ->
            async {
                runCatching {
                    val doc = withTimeout(TIMEOUT_MS) {
                        firestore.collection("posts").document(entry.postId).get(Source.SERVER).await()
                    }
                    if (!doc.exists()) return@async null
                    val post = HomePost.fromDocument(doc.id, doc.data.orEmpty())
                    if (post.isVisibleOnFeed) post else null
                }.getOrElse { err ->
                    if (err is CancellationException && err !is TimeoutCancellationException) throw err
                    logFirestoreError("fetchAccessibleSavedPosts", "posts/${entry.postId}", null, entry.postId, err)
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun logFirestoreError(
        operation: String,
        path: String,
        uid: String?,
        postId: String?,
        error: Throwable,
    ) {
        runCatching {
            val code = (error as? FirebaseFirestoreException)?.code?.name ?: error::class.java.simpleName
            val user = auth.currentUser
            Log.e(
                TAG,
                "Firestore error in $operation: code=$code, path=$path, targetUid=$uid, postId=$postId, " +
                    "authUid=${user?.uid}, email=${user?.email}, emailVerified=${user?.isEmailVerified}, " +
                    "message=${error.message}",
                error,
            )
        }
    }

    companion object {
        private const val TAG = "SavedPostsBackend"
        private const val TIMEOUT_MS = 15_000L

        internal fun mapFirestoreError(error: Throwable, fallback: String): String {
            val code = (error as? FirebaseFirestoreException)?.code
            return when (code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                    "Bạn không có quyền truy cập hoặc phiên đăng nhập đã hết hạn."
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                    "Không thể kết nối máy chủ. Vui lòng kiểm tra mạng và thử lại."
                else -> error.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
            }
        }
    }
}

class SavedPostsRepository(
    private val backend: SavedPostsBackend = FirestoreSavedPostsBackend(),
    private val activeUidProvider: () -> String? = {
        val accounts = FirebaseAccountRepository()
        FirebaseAuth.getInstance().currentUser?.uid?.takeIf { accounts.isUserLoggedInAndVerified }
    },
) {
    private val lock = Any()
    private var boundUid: String? = null
    private var stopListening: (() -> Unit)? = null

    private val _savedEntries = MutableStateFlow<List<SavedPostEntry>>(emptyList())
    val savedEntries: StateFlow<List<SavedPostEntry>> = _savedEntries.asStateFlow()

    private val _savedPostIds = MutableStateFlow<Set<String>>(emptySet())
    val savedPostIds: StateFlow<Set<String>> = _savedPostIds.asStateFlow()

    private val _pendingPostIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingPostIds: StateFlow<Set<String>> = _pendingPostIds.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    fun bindUser(uid: String?) {
        val normalized = uid?.takeIf { it.isNotBlank() }
        synchronized(lock) {
            if (normalized == boundUid) return
            stopListening?.invoke()
            stopListening = null
            boundUid = normalized
            _savedEntries.value = emptyList()
            _savedPostIds.value = emptySet()
            _pendingPostIds.value = emptySet()
            _syncError.value = null

            if (normalized != null) {
                stopListening = backend.observeSavedEntries(
                    uid = normalized,
                    onUpdate = { entries ->
                        synchronized(lock) {
                            if (boundUid != normalized) return@observeSavedEntries
                        }
                        _savedEntries.value = entries
                        _savedPostIds.value = entries.mapTo(LinkedHashSet()) { it.postId }
                        _syncError.value = null
                    },
                    onError = { err ->
                        synchronized(lock) {
                            if (boundUid != normalized) return@observeSavedEntries
                        }
                        _syncError.value = FirestoreSavedPostsBackend.mapFirestoreError(
                            err,
                            "Không thể đồng bộ danh sách tin đã lưu.",
                        )
                    },
                )
            }
        }
    }

    suspend fun toggleSave(postId: String, titleHint: String = ""): Result<Boolean> {
        val cleanId = postId.trim()
        if (cleanId.isBlank()) {
            return Result.failure(IllegalArgumentException("Mã bài đăng không hợp lệ."))
        }
        val uid = activeUidProvider()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(SecurityException("Vui lòng đăng nhập bằng tài khoản UTH đã xác minh."))

        bindUser(uid)
        if (!tryAcquirePending(cleanId)) {
            return Result.failure(SaveInProgressException())
        }

        val wasSaved = cleanId in _savedPostIds.value
        val previousEntry = _savedEntries.value.firstOrNull { it.postId == cleanId }

        if (isStillBound(uid)) {
            if (wasSaved) {
                _savedPostIds.update { it - cleanId }
                _savedEntries.update { list -> list.filterNot { it.postId == cleanId } }
            } else {
                _savedPostIds.update { it + cleanId }
                _savedEntries.update { list ->
                    if (list.any { it.postId == cleanId }) list
                    else listOf(SavedPostEntry(cleanId, System.currentTimeMillis(), titleHint)) + list
                }
            }
        }

        return try {
            if (wasSaved) {
                backend.unsavePost(uid, cleanId).fold(
                    onSuccess = { Result.success(false) },
                    onFailure = { err ->
                        restoreState(uid, cleanId, wasSaved = true, previousEntry = previousEntry)
                        Result.failure(err)
                    },
                )
            } else {
                val post = backend.checkPostAvailableForSave(cleanId).getOrElse { err ->
                    restoreState(uid, cleanId, wasSaved = false, previousEntry = null)
                    return Result.failure(err)
                }
                backend.savePost(uid, post).fold(
                    onSuccess = { Result.success(true) },
                    onFailure = { err ->
                        restoreState(uid, cleanId, wasSaved = false, previousEntry = null)
                        Result.failure(err)
                    },
                )
            }
        } finally {
            releasePending(cleanId)
        }
    }

    suspend fun loadAccessibleSavedPosts(entries: List<SavedPostEntry> = _savedEntries.value): List<HomePost> {
        if (entries.isEmpty()) return emptyList()
        return backend.fetchAccessibleSavedPosts(entries)
    }

    private fun restoreState(
        uid: String,
        postId: String,
        wasSaved: Boolean,
        previousEntry: SavedPostEntry?,
    ) {
        if (!isStillBound(uid)) return
        if (wasSaved) {
            _savedPostIds.update { it + postId }
            _savedEntries.update { list ->
                if (list.any { it.postId == postId }) list
                else listOf(previousEntry ?: SavedPostEntry(postId, System.currentTimeMillis())) + list
            }
        } else {
            _savedPostIds.update { it - postId }
            _savedEntries.update { list -> list.filterNot { it.postId == postId } }
        }
    }

    private fun isStillBound(uid: String): Boolean = synchronized(lock) { boundUid == uid }

    private fun tryAcquirePending(postId: String): Boolean = synchronized(lock) {
        if (postId in _pendingPostIds.value) return false
        _pendingPostIds.value = _pendingPostIds.value + postId
        true
    }

    private fun releasePending(postId: String) {
        synchronized(lock) {
            _pendingPostIds.value = _pendingPostIds.value - postId
        }
    }

    companion object {
        @Volatile
        private var instance: SavedPostsRepository? = null

        fun getInstance(): SavedPostsRepository {
            return instance ?: synchronized(this) {
                instance ?: SavedPostsRepository().also { instance = it }
            }
        }
    }
}

