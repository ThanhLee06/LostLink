package com.example.lostlink.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.example.lostlink.data.AccountDeletionStatus
import com.example.lostlink.data.BackendApiClient
import com.example.lostlink.data.BackendApiException
import com.example.lostlink.data.DeletionStepSummary
import com.example.lostlink.data.PostRepository
import androidx.lifecycle.ViewModel
import com.example.lostlink.data.FirebaseAccountRepository
import com.example.lostlink.ui.home.HomePost
import com.google.firebase.Timestamp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.io.IOException
import java.util.Date

class ProfileViewModel(
    private val mutations: PostRepository? = null,
    private val backendApi: BackendApiClient = BackendApiClient(),
) : ViewModel() {
    private var actionLoading = false
    private var deletionPollJob: Job? = null
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val accounts = FirebaseAccountRepository()
    private var identity: String? = null
    private var session = 0L
    private var profileRequest = 0L
    private var closed = false

    var state by mutableStateOf(ProfileState())
        private set

    private val authListener = FirebaseAuth.AuthStateListener {
        val next = accounts.currentUserId.takeIf { accounts.isUserLoggedInAndVerified }
        if (!closed) {
            deletionPollJob?.cancel()
            deletionPollJob = null
            identity = next
            actionLoading = false
            session++
            profileRequest++
            state = ProfileState(loading = false)
            if (next != null) {
                loadProfileAndPosts()
                loadExistingDeletionRequest(next, session)
            }
        }
    }

    init {
        auth.addAuthStateListener(authListener)
    }

    fun loadProfileAndPosts() {
        if (closed || state.isSavingName || state.isSavingEmailContact) return
        val currentUser = auth.currentUser
        if (currentUser == null || !accounts.isUserLoggedInAndVerified) {
            profileRequest++
            state = ProfileState(loading = false, error = "Bạn chưa đăng nhập.")
            return
        }

        val uid = currentUser.uid
        if (uid != identity) return
        val accountSession = session
        val request = ++profileRequest
        val authEmail = currentUser.email.orEmpty()
        val authDisplayName = currentUser.displayName.orEmpty()
        val authMssv = UserProfile.extractMssv(authEmail)
        val authCreationTime = currentUser.metadata?.creationTimestamp ?: System.currentTimeMillis()
        val isVerified = currentUser.isEmailVerified

        state = state.copy(loading = true, error = null)

        firestore.collection("users").document(uid).get(Source.SERVER)
            .addOnSuccessListener { doc ->
                if (!isCurrentSession(uid, accountSession) || request != profileRequest) return@addOnSuccessListener
                val nameFromDoc = doc.getString("displayName")?.trim()
                val mssvFromDoc = doc.getString("mssv")?.trim()
                val createdAtTimestamp = doc.getTimestamp("createdAt")?.toDate()?.time
                val allowEmailContactFromDoc = doc.getBoolean("allowEmailContact") ?: true

                val finalName = nameFromDoc.takeUnless { it.isNullOrEmpty() }
                    ?: authDisplayName.ifBlank { authEmail.substringBefore('@') }
                val finalMssv = mssvFromDoc.takeUnless { it.isNullOrEmpty() } ?: authMssv
                val finalJoinTime = createdAtTimestamp ?: authCreationTime

                val profile = UserProfile(
                    userId = uid,
                    displayName = finalName,
                    email = authEmail,
                    mssv = finalMssv,
                    joinTimestamp = finalJoinTime,
                    isEmailVerified = isVerified,
                    allowEmailContact = allowEmailContactFromDoc
                )
                state = state.copy(profile = profile, emailContactLoaded = true)

                if (!doc.exists()) {
                    val userDoc = mapOf(
                        "displayName" to finalName,
                        "email" to authEmail,
                        "mssv" to finalMssv,
                        "createdAt" to Timestamp(Date(finalJoinTime)),
                        "isEmailVerified" to isVerified,
                        "allowEmailContact" to true
                    )
                    firestore.collection("users").document(uid).set(userDoc, SetOptions.merge())
                }

                loadUserPosts(uid, profile, accountSession, request)
            }
            .addOnFailureListener {
                if (!isCurrentSession(uid, accountSession) || request != profileRequest) return@addOnFailureListener
                val fallbackProfile = state.profile.takeIf { it.userId == uid } ?: UserProfile(
                    userId = uid,
                    displayName = authDisplayName.ifBlank { authEmail.substringBefore('@') },
                    email = authEmail,
                    mssv = authMssv,
                    joinTimestamp = authCreationTime,
                    isEmailVerified = isVerified,
                    allowEmailContact = false
                )
                loadUserPosts(uid, fallbackProfile, accountSession, request,
                    "Chưa tải được thông tin hồ sơ và cài đặt liên hệ. Vui lòng thử lại.")
            }
    }

    private fun loadUserPosts(uid: String, profile: UserProfile, accountSession: Long, request: Long, profileError: String? = null) {
        firestore.collection("posts")
            .whereEqualTo("authorId", uid)
            .get(Source.SERVER)
            .addOnSuccessListener { querySnapshot ->
                if (!isCurrentSession(uid, accountSession) || request != profileRequest) return@addOnSuccessListener
                val posts = querySnapshot.documents.mapNotNull { doc ->
                    runCatching { HomePost.fromDocument(doc.id, doc.data.orEmpty()) }.getOrNull()
                }.sortedByDescending { it.createdAt }

                state = state.copy(
                    loading = false,
                    error = profileError,
                    profile = profile,
                    userPosts = posts
                )
            }
            .addOnFailureListener {
                if (!isCurrentSession(uid, accountSession) || request != profileRequest) return@addOnFailureListener
                state = state.copy(
                    loading = false,
                    error = profileError ?: "Chưa thể tải bài đăng của bạn. Vui lòng kiểm tra kết nối mạng và thử lại.",
                    profile = profile
                )
            }
    }

    fun selectTab(tab: ProfilePostTab) {
        state = state.copy(selectedTab = tab)
    }

    fun markStatus(post: HomePost, newStatus: String, onDone: ((Result<Unit>) -> Unit)? = null) {
        mutate({ repo, uid -> repo.updatePostStatus(uid, post.id, newStatus) }, {
            state = state.copy(userPosts = state.userPosts.map {
                if (it.id == post.id) it.copy(status = newStatus) else it
            })
        }, onDone)
    }

    fun deletePost(postId: String, onDone: ((Result<Unit>) -> Unit)? = null) {
        mutate({ repo, uid -> repo.deletePost(uid, postId) }, {
            state = state.copy(userPosts = state.userPosts.filterNot { it.id == postId })
        }, onDone)
    }

    private fun mutate(operation: suspend (PostRepository, String) -> Unit,
        onSuccess: () -> Unit, onDone: ((Result<Unit>) -> Unit)?) {
        val uid = identity ?: return
        val repo = mutations ?: return
        if (actionLoading || !accounts.isUserLoggedInAndVerified) return
        val version = session
        actionLoading = true
        viewModelScope.launch {
            val result = try { operation(repo, uid); Result.success(Unit) } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Result.failure(error)
            }
            if (!isCurrentSession(uid, version)) return@launch
            actionLoading = false
            if (result.isSuccess) onSuccess()
            else state = state.copy(error = result.exceptionOrNull()?.localizedMessage ?: "Thao tác thất bại.")
            onDone?.invoke(result)
        }
    }

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { ProfileViewModel(PostRepository(context.applicationContext)) }
        }
    }

    fun toggleNotifications() {
        state = state.copy(notificationsEnabled = !state.notificationsEnabled)
    }

    fun toggleAllowEmailContact(onResult: ((Result<Boolean>) -> Unit)? = null) {
        if (state.loading || !state.emailContactLoaded || state.isSavingEmailContact) return
        val currentUser = auth.currentUser ?: return
        val accountSession = session
        if (!isCurrentSession(currentUser.uid, accountSession) || state.profile.userId != currentUser.uid) return
        val previousValue = state.profile.allowEmailContact
        val newValue = !previousValue
        state = state.copy(profile = state.profile.copy(allowEmailContact = newValue), isSavingEmailContact = true)

        firestore.collection("users").document(currentUser.uid)
            .set(mapOf("allowEmailContact" to newValue), SetOptions.merge())
            .addOnSuccessListener {
                if (!isCurrentSession(currentUser.uid, accountSession)) return@addOnSuccessListener
                state = state.copy(isSavingEmailContact = false)
                onResult?.invoke(Result.success(newValue))
            }
            .addOnFailureListener { error ->
                if (!isCurrentSession(currentUser.uid, accountSession)) return@addOnFailureListener
                state = state.copy(profile = state.profile.copy(allowEmailContact = previousValue), isSavingEmailContact = false)
                onResult?.invoke(Result.failure(error))
            }
    }

    fun validateDisplayName(name: String): String? = UserProfile.validateDisplayName(name)

    fun updateDisplayName(newName: String, onResult: (Result<Unit>) -> Unit) {
        if (state.isSavingName) return
        val validationError = validateDisplayName(newName)
        if (validationError != null) {
            onResult(Result.failure(IllegalArgumentException(validationError)))
            return
        }

        val cleanName = newName.trim().replace(Regex("""[\r\n\t]"""), " ")
        val user = auth.currentUser
        val accountSession = session
        if (user == null || !isCurrentSession(user.uid, accountSession) || state.profile.userId != user.uid) {
            onResult(Result.failure(IllegalStateException("Bạn chưa đăng nhập.")))
            return
        }
        if (state.loading) {
            onResult(Result.failure(IllegalStateException("Đang tải hồ sơ. Vui lòng thử lại sau.")))
            return
        }

        state = state.copy(isSavingName = true)
        val profileUpdates = UserProfileChangeRequest.Builder().setDisplayName(cleanName).build()

        user.updateProfile(profileUpdates)
            .addOnSuccessListener {
                if (!isCurrentSession(user.uid, accountSession)) return@addOnSuccessListener
                val uid = user.uid
                firestore.collection("users").document(uid)
                    .set(mapOf("displayName" to cleanName), SetOptions.merge())
                    .addOnSuccessListener {
                        if (!isCurrentSession(uid, accountSession)) return@addOnSuccessListener
                        state = state.copy(
                            profile = state.profile.copy(displayName = cleanName),
                            isSavingName = false
                        )
                        onResult(Result.success(Unit))
                    }
                    .addOnFailureListener { error ->
                        if (!isCurrentSession(uid, accountSession)) return@addOnFailureListener
                        state = state.copy(isSavingName = false)
                        onResult(Result.failure(IllegalStateException("Chưa đồng bộ được tên vào hồ sơ. Vui lòng thử lại.", error)))
                    }
            }
            .addOnFailureListener { err ->
                if (!isCurrentSession(user.uid, accountSession)) return@addOnFailureListener
                state = state.copy(isSavingName = false)
                onResult(Result.failure(err))
            }
    }

    fun requestAccountDeletion(
        reauthPassword: String? = null,
        onCompletedSignOut: () -> Unit,
    ) {
        val uid = identity ?: return
        val accountSession = session
        if (state.deletion.isSubmitting || !accounts.isUserLoggedInAndVerified) return

        state = state.copy(
            deletion = state.deletion.copy(
                isSubmitting = true,
                status = state.deletion.status ?: "IN_PROGRESS",
                error = null,
            )
        )
        viewModelScope.launch {
            if (!reauthPassword.isNullOrBlank()) {
                val reauthErr = runCatching {
                    val user = auth.currentUser ?: error("Bạn chưa đăng nhập.")
                    val email = user.email?.takeIf { it.isNotBlank() } ?: error("Không tìm thấy email tài khoản.")
                    user.reauthenticate(EmailAuthProvider.getCredential(email, reauthPassword)).await()
                }.exceptionOrNull()
                if (!isCurrentSession(uid, accountSession)) return@launch
                if (reauthErr != null) {
                    state = state.copy(
                        deletion = state.deletion.copy(
                            isSubmitting = false,
                            status = "FAILED",
                            requiresReauth = true,
                            error = "Mật khẩu xác nhận không đúng hoặc không thể xác thực lại. Vui lòng kiểm tra lại.",
                        )
                    )
                    return@launch
                }
            }

            val result = backendApi.requestAccountDeletion()
            if (!isCurrentSession(uid, accountSession)) return@launch

            result.onSuccess { statusDto ->
                applyDeletionStatus(statusDto, uid, accountSession, onCompletedSignOut)
            }.onFailure { err ->
                if (err is IOException && err !is BackendApiException) {
                    performClientSideFirebaseDeletion(uid, accountSession, onCompletedSignOut)
                } else {
                    handleDeletionError(err, uid, accountSession, onCompletedSignOut)
                }
            }
        }
    }

    fun resumeAccountDeletion(
        reauthPassword: String? = null,
        onCompletedSignOut: () -> Unit,
    ) {
        val uid = identity ?: return
        val accountSession = session
        if (state.deletion.isSubmitting || !accounts.isUserLoggedInAndVerified) return

        if (state.deletion.requiresReauth || !reauthPassword.isNullOrBlank()) {
            requestAccountDeletion(reauthPassword = reauthPassword, onCompletedSignOut = onCompletedSignOut)
            return
        }

        state = state.copy(
            deletion = state.deletion.copy(isSubmitting = true, error = null)
        )
        viewModelScope.launch {
            val result = backendApi.resumeAccountDeletion()
            if (!isCurrentSession(uid, accountSession)) return@launch

            result.onSuccess { statusDto ->
                applyDeletionStatus(statusDto, uid, accountSession, onCompletedSignOut)
            }.onFailure { err ->
                val apiErr = err as? BackendApiException
                if (apiErr?.statusCode == 404) {
                    state = state.copy(deletion = state.deletion.copy(isSubmitting = false))
                    requestAccountDeletion(onCompletedSignOut = onCompletedSignOut)
                } else if (err is IOException && err !is BackendApiException) {
                    performClientSideFirebaseDeletion(uid, accountSession, onCompletedSignOut)
                } else {
                    handleDeletionError(err, uid, accountSession, onCompletedSignOut)
                }
            }
        }
    }

    private suspend fun performClientSideFirebaseDeletion(
        uid: String,
        accountSession: Long,
        onCompletedSignOut: () -> Unit,
    ) {
        var steps = state.deletion.steps.copy(
            sessionRevoked = "DONE",
            firestorePostsStatus = if (state.deletion.steps.firestorePostsStatus == "DONE") "DONE" else "IN_PROGRESS",
        )
        state = state.copy(
            deletion = AccountDeletionUiState(
                isSubmitting = true,
                isPolling = false,
                status = "IN_PROGRESS",
                steps = steps,
                error = null,
                sessionRevokedConfirmed = true,
                requiresReauth = false,
            )
        )

        try {
            // 1. Delete user's posts on Firestore & audit media
            val postQuery = firestore.collection("posts")
                .whereEqualTo("authorId", uid)
                .get(Source.SERVER)
                .await()
            if (!isCurrentSession(uid, accountSession)) return

            var deletedPosts = steps.firestorePostsDeleted
            var managedMediaCount = steps.mediaAssetsDeleted
            var legacyMediaCount = steps.legacyAuditCount

            for (doc in postQuery.documents) {
                if (!isCurrentSession(uid, accountSession)) return
                val data = doc.data.orEmpty()
                val assetIds = (data["mediaAssetIds"] as? List<*>)?.filterIsInstance<String>()
                    ?.filter { it.isNotBlank() }.orEmpty()
                if (assetIds.isNotEmpty()) {
                    managedMediaCount += assetIds.size
                } else {
                    val hasLegacyUrl = !doc.getString("imageUrl").isNullOrBlank() ||
                        ((data["imageUrls"] as? List<*>)?.isNotEmpty() == true)
                    if (hasLegacyUrl) legacyMediaCount += 1
                }

                val repo = mutations
                val deletedViaRepo = if (repo != null) {
                    runCatching { repo.deletePost(uid, doc.id) }.isSuccess
                } else {
                    false
                }
                if (!deletedViaRepo) {
                    doc.reference.delete().await()
                }

                deletedPosts += 1
                steps = steps.copy(
                    firestorePostsStatus = "IN_PROGRESS",
                    firestorePostsDeleted = deletedPosts,
                    mediaAssetsStatus = "IN_PROGRESS",
                    mediaAssetsDeleted = managedMediaCount,
                    legacyAuditCount = legacyMediaCount,
                )
                state = state.copy(
                    userPosts = state.userPosts.filterNot { it.id == doc.id },
                    deletion = state.deletion.copy(steps = steps),
                )
            }

            steps = steps.copy(
                firestorePostsStatus = "DONE",
                mediaAssetsStatus = "DONE",
                firestoreUserDataStatus = "IN_PROGRESS",
            )
            state = state.copy(deletion = state.deletion.copy(steps = steps))

            // 2. Delete saved_posts/{uid}/items/* and users/{uid}
            runCatching {
                val savedSnap = firestore.collection("saved_posts")
                    .document(uid)
                    .collection("items")
                    .get(Source.SERVER)
                    .await()
                for (item in savedSnap.documents) {
                    item.reference.delete().await()
                }
            }
            if (!isCurrentSession(uid, accountSession)) return

            runCatching {
                firestore.collection("users").document(uid).delete().await()
            }
            if (!isCurrentSession(uid, accountSession)) return

            steps = steps.copy(
                firestoreUserDataStatus = "DONE",
                firebaseAuthStatus = "IN_PROGRESS",
            )
            state = state.copy(deletion = state.deletion.copy(steps = steps))

            // 3. Delete Firebase Authentication account
            val currentUser = auth.currentUser ?: error("Phiên đăng nhập không còn hợp lệ.")
            currentUser.delete().await()

            steps = steps.copy(firebaseAuthStatus = "DONE")
            state = state.copy(
                deletion = AccountDeletionUiState(
                    isSubmitting = false,
                    isPolling = false,
                    status = "COMPLETED",
                    steps = steps,
                    error = null,
                    sessionRevokedConfirmed = true,
                    requiresReauth = false,
                )
            )
            accounts.signOut()
            onCompletedSignOut()
        } catch (reauthErr: FirebaseAuthRecentLoginRequiredException) {
            if (!isCurrentSession(uid, accountSession)) return
            state = state.copy(
                deletion = state.deletion.copy(
                    isSubmitting = false,
                    isPolling = false,
                    status = "FAILED",
                    steps = steps.copy(firebaseAuthStatus = "FAILED"),
                    requiresReauth = true,
                    error = "Dữ liệu bài đăng & hồ sơ đã xóa xong. Vì phiên đăng nhập đã lâu, vui lòng nhập mật khẩu (hoặc đăng nhập lại) để hoàn tất xóa tài khoản khỏi Firebase.",
                )
            )
        } catch (err: Exception) {
            if (err is kotlinx.coroutines.CancellationException) throw err
            if (!isCurrentSession(uid, accountSession)) return
            state = state.copy(
                deletion = state.deletion.copy(
                    isSubmitting = false,
                    isPolling = false,
                    status = "FAILED",
                    steps = steps,
                    error = err.localizedMessage ?: "Không thể hoàn tất xóa tài khoản. Vui lòng thử lại.",
                )
            )
        }
    }

    fun refreshDeletionStatus(onCompletedSignOut: () -> Unit) {
        val uid = identity ?: return
        val accountSession = session
        viewModelScope.launch {
            val fresh = fetchDeletionStatusWithFallback(uid)
            if (!isCurrentSession(uid, accountSession)) return@launch
            if (fresh != null) {
                applyDeletionStatus(fresh, uid, accountSession, onCompletedSignOut)
            }
        }
    }

    private fun loadExistingDeletionRequest(uid: String, accountSession: Long) {
        firestore.collection("deletion_requests").document(uid)
            .get(Source.SERVER)
            .addOnSuccessListener { doc ->
                if (!isCurrentSession(uid, accountSession) || !doc.exists()) return@addOnSuccessListener
                val dto = parseFirestoreDeletionDoc(uid, doc.data.orEmpty())
                state = state.copy(
                    deletion = AccountDeletionUiState(
                        isSubmitting = false,
                        isPolling = false,
                        status = dto.status,
                        steps = dto.steps,
                        error = dto.lastError,
                        sessionRevokedConfirmed = dto.steps.sessionRevoked == "DONE",
                    )
                )
            }
    }

    private fun applyDeletionStatus(
        dto: AccountDeletionStatus,
        uid: String,
        accountSession: Long,
        onCompletedSignOut: () -> Unit,
    ) {
        val next = AccountDeletionUiState(
            isSubmitting = false,
            isPolling = dto.status == "PENDING" || dto.status == "IN_PROGRESS",
            status = dto.status,
            steps = dto.steps,
            error = dto.lastError,
            sessionRevokedConfirmed = state.deletion.sessionRevokedConfirmed || dto.steps.sessionRevoked == "DONE",
        )
        state = state.copy(deletion = next)

        when (dto.status) {
            "COMPLETED" -> {
                deletionPollJob?.cancel()
                deletionPollJob = null
                onCompletedSignOut()
            }
            "PENDING", "IN_PROGRESS" -> startDeletionPolling(uid, accountSession, onCompletedSignOut)
            else -> {
                deletionPollJob?.cancel()
                deletionPollJob = null
            }
        }
    }

    private fun handleDeletionError(
        err: Throwable,
        uid: String,
        accountSession: Long,
        onCompletedSignOut: () -> Unit,
    ) {
        val apiErr = err as? BackendApiException
        if (apiErr?.code == "ACCOUNT_ALREADY_DELETED" || apiErr?.statusCode == 409) {
            deletionPollJob?.cancel()
            state = state.copy(
                deletion = state.deletion.copy(
                    isSubmitting = false,
                    isPolling = false,
                    status = "COMPLETED",
                    error = null,
                    sessionRevokedConfirmed = true,
                )
            )
            onCompletedSignOut()
            return
        }
        if (apiErr?.code == "TOKEN_REVOKED" || apiErr?.statusCode == 401) {
            state = state.copy(
                deletion = state.deletion.copy(
                    isSubmitting = false,
                    status = state.deletion.status ?: "IN_PROGRESS",
                    sessionRevokedConfirmed = true,
                    error = null,
                )
            )
            startDeletionPolling(uid, accountSession, onCompletedSignOut)
            return
        }
        state = state.copy(
            deletion = state.deletion.copy(
                isSubmitting = false,
                isPolling = false,
                error = err.localizedMessage ?: "Không thể gửi yêu cầu xóa tài khoản. Vui lòng thử lại.",
            )
        )
    }

    private fun startDeletionPolling(
        uid: String,
        accountSession: Long,
        onCompletedSignOut: () -> Unit,
    ) {
        if (deletionPollJob?.isActive == true) return
        deletionPollJob = viewModelScope.launch {
            var attempts = 0
            while (attempts < 30 && isCurrentSession(uid, accountSession)) {
                delay(2500L)
                attempts++
                val dto = fetchDeletionStatusWithFallback(uid)
                if (!isCurrentSession(uid, accountSession)) return@launch
                if (dto != null) {
                    val isDone = dto.status == "COMPLETED"
                    val isFailed = dto.status == "FAILED"
                    state = state.copy(
                        deletion = AccountDeletionUiState(
                            isSubmitting = false,
                            isPolling = !isDone && !isFailed,
                            status = dto.status,
                            steps = dto.steps,
                            error = dto.lastError,
                            sessionRevokedConfirmed = state.deletion.sessionRevokedConfirmed || dto.steps.sessionRevoked == "DONE",
                        )
                    )
                    if (isDone) {
                        onCompletedSignOut()
                        return@launch
                    }
                    if (isFailed) {
                        return@launch
                    }
                }
            }
            if (isCurrentSession(uid, accountSession)) {
                state = state.copy(deletion = state.deletion.copy(isPolling = false))
            }
        }
    }

    private suspend fun fetchDeletionStatusWithFallback(uid: String): AccountDeletionStatus? {
        val apiResult = backendApi.getAccountDeletionStatus()
        apiResult.getOrNull()?.let { return it }

        // Expected after revoke_refresh_tokens: fall back to Firestore read allowed for self
        return runCatching {
            val snap = firestore.collection("deletion_requests").document(uid).get(Source.SERVER).await()
            if (snap.exists()) parseFirestoreDeletionDoc(uid, snap.data.orEmpty()) else null
        }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseFirestoreDeletionDoc(uid: String, data: Map<String, Any?>): AccountDeletionStatus {
        val rawStatus = (data["status"] as? String)?.trim().orEmpty().ifEmpty { "PENDING" }
        val lastError = (data["lastError"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val stepsMap = (data["steps"] as? Map<String, Any?>).orEmpty()
        return AccountDeletionStatus(
            uid = uid,
            status = rawStatus,
            steps = BackendApiClient.parseDeletionStepSummary(stepsMap),
            lastError = lastError,
        )
    }

    private fun isCurrentSession(uid: String, accountSession: Long): Boolean =
        !closed && session == accountSession && identity == uid &&
            accounts.currentUserId == uid && accounts.isUserLoggedInAndVerified

    override fun onCleared() {
        closed = true
        deletionPollJob?.cancel()
        deletionPollJob = null
        session++
        profileRequest++
        auth.removeAuthStateListener(authListener)
    }
}
