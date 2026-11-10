package com.example.lostlink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lostlink.data.PostRepository
import com.example.lostlink.ui.home.HomePost
import com.example.lostlink.ui.post.AreaChoice
import com.example.lostlink.ui.post.PostFormState
import com.example.lostlink.ui.post.PostType
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.Source
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in: uses only a separate demo project and named Firebase app, never the shared backend. */
@RunWith(AndroidJUnit4::class)
class PostRepositoryEmulatorTest {
    private lateinit var app: FirebaseApp
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var repository: PostRepository
    private lateinit var uid: String
    private val host get() = InstrumentationRegistry.getArguments().getString("firebaseEmulatorHost") ?: "10.0.2.2"
    private val apiKey = "fake-emulator-key"

    @Before fun setup() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("firebaseEmulatorTests") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        app = FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setProjectId("demo-lostlink").setApiKey(apiKey).setApplicationId("1:123:android:emulator")
            .build(), "post-test-${UUID.randomUUID()}")
        auth = FirebaseAuth.getInstance(app).apply { useEmulator(host, 9099) }
        firestore = FirebaseFirestore.getInstance(app).apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder().setPersistenceEnabled(false).build()
            useEmulator(host, 8088)
        }
        repository = PostRepository(context, { auth }, { firestore })
        uid = withTimeout(15_000) { createStudent(verified = true) }
    }

    @After fun cleanup() = runBlocking {
        if (::firestore.isInitialized) firestore.terminate().await()
        if (::auth.isInitialized) auth.signOut()
        if (::app.isInitialized) app.delete()
    }

    private suspend fun createStudent(verified: Boolean): String {
        auth.signOut()
        val user = auth.createUserWithEmailAndPassword("${UUID.randomUUID()}@ut.edu.vn", "TestPassword123!").await().user!!
        if (verified) {
            val payload = JSONObject().put("localId", user.uid).put("emailVerified", true).toString()
            val request = Request.Builder()
                .url("http://$host:9099/identitytoolkit.googleapis.com/v1/accounts:update?key=$apiKey")
                .header("Authorization", "Bearer owner")
                .post(payload.toRequestBody("application/json".toMediaType())).build()
            OkHttpClient().newCall(request).execute().use { response ->
                assertTrue("Auth emulator must verify the test account: ${response.code}", response.isSuccessful)
            }
            user.reload().await()
            user.getIdToken(true).await()
            assertTrue(user.isEmailVerified)
        }
        return user.uid
    }

    private fun draft() = PostFormState(type = PostType.FOUND, title = "Test wallet",
        categoryId = "wallet", description = "A black wallet found near the library", areaChoice = AreaChoice.UNKNOWN)

    private suspend fun failure(action: suspend () -> Unit): Exception {
        try { action() } catch (error: Exception) { return error }
        fail("Expected the operation to be rejected")
        error("Unreachable")
    }

    @Test fun verifiedStudentCanCreateAndReadAnActualPost() = runBlocking {
        firestore.collection("users").document(uid).set(mapOf("displayName" to "Test Student", "allowEmailContact" to true)).await()
        val draft = draft()
        assertFalse(repository.postExists(uid, draft.clientPostId))
        repository.createPost(uid, draft.clientPostId, draft, null)
        assertTrue(repository.postExists(uid, draft.clientPostId))
        val doc = firestore.collection("posts").document(draft.clientPostId).get(Source.SERVER).await()
        val post = HomePost.fromDocument(doc.id, doc.data.orEmpty())
        assertEquals(uid, post.authorId)
        assertEquals("FOUND", post.type)
        assertEquals("Test Student", post.authorName)
        assertEquals(auth.currentUser!!.email, post.contactEmail)
        assertTrue(post.allowContact)
        assertTrue(post.images.isEmpty())
        assertTrue(post.createdAt > 0)
    }

    @Test fun retryDoesNotOverwriteOrCreateAnotherDocument() = runBlocking {
        val draft = draft()
        repository.createPost(uid, draft.clientPostId, draft, null)
        val ref = firestore.collection("posts").document(draft.clientPostId)
        val original = ref.get(Source.SERVER).await()
        repository.createPost(uid, draft.clientPostId, draft.copy(title = "Must not overwrite"), null)
        val repeated = ref.get(Source.SERVER).await()
        assertEquals(original.getString("title"), repeated.getString("title"))
        assertEquals(original.getTimestamp("createdAt"), repeated.getTimestamp("createdAt"))
        assertEquals(1, firestore.collection("posts").whereEqualTo("authorId", uid).get(Source.SERVER).await().size())
    }

    @Test fun privateContactIsAbsentFromTheStoredDocument() = runBlocking {
        firestore.collection("users").document(uid).set(mapOf("allowEmailContact" to false)).await()
        val draft = draft()
        repository.createPost(uid, draft.clientPostId, draft, null)
        val doc = firestore.collection("posts").document(draft.clientPostId).get(Source.SERVER).await()
        assertEquals(false, doc.getBoolean("allowContact"))
        assertEquals("", doc.getString("contactEmail"))
    }

    @Test fun unverifiedStudentIsRejectedByRepositoryAndServerRules() = runBlocking {
        val unverifiedUid = createStudent(verified = false)
        assertFalse(repository.isActiveAccount(unverifiedUid))
        assertTrue(failure { repository.createPost(unverifiedUid, "unverified", draft().copy(clientPostId = "unverified"), null) } is SecurityException)
        val error = failure { firestore.collection("posts").get(Source.SERVER).await() }
        assertTrue(error is FirebaseFirestoreException)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, (error as FirebaseFirestoreException).code)
    }

    @Test fun anotherAccountCannotModifyTheOriginalPost() = runBlocking {
        val draft = draft()
        repository.createPost(uid, draft.clientPostId, draft, null)
        createStudent(verified = true)
        assertFalse(repository.isActiveAccount(uid))
        assertTrue(failure { repository.postExists(uid, draft.clientPostId) } is SecurityException)
        val error = failure { firestore.collection("posts").document(draft.clientPostId).update("title", "Tampered").await() }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, (error as FirebaseFirestoreException).code)
    }
}
