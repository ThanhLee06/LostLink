package com.example.lostlink.ui.detail

import android.content.Context
import android.content.Intent
import com.example.lostlink.MainActivity

/** Call with the confirmed Firestore ID after publishing; both entry points use the same screen. */
object PostDetailNavigation {
    const val EXTRA_POST_ID = "com.example.lostlink.POST_ID"

    fun afterPostCreated(context: Context, documentId: String): Intent {
        require(isValidPostId(documentId)) { "A Firestore document ID is required" }
        return Intent(context, MainActivity::class.java).putExtra(EXTRA_POST_ID, documentId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
