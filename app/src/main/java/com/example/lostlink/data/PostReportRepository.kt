package com.example.lostlink.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * Canonical report reasons agreed with Admin in Step 0 (Contract §3.8 & firestore.rules).
 */
enum class ReportReason(
    val code: String,
    val label: String,
    val subtitle: String,
) {
    INAPPROPRIATE(
        code = "INAPPROPRIATE",
        label = "Nội dung không phù hợp",
        subtitle = "Ngôn từ phản cảm, hình ảnh hoặc thông tin vi phạm quy định trường.",
    ),
    WRONG_INFO(
        code = "WRONG_INFO",
        label = "Thông tin sai sự thật",
        subtitle = "Thông tin đồ thất lạc/nhặt được không chính xác, gây hiểu nhầm.",
    ),
    SPAM(
        code = "SPAM",
        label = "Tin rác / Quảng cáo",
        subtitle = "Đăng trùng lặp nhiều lần, quảng cáo hoặc có dấu hiệu lừa đảo.",
    ),
    OTHER(
        code = "OTHER",
        label = "Lý do khác",
        subtitle = "Vấn đề khác cần quản trị viên xem xét (có thể mô tả thêm bên dưới).",
    );

    companion object {
        fun fromCode(code: String?): ReportReason? {
            val normalized = code?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.code == normalized }
        }
    }
}

enum class ReportStatus(val code: String, val label: String) {
    PENDING("PENDING", "Đang chờ duyệt"),
    RESOLVED_HIDDEN("RESOLVED_HIDDEN", "Đã xử lý (Đã ẩn bài viết)"),
    REJECTED("REJECTED", "Đã xử lý (Từ chối báo cáo)");

    val isProcessed: Boolean get() = this != PENDING

    companion object {
        fun fromCode(code: String?): ReportStatus {
            val normalized = code?.trim()?.uppercase() ?: return PENDING
            return entries.firstOrNull { it.code == normalized } ?: PENDING
        }
    }
}

data class UserReportInfo(
    val postId: String,
    val reporterUid: String,
    val reason: ReportReason? = null,
    val description: String? = null,
    val status: ReportStatus = ReportStatus.PENDING,
    val publicNote: String? = null,
) {
    val statusBadgeText: String get() = status.label
}

data class ReportValidationResult(
    val reasonError: String? = null,
    val descriptionError: String? = null,
) {
    val isValid: Boolean get() = reasonError == null && descriptionError == null
    val firstErrorMessage: String? get() = reasonError ?: descriptionError
}

object PostReportValidator {
    const val MAX_DESCRIPTION_LENGTH = 500

    fun validate(reason: ReportReason?, rawDescription: String): ReportValidationResult {
        val reasonErr = if (reason == null) {
            "Vui lòng chọn lý do báo cáo."
        } else {
            null
        }
        val descLength = rawDescription.length
        val descErr = if (descLength > MAX_DESCRIPTION_LENGTH) {
            "Mô tả ngắn không được vượt quá $MAX_DESCRIPTION_LENGTH ký tự (hiện tại: $descLength/$MAX_DESCRIPTION_LENGTH)."
        } else {
            null
        }
        return ReportValidationResult(
            reasonError = reasonErr,
            descriptionError = descErr,
        )
    }

    fun normalizeDescription(rawDescription: String): String? {
        return rawDescription.trim().takeIf { it.isNotEmpty() }
    }

    fun buildReportId(postId: String, reporterUid: String): String {
        val cleanPostId = postId.trim()
        val cleanUid = reporterUid.trim()
        require(cleanPostId.isNotEmpty()) { "Mã bài đăng không hợp lệ." }
        require(cleanUid.isNotEmpty()) { "Mã người báo cáo không hợp lệ." }
        return "${cleanPostId}_${cleanUid}"
    }

    fun buildCreatePayload(
        postId: String,
        reporterUid: String,
        reason: ReportReason,
        description: String?,
        createdAtMarker: Any,
    ): Map<String, Any?> {
        val cleanPostId = postId.trim()
        val cleanUid = reporterUid.trim()
        val normalizedDesc = description?.trim()?.takeIf { it.isNotEmpty() }
        require(cleanPostId.isNotEmpty()) { "Mã bài đăng không được để trống." }
        require(cleanUid.isNotEmpty()) { "Mã người dùng không được để trống." }
        require(normalizedDesc == null || normalizedDesc.length <= MAX_DESCRIPTION_LENGTH) {
            "Mô tả báo cáo không được vượt quá $MAX_DESCRIPTION_LENGTH ký tự."
        }
        return mapOf(
            "postId" to cleanPostId,
            "reporterUid" to cleanUid,
            "reason" to reason.code,
            "description" to normalizedDesc,
            "status" to ReportStatus.PENDING.code,
            "createdAt" to createdAtMarker,
        )
    }
}

