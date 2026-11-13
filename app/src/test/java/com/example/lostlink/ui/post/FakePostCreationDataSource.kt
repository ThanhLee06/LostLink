package com.example.lostlink.ui.post

import android.net.Uri
import com.example.lostlink.data.PostCreationDataSource

internal class FakePostCreationDataSource : PostCreationDataSource {
    var activeUid = "user-a"
    var exists = false
    var readCalls = 0
    var uploadCalls = 0
    val writtenIds = mutableListOf<String>()
    val writtenDrafts = mutableListOf<PostFormState>()
    var read: suspend () -> Boolean = { exists }
    var write: suspend () -> Unit = {}

    override fun isActiveAccount(uid: String) = uid == activeUid
    override suspend fun postExists(uid: String, postId: String): Boolean {
        readCalls++
        return read()
    }
    override suspend fun uploadImage(uid: String, uri: Uri): String {
        uploadCalls++
        return "https://res.cloudinary.com/test/image/upload/test.jpg"
    }
    override suspend fun createPost(uid: String, postId: String, draft: PostFormState, imageUrl: String?) {
        writtenIds += postId
        writtenDrafts += draft
        write()
    }
}
