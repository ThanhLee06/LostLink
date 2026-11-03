package com.example.lostlink.ui.home

import android.content.Intent
import com.example.lostlink.ui.detail.isValidPostId

object HomePostCreation {
    private const val POST_ID = "com.example.lostlink.CREATED_POST_ID"
    private const val AUTHOR_ID = "com.example.lostlink.CREATED_POST_AUTHOR"

    fun attach(intent: Intent, postId: String, accountId: String?): Intent {
        require(isValidPostId(postId))
        if (accountId.isNullOrBlank()) return intent
        return intent.putExtra(POST_ID, postId).putExtra(AUTHOR_ID, accountId)
    }

    fun consume(intent: Intent, accountId: String?): Boolean {
        val postId = intent.getStringExtra(POST_ID) ?: return false
        val authorId = intent.getStringExtra(AUTHOR_ID)
        intent.removeExtra(POST_ID)
        intent.removeExtra(AUTHOR_ID)
        return accountId != null && accountId == authorId && isValidPostId(postId)
    }
}