class ReportInProgressException :
    IllegalStateException("Đang gửi báo cáo cho bài viết này, vui lòng đợi.")

class AlreadyReportedException(
    message: String = "Bạn đã báo cáo bài viết này trước đó. Quản trị viên đang xem xét.",
) : IllegalStateException(message)

class SelfReportNotAllowedException(
    message: String = "Bạn không thể báo cáo bài đăng của chính mình.",
) : IllegalStateException(message)

enum class UnavailablePostReason {
    DELETED,
    HIDDEN,
}

class PostUnavailableForReportException(
    val reason: UnavailablePostReason,
    message: String = when (reason) {
        UnavailablePostReason.DELETED ->
            "Bài đăng này vừa bị xóa nên không thể gửi báo cáo."
        UnavailablePostReason.HIDDEN ->
            "Bài đăng này vừa bị ẩn bởi quản trị viên nên không thể gửi báo cáo."
    },
) : IllegalStateException(message)

class ReportValidationException(message: String) : IllegalArgumentException(message)

interface PostReportBackend {
    suspend fun hasUserReportedPost(uid: String, postId: String): Result<Boolean>
    suspend fun getUserReport(uid: String, postId: String): Result<UserReportInfo?> {
        return hasUserReportedPost(uid, postId).map { exists ->
            if (exists) {
                UserReportInfo(
                    postId = postId,
                    reporterUid = uid,
                    status = ReportStatus.PENDING,
                )
            } else {
                null
            }
        }
    }
    suspend fun checkPostReportable(uid: String, postId: String): Result<Unit>
    suspend fun createReport(
        uid: String,
        postId: String,
        reason: ReportReason,
        description: String?,
    ): Result<Unit>
}

