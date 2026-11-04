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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.util.Date

class ProfileViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val accounts = FirebaseAccountRepository()

    var state by mutableStateOf(ProfileState())
        private set

    init {
        loadProfileAndPosts()
    }

    fun loadProfileAndPosts() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            state = state.copy(loading = false, error = "Bạn chưa đăng nhập.")
            return
        }

        val uid = currentUser.uid
        val authEmail = currentUser.email.orEmpty()
        val authDisplayName = currentUser.displayName.orEmpty()
        val authMssv = UserProfile.extractMssv(authEmail)
        val authCreationTime = currentUser.metadata?.creationTimestamp ?: System.currentTimeMillis()
        val isVerified = currentUser.isEmailVerified

        state = state.copy(loading = true, error = null)

        firestore.collection("users").document(uid).get(Source.SERVER)
            .addOnSuccessListener { doc ->
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

                loadUserPosts(uid, profile)
            }
            .addOnFailureListener {
                val fallbackProfile = UserProfile(
                    userId = uid,
                    displayName = authDisplayName.ifBlank { authEmail.substringBefore('@') },
                    email = authEmail,
                    mssv = authMssv,
                    joinTimestamp = authCreationTime,
                    isEmailVerified = isVerified
                )
                loadUserPosts(uid, fallbackProfile)
            }
    }

    private fun loadUserPosts(uid: String, profile: UserProfile) {
        firestore.collection("posts")
            .whereEqualTo("authorId", uid)
            .get(Source.SERVER)
            .addOnSuccessListener { querySnapshot ->
                val posts = querySnapshot.documents.mapNotNull { doc ->
                    runCatching { HomePost.fromDocument(doc.id, doc.data.orEmpty()) }.getOrNull()
                }.sortedByDescending { it.createdAt }

                state = state.copy(
                    loading = false,
                    error = null,
                    profile = profile,
                    userPosts = posts
                )
            }
            .addOnFailureListener {
                state = state.copy(
                    loading = false,
                    error = "Chưa thể tải bài đăng của bạn. Vui lòng kiểm tra kết nối mạng và thử lại.",
                    profile = profile
                )
            }
    }

    fun selectTab(tab: ProfilePostTab) {
        state = state.copy(selectedTab = tab)
    }

    fun toggleNotifications() {
        state = state.copy(notificationsEnabled = !state.notificationsEnabled)
    }

    fun toggleAllowEmailContact(onResult: ((Result<Boolean>) -> Unit)? = null) {
        val currentUser = auth.currentUser ?: return
        val newValue = !state.profile.allowEmailContact
        state = state.copy(profile = state.profile.copy(allowEmailContact = newValue))

        firestore.collection("users").document(currentUser.uid)
            .set(mapOf("allowEmailContact" to newValue), SetOptions.merge())
            .addOnSuccessListener {
                onResult?.invoke(Result.success(newValue))
            }
            .addOnFailureListener { error ->
                state = state.copy(profile = state.profile.copy(allowEmailContact = !newValue))
                onResult?.invoke(Result.failure(error))
            }
    }

    fun validateDisplayName(name: String): String? = UserProfile.validateDisplayName(name)

    fun updateDisplayName(newName: String, onResult: (Result<Unit>) -> Unit) {
        val validationError = validateDisplayName(newName)
        if (validationError != null) {
            onResult(Result.failure(IllegalArgumentException(validationError)))
            return
        }

        val cleanName = newName.trim().replace(Regex("""[\r\n\t]"""), " ")
        val user = auth.currentUser
        if (user == null) {
            onResult(Result.failure(IllegalStateException("Bạn chưa đăng nhập.")))
            return
        }

        state = state.copy(isSavingName = true)
        val profileUpdates = UserProfileChangeRequest.Builder().setDisplayName(cleanName).build()

        user.updateProfile(profileUpdates)
            .addOnSuccessListener {
                val uid = user.uid
                firestore.collection("users").document(uid)
                    .set(mapOf("displayName" to cleanName), SetOptions.merge())
                    .addOnSuccessListener {
                        state = state.copy(
                            profile = state.profile.copy(displayName = cleanName),
                            isSavingName = false
                        )
                        onResult(Result.success(Unit))
                    }
                    .addOnFailureListener {
                        state = state.copy(
                            profile = state.profile.copy(displayName = cleanName),
                            isSavingName = false
                        )
                        onResult(Result.success(Unit))
                    }
            }
            .addOnFailureListener { err ->
                state = state.copy(isSavingName = false)
                onResult(Result.failure(err))
            }
    }
}
