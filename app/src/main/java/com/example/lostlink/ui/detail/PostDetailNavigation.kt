package com.example.lostlink.ui.detail

import android.content.Context
import android.content.Intent
import com.example.lostlink.MainActivity
import com.example.lostlink.data.FirebaseAccountRepository
import com.example.lostlink.ui.home.HomePostCreation

object PostDetailNavigation {
    const val EXTRA_POST_ID = "com.example.lostlink.POST_ID"

    fun afterPostCreated(context: Context, documentId: String, authorId: String? = FirebaseAccountRepository().currentUserId): Intent {
        require(isValidPostId(documentId)) { "A Firestore document ID is required" }
        val intent = Intent(context, MainActivity::class.java).putExtra(EXTRA_POST_ID, documentId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return HomePostCreation.attach(intent, documentId, authorId)
    }
}