class FirestorePostReportBackend(
    firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
    authProvider: () -> FirebaseAuth = { FirebaseAuth.getInstance() },
) : PostReportBackend {
    private val firestore by lazy(firestoreProvider)
    private val auth by lazy(authProvider)

    override suspend fun hasUserReportedPost(uid: String, postId: String): Result<Boolean> {
        return getUserReport(uid, postId).map { it != null }
    }

    override suspend fun getUserReport(uid: String, postId: String): Result<UserReportInfo?> {
        val reportId = PostReportValidator.buildReportId(postId, uid)
        return try {
            val snap = withTimeout(TIMEOUT_MS) {
                firestore.collection("reports").document(reportId).get(Source.SERVER).await()
            }
            if (!snap.exists()) {
                return Result.success(null)
            }
            val info = UserReportInfo(
                postId = snap.getString("postId")?.trim().takeUnless { it.isNullOrEmpty() } ?: postId.trim(),
                reporterUid = snap.getString("reporterUid")?.trim().takeUnless { it.isNullOrEmpty() } ?: uid.trim(),
                reason = ReportReason.fromCode(snap.getString("reason")),
                description = snap.getString("description")?.trim()?.takeIf { it.isNotEmpty() },
                status = ReportStatus.fromCode(snap.getString("status")),
                publicNote = snap.getString("publicNote")?.trim()?.takeIf { it.isNotEmpty() },
            )
            Result.success(info)
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Result.failure(IllegalStateException("Kiểm tra trạng thái báo cáo quá thời gian chờ. Vui lòng thử lại.", e))
            } else {
                throw e
            }
        } catch (e: Exception) {
            val code = (e as? FirebaseFirestoreException)?.code
            // If rules on a remote environment deny GET when resource == null, PERMISSION_DENIED on own reportId
            // while authenticated means the report document does not exist yet.
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED && isCurrentUserVerified(uid)) {
                return Result.success(null)
            }
            logFirestoreError("getUserReport", "reports/$reportId", uid, postId, e)
            Result.failure(
                IllegalStateException(
                    FirestoreSavedPostsBackend.mapFirestoreError(e, "Không thể kiểm tra lịch sử báo cáo."),
                    e,
                )
            )
        }
    }

    override suspend fun checkPostReportable(uid: String, postId: String): Result<Unit> {
        return try {
            val doc = withTimeout(TIMEOUT_MS) {
                firestore.collection("posts").document(postId).get(Source.SERVER).await()
            }
            if (!doc.exists()) {
                return Result.failure(PostUnavailableForReportException(UnavailablePostReason.DELETED))
            }
            val authorId = doc.getString("authorId")?.trim().orEmpty()
            if (authorId.isNotEmpty() && authorId == uid) {
                return Result.failure(SelfReportNotAllowedException())
            }
            val moderationStatus = doc.getString("moderationStatus")?.trim().orEmpty()
            if (moderationStatus.equals("HIDDEN", ignoreCase = true)) {
                return Result.failure(PostUnavailableForReportException(UnavailablePostReason.HIDDEN))
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Result.failure(IllegalStateException("Kiểm tra bài đăng quá thời gian chờ. Vui lòng thử lại.", e))
            } else {
                throw e
            }
        } catch (e: Exception) {
            val code = (e as? FirebaseFirestoreException)?.code
            if (code == FirebaseFirestoreException.Code.NOT_FOUND) {
                return Result.failure(PostUnavailableForReportException(UnavailablePostReason.DELETED))
            }
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED && isCurrentUserVerified(uid)) {
                // In firestore.rules, reading another user's post is denied only when moderationStatus != 'VISIBLE'
                return Result.failure(PostUnavailableForReportException(UnavailablePostReason.HIDDEN))
            }
            logFirestoreError("checkPostReportable", "posts/$postId", uid, postId, e)
            Result.failure(
                IllegalStateException(
                    FirestoreSavedPostsBackend.mapFirestoreError(e, "Không thể kiểm tra trạng thái bài đăng."),
                    e,
                )
            )
        }
    }

    override suspend fun createReport(
        uid: String,
        postId: String,
        reason: ReportReason,
        description: String?,
    ): Result<Unit> {
        val reportId = PostReportValidator.buildReportId(postId, uid)
        val payload = PostReportValidator.buildCreatePayload(
            postId = postId,
            reporterUid = uid,
            reason = reason,
            description = description,
            createdAtMarker = FieldValue.serverTimestamp(),
        )
        return try {
            withTimeout(TIMEOUT_MS) {
                firestore.collection("reports").document(reportId).set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Result.failure(IllegalStateException("Gửi báo cáo quá thời gian chờ. Vui lòng thử lại.", e))
            } else {
                throw e
            }
        } catch (e: Exception) {
            val code = (e as? FirebaseFirestoreException)?.code
            if (code == FirebaseFirestoreException.Code.ALREADY_EXISTS) {
                return Result.failure(AlreadyReportedException())
            }
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED && isCurrentUserVerified(uid)) {
                // Disambiguate why Firestore rules rejected the create/set:
                // 1) Report document already exists (set() hit update: if false)
                val alreadyReported = hasUserReportedPost(uid, postId).getOrDefault(false)
                if (alreadyReported) {
                    return Result.failure(AlreadyReportedException())
                }
                // 2) Post was deleted or hidden right as the report was being submitted
                val postCheck = checkPostReportable(uid, postId)
                if (postCheck.isFailure) {
                    return Result.failure(postCheck.exceptionOrNull()!!)
                }
            }
            logFirestoreError("createReport", "reports/$reportId", uid, postId, e)
            Result.failure(
                IllegalStateException(
                    FirestoreSavedPostsBackend.mapFirestoreError(e, "Không thể gửi báo cáo. Vui lòng thử lại."),
                    e,
                )
            )
        }
    }

    private fun isCurrentUserVerified(uid: String): Boolean {
        val user = auth.currentUser ?: return false
        return user.uid == uid &&
            user.isEmailVerified &&
            FirebaseAccountRepository.isAllowedEmail(user.email.orEmpty())
    }

    private fun logFirestoreError(
        operation: String,
        path: String,
        uid: String?,
        postId: String?,
        error: Throwable,
    ) {
        runCatching {
            val code = (error as? FirebaseFirestoreException)?.code?.name ?: error::class.java.simpleName
            Log.e(
                TAG,
                "Firestore error in $operation: code=$code, path=$path, uid=$uid, postId=$postId, message=${error.message}",
                error,
            )
        }
    }

    companion object {
        private const val TAG = "PostReportBackend"
        private const val TIMEOUT_MS = 15_000L
    }
}

