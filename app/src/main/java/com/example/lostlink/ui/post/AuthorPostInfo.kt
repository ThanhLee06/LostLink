package com.example.lostlink.ui.post

import com.google.firebase.auth.FirebaseUser

data class AuthorInfo(
    val authorId: String = "",
    val authorName: String = "",
    val contactEmail: String = "",
    val allowContact: Boolean = false
)

object AuthorPostInfo {
    /**
     * Builds a self-contained author payload to attach to post documents in Firestore.
     * Uses authorName, contactEmail, and allowContact matching Task 6 specifications.
     */
    fun buildPayload(
        uid: String,
        displayName: String,
        email: String,
        allowEmailContact: Boolean
    ): Map<String, Any> {
        val cleanName = displayName.trim().ifEmpty { email.substringBefore('@') }
        val contactEmail = if (allowEmailContact) email.trim() else ""
        return mapOf(
            "authorId" to uid,
            "authorName" to cleanName,
            "contactEmail" to contactEmail,
            "allowContact" to allowEmailContact
        )
    }

    fun buildPayloadFromUser(
        user: FirebaseUser?,
        allowEmailContact: Boolean
    ): Map<String, Any> {
        if (user == null) return emptyMap()
        val uid = user.uid
        val email = user.email.orEmpty()
        val displayName = user.displayName.orEmpty().ifBlank { email.substringBefore('@') }
        return buildPayload(uid, displayName, email, allowEmailContact)
    }

    fun extract(data: Map<String, Any?>): AuthorInfo {
        fun text(key: String) = (data[key] as? String)?.trim().orEmpty()
        val contactEmail = text("contactEmail")
        val allowContact = (data["allowContact"] as? Boolean)
            ?: (data["allowEmailContact"] as? Boolean)
            ?: contactEmail.isNotBlank()
        val authorName = text("authorName")
        val authorId = text("authorId")
        return AuthorInfo(
            authorId = authorId,
            authorName = authorName,
            contactEmail = contactEmail,
            allowContact = allowContact
        )
    }
}
