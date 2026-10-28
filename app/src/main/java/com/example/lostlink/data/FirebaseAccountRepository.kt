package com.example.lostlink.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest

/** Email/password account operations. Data access is enforced again by Firebase Security Rules. */
class FirebaseAccountRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    fun register(
        name: String,
        email: String,
        password: String,
        onComplete: (Result<FirebaseUser>) -> Unit,
    ) {
        val cleanName = name.trim()
        val cleanEmail = email.trim()
        if (cleanName.isEmpty()) return onComplete(Result.failure(IllegalArgumentException("Name is required.")))
        if (!isAllowedEmail(cleanEmail)) {
            return onComplete(Result.failure(IllegalArgumentException("Use your @ut.edu.vn email address.")))
        }
        if (password.length < 6) {
            return onComplete(Result.failure(IllegalArgumentException("Password must be at least 6 characters.")))
        }

        auth.createUserWithEmailAndPassword(cleanEmail, password)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user == null) {
                    onComplete(Result.failure(IllegalStateException("Firebase did not return the new account.")))
                } else {
                    val profile = UserProfileChangeRequest.Builder().setDisplayName(cleanName).build()
                    user.updateProfile(profile)
                        .continueWithTask { user.sendEmailVerification() }
                        .addOnSuccessListener {
                            auth.signOut()
                            onComplete(Result.success(user))
                        }
                        .addOnFailureListener { error -> onComplete(Result.failure(error)) }
                }
            }
            .addOnFailureListener { error -> onComplete(Result.failure(error)) }
    }

    fun signIn(email: String, password: String, onComplete: (Result<FirebaseUser>) -> Unit) {
        val cleanEmail = email.trim()
        if (!isAllowedEmail(cleanEmail)) {
            return onComplete(Result.failure(IllegalArgumentException("Use your @ut.edu.vn email address.")))
        }
        auth.signInWithEmailAndPassword(cleanEmail, password)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user == null) {
                    onComplete(Result.failure(IllegalStateException("Firebase did not return the signed-in account.")))
                } else if (!user.isEmailVerified) {
                    auth.signOut()
                    onComplete(Result.failure(IllegalStateException("Verify your email before signing in.")))
                } else {
                    onComplete(Result.success(user))
                }
            }
            .addOnFailureListener { error -> onComplete(Result.failure(error)) }
    }

    fun resendVerification(onComplete: (Result<Unit>) -> Unit) {
        val user = auth.currentUser
        if (user == null) return onComplete(Result.failure(IllegalStateException("Sign in first.")))
        user.sendEmailVerification()
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { error -> onComplete(Result.failure(error)) }
    }

    fun sendVerificationEmailForAccount(email: String, password: String, onComplete: (Result<Unit>) -> Unit) {
        val cleanEmail = email.trim()
        if (!isAllowedEmail(cleanEmail)) {
            return onComplete(Result.failure(IllegalArgumentException("Vui lòng sử dụng địa chỉ email @ut.edu.vn.")))
        }
        auth.signInWithEmailAndPassword(cleanEmail, password)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user != null) {
                    user.sendEmailVerification()
                        .addOnSuccessListener {
                            auth.signOut()
                            onComplete(Result.success(Unit))
                        }
                        .addOnFailureListener { err ->
                            auth.signOut()
                            onComplete(Result.failure(err))
                        }
                } else {
                    onComplete(Result.failure(IllegalStateException("Không thể gửi lại email xác minh.")))
                }
            }
            .addOnFailureListener { err -> onComplete(Result.failure(err)) }
    }

    fun sendPasswordReset(email: String, onComplete: (Result<Unit>) -> Unit) {
        val cleanEmail = email.trim()
        if (!isAllowedEmail(cleanEmail)) {
            return onComplete(Result.failure(IllegalArgumentException("Vui lòng sử dụng địa chỉ email @ut.edu.vn.")))
        }
        auth.sendPasswordResetEmail(cleanEmail)
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { error -> onComplete(Result.failure(error)) }
    }

    fun signInWithGoogle(idToken: String, onComplete: (Result<FirebaseUser>) -> Unit) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user == null) {
                    onComplete(Result.failure(IllegalStateException("Không nhận được tài khoản Google.")))
                } else {
                    val userEmail = user.email.orEmpty()
                    if (!isAllowedEmail(userEmail)) {
                        auth.signOut()
                        onComplete(Result.failure(IllegalArgumentException("Vui lòng chọn tài khoản Google sinh viên có đuôi @ut.edu.vn ($userEmail).")))
                    } else {
                        onComplete(Result.success(user))
                    }
                }
            }
            .addOnFailureListener { error -> onComplete(Result.failure(error)) }
    }

    fun refreshVerification(onComplete: (Result<Boolean>) -> Unit) {
        val user = auth.currentUser
        if (user == null) return onComplete(Result.failure(IllegalStateException("No signed-in account.")))
        user.reload()
            .addOnSuccessListener {
                user.getIdToken(true)
                    .addOnSuccessListener {
                        val refreshedUser = auth.currentUser
                        onComplete(Result.success(
                            refreshedUser?.isEmailVerified == true && isAllowedEmail(refreshedUser.email.orEmpty()),
                        ))
                    }
                    .addOnFailureListener { error -> onComplete(Result.failure(error)) }
            }
            .addOnFailureListener { error -> onComplete(Result.failure(error)) }
    }

    fun signOut() = auth.signOut()

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val currentUserId: String?
        get() = auth.currentUser?.uid

    val isUserLoggedInAndVerified: Boolean
        get() {
            val user = auth.currentUser ?: return false
            return user.isEmailVerified && isAllowedEmail(user.email.orEmpty())
        }

    companion object {
        fun isAllowedEmail(email: String): Boolean =
            email.trim().substringAfterLast('@', "").equals("ut.edu.vn", ignoreCase = true)
    }
}
