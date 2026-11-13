package com.example.lostlink.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.lostlink.data.FirebaseAccountRepository
import com.example.lostlink.ui.home.HomePost
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.util.Date

class ProfileViewModel : ViewModel() {
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
            identity = next
            session++
            profileRequest++
            state = ProfileState(loading = false)
            if (next != null) loadProfileAndPosts()
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
        firestore.collection("posts").document(post.id)
            .update(
                mapOf(
                    "status" to newStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .addOnSuccessListener {
                state = state.copy(
                    userPosts = state.userPosts.map {
                        if (it.id == post.id) it.copy(status = newStatus) else it
                    }
                )
                onDone?.invoke(Result.success(Unit))
            }
            .addOnFailureListener { error ->
                onDone?.invoke(Result.failure(error))
            }
    }

    fun deletePost(postId: String, onDone: ((Result<Unit>) -> Unit)? = null) {
        firestore.collection("posts").document(postId).delete()
            .addOnSuccessListener {
                state = state.copy(
                    userPosts = state.userPosts.filterNot { it.id == postId }
                )
                onDone?.invoke(Result.success(Unit))
            }
            .addOnFailureListener { error ->
                onDone?.invoke(Result.failure(error))
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

    private fun isCurrentSession(uid: String, accountSession: Long): Boolean =
        !closed && session == accountSession && identity == uid &&
            accounts.currentUserId == uid && accounts.isUserLoggedInAndVerified

    override fun onCleared() {
        closed = true
        session++
        profileRequest++
        auth.removeAuthStateListener(authListener)
    }
}
