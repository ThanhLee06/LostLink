package com.example.lostlink

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lostlink.data.PostCreationDataSource
import com.example.lostlink.data.UploadedMedia
import com.example.lostlink.ui.post.PostFormScreen
import com.example.lostlink.ui.post.PostFormState
import com.example.lostlink.ui.post.PostFormViewModel
import com.example.lostlink.ui.post.PostType
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PostFormSubmissionUiTest {
    @get:Rule val compose = createComposeRule()
    private class Source : PostCreationDataSource {
        var exists = false
        var failWrite = false
        var failUpload = false
        var uploadCalls = 0
        var createCalls = 0
        var gate: CompletableDeferred<Unit>? = null
        override fun isActiveAccount(uid: String) = uid == "user-a"
        override suspend fun postExists(uid: String, postId: String) = exists
        override suspend fun uploadImage(uid: String, postId: String, uri: Uri): UploadedMedia {
            uploadCalls++
            if (failUpload) throw IOException("Upload failed")
            return UploadedMedia(
                assetId = "asset-ui-$uploadCalls",
                secureUrl = "https://res.cloudinary.com/test/image/upload/wallet.jpg",
                publicId = "lostlink/posts/$postId/asset-ui-$uploadCalls",
            )
        }
        override suspend fun createPost(uid: String, postId: String, draft: PostFormState, media: UploadedMedia?) {
            createCalls++
            gate?.await()
            if (failWrite) throw IOException("Lost server response")
        }
        override suspend fun updatePost(
            uid: String,
            postId: String,
            draft: PostFormState,
            media: UploadedMedia?,
            keepExistingMedia: Boolean,
        ) {
            createCalls++
            gate?.await()
            if (failWrite) throw IOException("Lost server response")
        }
    }

    private fun show(model: PostFormViewModel, created: AtomicReference<String?> = AtomicReference(null)) {
        compose.setContent {
            MaterialTheme { PostFormScreen("user-a", onLeave = {}, vm = model, onPostCreated = { created.set(it) }) }
        }
    }

    private fun ready(source: Source) = PostFormViewModel(source).apply {
        openForUser("user-a", true)
        loadSampleData()
    }

    @Test fun firstFoundFormKeepsFoundAfterComposition() {
        val model = PostFormViewModel(Source())
        model.openForUser("user-a", false)
        show(model)
        compose.runOnIdle { assertEquals(PostType.FOUND, model.state.value.type) }
    }

    @Test fun doesNotNavigateUntilServerConfirmsTheWrite() {
        val source = Source().apply { gate = CompletableDeferred() }
        val model = ready(source)
        val created = AtomicReference<String?>(null)
        val id = model.state.value.clientPostId
        show(model, created)
        compose.onNodeWithTag("submit-post").performClick()
        compose.onNodeWithTag("post-submit-progress").assertIsDisplayed()
        compose.runOnIdle {
            assertNull(created.get())
            assertEquals(1, source.createCalls)
            source.gate!!.complete(Unit)
        }
        compose.waitUntil(5_000) { created.get() != null }
        assertEquals(id, created.get())
        compose.runOnIdle { assertFalse(model.state.value.hasUnsavedChanges) }
    }

    @Test fun failedWriteKeepsDraftAndRetryConfirmsWithoutPostingAgain() {
        val source = Source().apply { failWrite = true }
        val model = ready(source)
        val created = AtomicReference<String?>(null)
        val id = model.state.value.clientPostId
        val title = model.state.value.title
        show(model, created)
        compose.onNodeWithTag("submit-post").performClick()
        compose.onNodeWithTag("retry-post").assertIsDisplayed()
        compose.runOnIdle {
            assertNull(created.get())
            assertEquals(title, model.state.value.title)
            assertEquals(id, model.state.value.clientPostId)
            source.exists = true
        }
        compose.onNodeWithTag("retry-post").performClick()
        compose.waitUntil(5_000) { created.get() != null }
        assertEquals(id, created.get())
        assertEquals(1, source.createCalls)
    }

    @Test fun failedWriteReusesUploadedImageOnRetry() {
        val source = Source().apply { failWrite = true }
        val model = ready(source)
        model.onImageSelected(Uri.parse("content://lostlink-test/photo"))
        val created = AtomicReference<String?>(null)
        show(model, created)
        compose.onNodeWithTag("submit-post").performClick()
        compose.onNodeWithTag("retry-post").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, source.uploadCalls)
            source.failWrite = false
        }
        compose.onNodeWithTag("retry-post").performClick()
        compose.waitUntil(5_000) { created.get() != null }
        assertEquals(1, source.uploadCalls)
        assertEquals(2, source.createCalls)
    }

    @Test fun uploadFailureDoesNotWriteOrNavigate() {
        val source = Source().apply { failUpload = true }
        val model = ready(source)
        model.onImageSelected(Uri.parse("content://lostlink-test/photo"))
        val created = AtomicReference<String?>(null)
        show(model, created)
        compose.onNodeWithTag("submit-post").performClick()
        compose.onNodeWithTag("post-submit-error").assertIsDisplayed()
        compose.runOnIdle {
            assertNull(created.get())
            assertEquals(0, source.createCalls)
            assertNotNull(model.state.value.imageUri)
            assertFalse(model.state.value.awaitingConfirmation)
        }
    }
}
