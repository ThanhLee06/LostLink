package com.example.lostlink.data

import android.content.Context
import android.net.Uri
import com.example.lostlink.ui.post.AreaChoice
import com.example.lostlink.ui.post.PostAreas
import com.example.lostlink.ui.post.PostCategories
import com.example.lostlink.ui.post.PostFormState
import com.example.lostlink.ui.post.TimeMode
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface PostCreationDataSource {
    suspend fun uploadImageIfNeeded(
        context: Context,
        imageUri: Uri?,
        cachedUrl: String?
    ): Result<String?>

    suspend fun createPost(
        state: PostFormState,
        imageUrl: String?
    ): Result<String>
}

/**
 * Repository xử lý đăng bài và lưu ảnh lên Firebase Firestore / Storage (Cloudinary).
 * Tuân thủ yêu cầu ST10: kiểm tra tài khoản xác minh, mã bài ổn định, chống trùng lặp.
 */
class PostRepository(
    private val firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
    private val authProvider: () -> FirebaseAuth = { FirebaseAuth.getInstance() },
    private val accountsProvider: () -> FirebaseAccountRepository = { FirebaseAccountRepository() },
    private val cloudinaryProvider: () -> CloudinaryRepository = { CloudinaryRepository() }
) : PostCreationDataSource {

    private val firestore by lazy { firestoreProvider() }
    private val auth by lazy { authProvider() }
    private val accounts by lazy { accountsProvider() }
    private val cloudinary by lazy { cloudinaryProvider() }

    /**
     * Tải ảnh lên Storage/Cloudinary nếu có ảnh và chưa được tải lên trước đó.
     * Tái sử dụng `cachedUrl` nếu lần trước đã upload thành công nhưng lưu bài thất bại.
     */
    override suspend fun uploadImageIfNeeded(
        context: Context,
        imageUri: Uri?,
        cachedUrl: String?
    ): Result<String?> {
        if (!cachedUrl.isNullOrBlank()) {
            return Result.success(cachedUrl)
        }
        if (imageUri == null) {
            return Result.success(null)
        }
        return cloudinary.uploadImage(context, imageUri)
    }

    /**
     * Lưu bài vào Firestore với mã bài ổn định cho lần đăng.
     * Chỉ cho phép tài khoản trường đã xác minh thực hiện; mã người đăng lấy từ phiên đăng nhập.
     */
    override suspend fun createPost(
        state: PostFormState,
        imageUrl: String?
    ): Result<String> {
        val user = auth.currentUser
        if (user == null || !accounts.isUserLoggedInAndVerified) {
            return Result.failure(IllegalStateException("Chỉ tài khoản trường đã xác minh (@ut.edu.vn) mới được tạo bài."))
        }

        val postId = state.clientPostId
        if (postId.isBlank()) {
            return Result.failure(IllegalArgumentException("Mã bài đăng không hợp lệ."))
        }

        val postDocRef = firestore.collection("posts").document(postId)

        // Kiểm tra nếu document đã tồn tại (trường hợp mất mạng sau khi đã gửi, tránh tạo bài trùng lặp)
        try {
            val existingDoc = postDocRef.get(Source.SERVER).awaitTask()
            if (existingDoc.exists()) {
                return Result.success(postId)
            }
        } catch (_: Exception) {
            // Nếu không thể kiểm tra trước đó do mạng, tiếp tục thực hiện set với document id ổn định
        }

        val locationString = buildLocationString(state)
        val occurredAtString = buildOccurredAtString(state)
        val categoryLabel = PostCategories.find(state.categoryId)?.label ?: ""

        val postData = hashMapOf<String, Any?>(
            "title" to state.title.trim(),
            "type" to state.type.name, // "LOST" hoặc "FOUND"
            "categoryId" to (state.categoryId ?: ""),
            "category" to categoryLabel,
            "description" to state.description.trim(),
            "areaId" to (state.areaId ?: ""),
            "location" to locationString,
            "locationNote" to state.locationNote.trim(),
            "occurredAt" to occurredAtString,
            "createdAt" to FieldValue.serverTimestamp(),
            "authorId" to user.uid,
            "authorName" to (user.displayName?.takeUnless { it.contains('@') }?.ifBlank { null }
                ?: user.email?.substringBefore("@") ?: "Sinh viên"),
            "status" to "OPEN",
            "imageUrl" to imageUrl,
            "imageUrls" to if (imageUrl != null) listOf(imageUrl) else emptyList<String>(),
            "contactPhone" to "",
            "pickupLocation" to ""
        )

        return try {
            postDocRef.set(postData).awaitTask()
            Result.success(postId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        fun buildLocationString(state: PostFormState): String {
            val base = when (state.areaChoice) {
                AreaChoice.KNOWN -> PostAreas.find(state.areaId)?.label ?: "Chưa rõ"
                AreaChoice.OTHER -> state.areaOtherText.trim()
                AreaChoice.UNKNOWN -> "Không rõ khu vực"
            }
            return if (state.locationNote.isNotBlank()) {
                "$base (${state.locationNote.trim()})"
            } else {
                base
            }
        }

        fun buildOccurredAtString(state: PostFormState): String {
            val sdf = SimpleDateFormat("HH:mm - dd/MM/yyyy", Locale.getDefault())
            return when (state.timeMode) {
                TimeMode.EXACT -> state.exactAtMillis?.let { sdf.format(Date(it)) }.orEmpty()
                TimeMode.RANGE -> {
                    val start = state.rangeStartMillis?.let { sdf.format(Date(it)) }.orEmpty()
                    val end = state.rangeEndMillis?.let { sdf.format(Date(it)) }.orEmpty()
                    if (start.isNotEmpty() && end.isNotEmpty()) "$start - $end" else ""
                }
                TimeMode.DESCRIBED -> state.timeNote.trim()
                TimeMode.UNKNOWN -> ""
            }
        }
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> cont.resume(result) }
    addOnFailureListener { error -> cont.resumeWithException(error) }
    addOnCanceledListener { cont.cancel() }
}
