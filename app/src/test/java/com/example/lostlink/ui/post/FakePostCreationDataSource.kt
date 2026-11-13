package com.example.lostlink.ui.post

import android.net.Uri
import com.example.lostlink.data.PostCreationDataSource
import com.example.lostlink.data.UploadedMedia

internal class FakePostCreationDataSource : PostCreationDataSource {
    var activeUid = "user-a"
    var exists = false
    var readCalls = 0
    var uploadCalls = 0
    val writtenIds = mutableListOf<String>()
    val updatedIds = mutableListOf<String>()
    val writtenDrafts = mutableListOf<PostFormState>()
    var read: suspend () -> Boolean = { exists }
    var write: suspend () -> Unit = {}
    var update: suspend () -> Unit = {}

    override fun isActiveAccount(uid: String) = uid == activeUid

    override suspend fun postExists(uid: String, postId: String): Boolean {
        readCalls++
        return read()
    }

    override suspend fun uploadImage(uid: String, postId: String, uri: Uri): UploadedMedia {
        uploadCalls++
        return UploadedMedia(
            assetId = "asset-$uploadCalls",
            secureUrl = "https://res.cloudinary.com/test/image/upload/test.jpg",
            publicId = "lostlink/posts/$postId/asset-$uploadCalls",
        )
    }

    override suspend fun createPost(uid: String, postId: String, draft: PostFormState, media: UploadedMedia?) {
        writtenIds += postId
        writtenDrafts += draft
        write()
    }

    override suspend fun updatePost(
        uid: String,
        postId: String,
        draft: PostFormState,
        media: UploadedMedia?,
        keepExistingMedia: Boolean,
    ) {
        updatedIds += postId
        writtenDrafts += draft
        update()
    }
}