class PostReportRepository(
    private val backend: PostReportBackend = FirestorePostReportBackend(),
    private val activeUidProvider: () -> String? = {
        val accounts = FirebaseAccountRepository()
        FirebaseAuth.getInstance().currentUser?.uid?.takeIf { accounts.isUserLoggedInAndVerified }
    },
) {
    private val lock = Any()
    private var boundUid: String? = null

    private val _reportedPostIds = MutableStateFlow<Set<String>>(emptySet())
    val reportedPostIds: StateFlow<Set<String>> = _reportedPostIds.asStateFlow()

    private val _userReports = MutableStateFlow<Map<String, UserReportInfo>>(emptyMap())
    val userReports: StateFlow<Map<String, UserReportInfo>> = _userReports.asStateFlow()

    private val _pendingPostIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingPostIds: StateFlow<Set<String>> = _pendingPostIds.asStateFlow()

    fun bindUser(uid: String?) {
        val normalized = uid?.takeIf { it.isNotBlank() }
        synchronized(lock) {
            if (normalized == boundUid) return
            boundUid = normalized
            _reportedPostIds.value = emptySet()
            _userReports.value = emptyMap()
            _pendingPostIds.value = emptySet()
        }
    }

    suspend fun fetchUserReport(postId: String): Result<UserReportInfo?> {
        val cleanId = postId.trim()
        if (cleanId.isBlank()) {
            return Result.failure(IllegalArgumentException("Mã bài đăng không hợp lệ."))
        }
        val uid = activeUidProvider()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(SecurityException("Vui lòng đăng nhập bằng tài khoản UTH đã xác minh."))

        bindUser(uid)
        return backend.getUserReport(uid, cleanId).onSuccess { reportInfo ->
            if (reportInfo != null && isStillBound(uid)) {
                _reportedPostIds.update { it + cleanId }
                _userReports.update { it + (cleanId to reportInfo) }
            }
        }
    }

    suspend fun checkAlreadyReported(postId: String): Result<Boolean> {
        return fetchUserReport(postId).map { it != null }
    }

    suspend fun submitReport(
        postId: String,
        authorIdHint: String = "",
        reason: ReportReason?,
        rawDescription: String = "",
    ): Result<Unit> {
        val cleanId = postId.trim()
        if (cleanId.isBlank()) {
            return Result.failure(IllegalArgumentException("Mã bài đăng không hợp lệ."))
        }
        val uid = activeUidProvider()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(SecurityException("Vui lòng đăng nhập bằng tài khoản UTH đã xác minh."))

        bindUser(uid)

        if (authorIdHint.isNotBlank() && authorIdHint.trim() == uid) {
            return Result.failure(SelfReportNotAllowedException())
        }

        val validation = PostReportValidator.validate(reason, rawDescription)
        if (!validation.isValid) {
            return Result.failure(
                ReportValidationException(validation.firstErrorMessage ?: "Thông tin báo cáo chưa hợp lệ.")
            )
        }
        val validReason = requireNotNull(reason)
        val normalizedDescription = PostReportValidator.normalizeDescription(rawDescription)

        if (cleanId in _reportedPostIds.value) {
            return Result.failure(AlreadyReportedException())
        }

        if (!tryAcquirePending(cleanId)) {
            return Result.failure(ReportInProgressException())
        }

        return try {
            // 1. Check if the user already reported this post before (including processed status)
            val existingReport = backend.getUserReport(uid, cleanId).getOrElse { err ->
                return Result.failure(err)
            }
            if (existingReport != null) {
                if (isStillBound(uid)) {
                    _reportedPostIds.update { it + cleanId }
                    _userReports.update { it + (cleanId to existingReport) }
                }
                return Result.failure(AlreadyReportedException())
            }

            // 2. Verify that the post still exists, is VISIBLE, and is not owned by the reporter
            backend.checkPostReportable(uid, cleanId).getOrElse { err ->
                return Result.failure(err)
            }

            // 3. Submit the report document reports/{postId}_{uid}
            backend.createReport(
                uid = uid,
                postId = cleanId,
                reason = validReason,
                description = normalizedDescription,
            ).fold(
                onSuccess = {
                    if (isStillBound(uid)) {
                        val createdInfo = UserReportInfo(
                            postId = cleanId,
                            reporterUid = uid,
                            reason = validReason,
                            description = normalizedDescription,
                            status = ReportStatus.PENDING,
                        )
                        _reportedPostIds.update { it + cleanId }
                        _userReports.update { it + (cleanId to createdInfo) }
                    }
                    Result.success(Unit)
                },
                onFailure = { err ->
                    if (err is AlreadyReportedException && isStillBound(uid)) {
                        _reportedPostIds.update { it + cleanId }
                    }
                    Result.failure(err)
                },
            )
        } finally {
            releasePending(cleanId)
        }
    }

    private fun isStillBound(uid: String): Boolean = synchronized(lock) { boundUid == uid }

    private fun tryAcquirePending(postId: String): Boolean = synchronized(lock) {
        if (postId in _pendingPostIds.value) return false
        _pendingPostIds.value = _pendingPostIds.value + postId
        true
    }

    private fun releasePending(postId: String) {
        synchronized(lock) {
            _pendingPostIds.value = _pendingPostIds.value - postId
        }
    }

    companion object {
        @Volatile
        private var instance: PostReportRepository? = null

        fun getInstance(): PostReportRepository {
            return instance ?: synchronized(this) {
                instance ?: PostReportRepository().also { instance = it }
            }
        }
    }
}
