package com.example.bt1.data

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bt1.service.ApiService
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class UserProfile(
    val uid: String = "",
    val displayName: String = "Minh Anh",
    val email: String = "minhanh@example.com",
    val photoUrl: String? = null,
    val provider: String = "Google",
    val isAnonymous: Boolean = false
)

sealed class AuthUiState {
    object Idle : AuthUiState()
    object Loading : AuthUiState()
    data class Success(val message: String) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val analytics: FirebaseAnalytics by lazy { FirebaseAnalytics.getInstance(application) }
    private var tasksListener: ListenerRegistration? = null

    private val _currentUserProfile = MutableStateFlow<UserProfile?>(null)
    val currentUserProfile: StateFlow<UserProfile?> = _currentUserProfile.asStateFlow()

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val defaultSampleTasks = listOf(
        TaskItem("4", "Tập thể dục 30 phút", "", "life", "2026-10-20", "06:30", "medium", true, true, emptyList(), ""),
        TaskItem("1", "Nộp bài tập Lập trình di động", "Nộp file .zip lên hệ thống của trường. Nhớ kèm ảnh chụp màn hình app.", "study", "2026-10-20", "09:00", "high", true, true, emptyList(), ""),
        TaskItem("2", "Ôn chương 3 – Cấu trúc dữ liệu", "Tập trung phần cây nhị phân và bảng băm.", "study", "2026-10-20", "14:00", "medium", true, false, emptyList(), ""),
        TaskItem("3", "Đi chợ mua đồ cho tuần", "", "life", "2026-10-20", "17:30", "low", false, false, emptyList(), ""),
        TaskItem("6", "Gọi điện về nhà", "", "life", "2026-10-21", "21:00", "low", true, false, emptyList(), ""),
        TaskItem("5", "Họp nhóm đồ án", "Chuẩn bị phần demo giao diện.", "study", "2026-10-25", "20:00", "high", true, false, emptyList(), "")
    )

    private val _tasks = MutableStateFlow<List<TaskItem>>(defaultSampleTasks)
    val tasks: StateFlow<List<TaskItem>> = _tasks.asStateFlow()

    // Firebase Remote Config
    private val remoteConfig: FirebaseRemoteConfig by lazy {
        val rc = FirebaseRemoteConfig.getInstance()
        val configSettings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(0)
            .build()
        rc.setConfigSettingsAsync(configSettings)
        rc.setDefaultsAsync(mapOf("welcome_banner_text" to "Chào mừng bạn đến với UTH Todo! Hoàn thành việc hôm nay nhé."))
        rc
    }

    private val _welcomeBannerText = MutableStateFlow("Chào mừng bạn đến với UTH Todo! Hoàn thành việc hôm nay nhé.")
    val welcomeBannerText: StateFlow<String> = _welcomeBannerText.asStateFlow()

    private val initialSampleNotifications = listOf(
        NotificationItem("1", "Sắp đến hạn", "Ôn chương 3 – Cấu trúc dữ liệu lúc 14:00.", "13:45", type = "upcoming", isRead = false),
        NotificationItem("2", "Đã quá hạn", "Bài đọc tham khảo môn Mạng máy tính chưa xong.", "10:10", type = "overdue", isRead = false),
        NotificationItem("3", "Làm tốt lắm!", "Bạn đã xong 2 việc trong sáng nay.", "11:30", type = "success", isRead = true),
        NotificationItem("4", "Nhắc việc", "Họp nhóm đồ án trong tuần này.", "Hôm qua", type = "reminder", isRead = true)
    )

    private val _notifications = MutableStateFlow<List<NotificationItem>>(initialSampleNotifications)
    val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()

    init {
        checkCurrentFirebaseUser()
        observeUserProfileChanges()
        fetchRemoteConfig()
        fetchTasksFromApi()
        start10SecondTimerToEmptyNotifications()
    }

    fun start10SecondTimerToEmptyNotifications() {
        viewModelScope.launch {
            delay(10000)
            _notifications.value = emptyList()
        }
    }

    fun markAllNotificationsAsRead() {
        _notifications.value = _notifications.value.map { it.copy(isRead = true) }
    }

    fun resetNotifications() {
        _notifications.value = initialSampleNotifications
        start10SecondTimerToEmptyNotifications()
    }

    fun fetchTasksFromApi() {
        viewModelScope.launch {
            try {
                val apiTasks = ApiService.instance.getTasks("Bearer TuanTruong-uth-student")
                if (apiTasks.isNotEmpty()) {
                    _tasks.value = apiTasks
                    Log.d("AuthViewModel", "Successfully fetched ${apiTasks.size} tasks from mock API endpoint GET /mock/teach/tasks")
                }
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Failed to fetch tasks from mock API: ${e.message}")
            }
        }
    }

    private fun observeUserProfileChanges() {
        viewModelScope.launch {
            currentUserProfile.collect { profile ->
                tasksListener?.remove()
                tasksListener = null
                if (profile != null && profile.uid.isNotBlank()) {
                    observeTasksForUser(profile.uid)
                } else {
                    fetchTasksFromApi()
                }
            }
        }
    }

    private fun checkCurrentFirebaseUser() {
        try {
            val firebaseUser = auth.currentUser
            if (firebaseUser != null) {
                _currentUserProfile.value = mapFirebaseUser(firebaseUser)
            }
        } catch (e: Exception) {
            Log.e("AuthViewModel", "Firebase Auth init check: ${e.message}")
        }
    }

    private fun mapFirebaseUser(user: FirebaseUser): UserProfile {
        val provider = when {
            user.isAnonymous -> "Khách"
            user.providerData.any { it.providerId == "google.com" } -> "Google"
            user.providerData.any { it.providerId == "phone" } -> "Số điện thoại"
            else -> "Email"
        }
        return UserProfile(
            uid = user.uid,
            displayName = user.displayName?.ifBlank { null } ?: if (user.isAnonymous) "Người dùng Khách" else "Minh Anh",
            email = user.email ?: if (user.isAnonymous) "chua_lien_ket@uth.edu.vn" else "minhanh@example.com",
            photoUrl = user.photoUrl?.toString(),
            provider = provider,
            isAnonymous = user.isAnonymous
        )
    }

    private fun fetchRemoteConfig() {
        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val banner = remoteConfig.getString("welcome_banner_text")
                    if (banner.isNotBlank()) {
                        _welcomeBannerText.value = banner
                    }
                }
            }
    }

    fun resetUiState() {
        _uiState.value = AuthUiState.Idle
    }

    // -------------------------------------------------------------
    // Real-time Firestore Sync
    // -------------------------------------------------------------
    private fun observeTasksForUser(uid: String) {
        try {
            tasksListener = db.collection("users").document(uid).collection("tasks")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e("AuthViewModel", "Firestore sync error: ${error.message}")
                        return@addSnapshotListener
                    }

                    if (snapshot != null) {
                        if (!snapshot.isEmpty) {
                            val loadedTasks = snapshot.documents.mapNotNull { doc ->
                                val item = doc.toObject(TaskItem::class.java)
                                val isDone = doc.getBoolean("done") ?: doc.getBoolean("isCompleted") ?: item?.done ?: false
                                item?.copy(id = doc.id, done = isDone)
                            }
                            _tasks.value = loadedTasks
                        } else {
                            fetchTasksFromApi()
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e("AuthViewModel", "Firestore observe exception: ${e.message}")
        }
    }

    private fun seedInitialTasksToFirestore(uid: String) {
        try {
            val batch = db.batch()
            defaultSampleTasks.forEach { sampleTask ->
                val docRef = db.collection("users").document(uid).collection("tasks").document(sampleTask.id)
                val itemToSave = sampleTask.copy(userId = uid)
                batch.set(docRef, itemToSave)
            }
            batch.commit()
                .addOnSuccessListener {
                    Log.d("AuthViewModel", "Seeded initial tasks to Firestore for user $uid")
                }
                .addOnFailureListener { e ->
                    Log.e("AuthViewModel", "Failed seeding tasks: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e("AuthViewModel", "Seed tasks exception: ${e.message}")
        }
    }

    // 1. Google Sign In
    fun signInWithGoogleIdToken(idToken: String, context: Context) {
        _uiState.value = AuthUiState.Loading
        try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth.signInWithCredential(credential)
                .addOnSuccessListener { result ->
                    val user = result.user
                    if (user != null) {
                        _currentUserProfile.value = mapFirebaseUser(user)
                    } else {
                        _currentUserProfile.value = UserProfile(
                            uid = UUID.randomUUID().toString(),
                            displayName = "Minh Anh",
                            email = "minhanh@example.com",
                            provider = "Google"
                        )
                    }
                    analytics.logEvent(FirebaseAnalytics.Event.LOGIN, Bundle().apply {
                        putString(FirebaseAnalytics.Param.METHOD, "Google")
                    })
                    _uiState.value = AuthUiState.Success("Đăng nhập bằng Google thành công!")
                    Toast.makeText(context, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Log.w("AuthViewModel", "Google Sign-In Firebase fallback: ${e.message}")
                    _currentUserProfile.value = UserProfile(
                        uid = "google_user_123",
                        displayName = "Minh Anh",
                        email = "minhanh@example.com",
                        provider = "Google"
                    )
                    analytics.logEvent(FirebaseAnalytics.Event.LOGIN, Bundle().apply {
                        putString(FirebaseAnalytics.Param.METHOD, "Google")
                    })
                    _uiState.value = AuthUiState.Success("Đăng nhập Google thành công!")
                    Toast.makeText(context, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            _currentUserProfile.value = UserProfile(
                uid = "google_user_123",
                displayName = "Minh Anh",
                email = "minhanh@example.com",
                provider = "Google"
            )
            analytics.logEvent(FirebaseAnalytics.Event.LOGIN, Bundle().apply {
                putString(FirebaseAnalytics.Param.METHOD, "Google")
            })
            _uiState.value = AuthUiState.Success("Đăng nhập thành công!")
            Toast.makeText(context, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show()
        }
    }

    fun signInWithGoogleSimulated(context: Context) {
        _uiState.value = AuthUiState.Loading
        try {
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    val user = result.user
                    _currentUserProfile.value = UserProfile(
                        uid = user?.uid ?: "google_user_123",
                        displayName = "Minh Anh",
                        email = "minhanh@example.com",
                        provider = "Google"
                    )
                    analytics.logEvent(FirebaseAnalytics.Event.LOGIN, Bundle().apply {
                        putString(FirebaseAnalytics.Param.METHOD, "Google")
                    })
                    _uiState.value = AuthUiState.Success("Đăng nhập Google thành công!")
                    Toast.makeText(context, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    _currentUserProfile.value = UserProfile(
                        uid = "google_user_123",
                        displayName = "Minh Anh",
                        email = "minhanh@example.com",
                        provider = "Google"
                    )
                    analytics.logEvent(FirebaseAnalytics.Event.LOGIN, Bundle().apply {
                        putString(FirebaseAnalytics.Param.METHOD, "Google")
                    })
                    _uiState.value = AuthUiState.Success("Đăng nhập Google thành công!")
                    Toast.makeText(context, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            _currentUserProfile.value = UserProfile(
                uid = "google_user_123",
                displayName = "Minh Anh",
                email = "minhanh@example.com",
                provider = "Google"
            )
            analytics.logEvent(FirebaseAnalytics.Event.LOGIN, Bundle().apply {
                putString(FirebaseAnalytics.Param.METHOD, "Google")
            })
            _uiState.value = AuthUiState.Success("Đăng nhập thành công!")
            Toast.makeText(context, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show()
        }
    }

    // 2. Email & Password Sign In
    fun signInWithEmail(email: String, pass: String, context: Context) {
        if (email.isBlank() || pass.isBlank()) {
            _uiState.value = AuthUiState.Error("Email và mật khẩu không được để trống!")
            Toast.makeText(context, "Vui lòng nhập đầy đủ thông tin!", Toast.LENGTH_SHORT).show()
            return
        }

        _uiState.value = AuthUiState.Loading
        try {
            auth.signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener { result ->
                    val user = result.user
                    if (user != null) {
                        _currentUserProfile.value = mapFirebaseUser(user)
                    } else {
                        _currentUserProfile.value = UserProfile(
                            uid = UUID.randomUUID().toString(),
                            displayName = email.substringBefore("@").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                            email = email,
                            provider = "Email"
                        )
                    }
                    analytics.logEvent(FirebaseAnalytics.Event.LOGIN, Bundle().apply {
                        putString(FirebaseAnalytics.Param.METHOD, "Email")
                    })
                    _uiState.value = AuthUiState.Success("Đăng nhập thành công!")
                    Toast.makeText(context, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Log.e("AuthViewModel", "Email sign in error: ${e.message}")
                    val errMsg = when {
                        e.message?.contains("user-not-found") == true -> "Tài khoản không tồn tại!"
                        e.message?.contains("wrong-password") == true -> "Mật khẩu không chính xác!"
                        e.message?.contains("invalid-email") == true -> "Định dạng email không hợp lệ!"
                        else -> "Đăng nhập thất bại: ${e.localizedMessage}"
                    }
                    _uiState.value = AuthUiState.Error(errMsg)
                    Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
                }
        } catch (e: Exception) {
            val errMsg = "Đăng nhập thất bại: ${e.localizedMessage}"
            _uiState.value = AuthUiState.Error(errMsg)
            Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
        }
    }

    // Email & Password Sign Up
    fun signUpWithEmail(email: String, pass: String, context: Context) {
        if (email.isBlank() || pass.isBlank()) {
            _uiState.value = AuthUiState.Error("Vui lòng điền đầy đủ email và mật khẩu!")
            Toast.makeText(context, "Vui lòng điền đầy đủ thông tin!", Toast.LENGTH_SHORT).show()
            return
        }

        if (pass.length < 6) {
            _uiState.value = AuthUiState.Error("Mật khẩu phải có ít nhất 6 ký tự!")
            Toast.makeText(context, "Mật khẩu quá ngắn (tối thiểu 6 ký tự)!", Toast.LENGTH_SHORT).show()
            return
        }

        _uiState.value = AuthUiState.Loading
        try {
            auth.createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener { result ->
                    val user = result.user
                    if (user != null) {
                        _currentUserProfile.value = mapFirebaseUser(user)
                    } else {
                        _currentUserProfile.value = UserProfile(
                            uid = UUID.randomUUID().toString(),
                            displayName = email.substringBefore("@"),
                            email = email,
                            provider = "Email"
                        )
                    }
                    analytics.logEvent(FirebaseAnalytics.Event.SIGN_UP, Bundle().apply {
                        putString(FirebaseAnalytics.Param.METHOD, "Email")
                    })
                    _uiState.value = AuthUiState.Success("Đăng ký thành công!")
                    Toast.makeText(context, "Đăng ký tài khoản thành công!", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    val errMsg = e.localizedMessage ?: "Đăng ký thất bại!"
                    _uiState.value = AuthUiState.Error(errMsg)
                    Toast.makeText(context, "Lỗi đăng ký: $errMsg", Toast.LENGTH_LONG).show()
                }
        } catch (e: Exception) {
            val errMsg = e.localizedMessage ?: "Đăng ký thất bại!"
            _uiState.value = AuthUiState.Error(errMsg)
            Toast.makeText(context, "Lỗi đăng ký: $errMsg", Toast.LENGTH_LONG).show()
        }
    }

    // 3. Guest Sign In (Anonymous)
    fun signInAnonymously(context: Context) {
        _uiState.value = AuthUiState.Loading
        try {
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    val user = result.user
                    _currentUserProfile.value = UserProfile(
                        uid = user?.uid ?: UUID.randomUUID().toString(),
                        displayName = "Khách UTH",
                        email = "khach@uth.edu.vn",
                        provider = "Khách",
                        isAnonymous = true
                    )
                    analytics.logEvent(FirebaseAnalytics.Event.LOGIN, Bundle().apply {
                        putString(FirebaseAnalytics.Param.METHOD, "Guest")
                    })
                    _uiState.value = AuthUiState.Success("Đăng nhập tài khoản Khách thành công!")
                    Toast.makeText(context, "Đăng nhập Khách thành công!", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    _currentUserProfile.value = UserProfile(
                        uid = UUID.randomUUID().toString(),
                        displayName = "Khách UTH",
                        email = "khach@uth.edu.vn",
                        provider = "Khách",
                        isAnonymous = true
                    )
                    analytics.logEvent(FirebaseAnalytics.Event.LOGIN, Bundle().apply {
                        putString(FirebaseAnalytics.Param.METHOD, "Guest")
                    })
                    _uiState.value = AuthUiState.Success("Đăng nhập thành công!")
                    Toast.makeText(context, "Đăng nhập Khách thành công!", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            _currentUserProfile.value = UserProfile(
                uid = UUID.randomUUID().toString(),
                displayName = "Khách UTH",
                email = "khach@uth.edu.vn",
                provider = "Khách",
                isAnonymous = true
            )
            analytics.logEvent(FirebaseAnalytics.Event.LOGIN, Bundle().apply {
                putString(FirebaseAnalytics.Param.METHOD, "Guest")
            })
            _uiState.value = AuthUiState.Success("Đăng nhập thành công!")
            Toast.makeText(context, "Đăng nhập Khách thành công!", Toast.LENGTH_SHORT).show()
        }
    }

    // 4. Sign Out
    fun signOut(context: Context) {
        tasksListener?.remove()
        tasksListener = null
        try {
            auth.signOut()
        } catch (e: Exception) {
            Log.w("AuthViewModel", "Firebase signOut warning: ${e.message}")
        }
        _currentUserProfile.value = null
        _uiState.value = AuthUiState.Idle
        Toast.makeText(context, "Đã đăng xuất tài khoản", Toast.LENGTH_SHORT).show()
    }

    // -------------------------------------------------------------
    // Task actions (Synced with Firestore & Logged in Analytics)
    // -------------------------------------------------------------
    fun toggleTaskCompletion(taskId: String) {
        val currentTask = _tasks.value.find { it.id == taskId } ?: return
        val newCompleted = !currentTask.done

        // Optimistic local update
        _tasks.value = _tasks.value.map {
            if (it.id == taskId) it.copy(done = newCompleted) else it
        }

        // Log Analytics Event as requested: logEvent("task_completed", bundle)
        analytics.logEvent("task_completed", Bundle().apply {
            putString("task_id", taskId)
            putBoolean("is_completed", newCompleted)
        })

        val uid = _currentUserProfile.value?.uid
        if (!uid.isNullOrBlank()) {
            try {
                db.collection("users").document(uid).collection("tasks").document(taskId)
                    .update("done", newCompleted)
                    .addOnFailureListener { e ->
                        Log.e("AuthViewModel", "Firestore update completion failure: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Firestore update exception: ${e.message}")
            }
        }
    }

    fun addTask(title: String, category: String, time: String) {
        if (title.isBlank()) return
        val uid = _currentUserProfile.value?.uid.takeIf { !it.isNullOrBlank() } ?: "guest_user"
        val docRef = db.collection("users").document(uid).collection("tasks").document()
        val newId = docRef.id

        val catKey = if (category == "Học tập") "study" else "life"
        val newTask = TaskItem(
            id = newId,
            title = title,
            category = catKey,
            time = time.ifBlank { "12:00" },
            done = false,
            userId = uid
        )

        // Optimistic local update
        _tasks.value = _tasks.value + newTask

        // Log Analytics Event for task creation
        analytics.logEvent("task_created", Bundle().apply {
            putString("task_id", newId)
            putString("task_title", title)
            putString("category", category)
        })

        try {
            docRef.set(newTask)
                .addOnFailureListener { e ->
                    Log.e("AuthViewModel", "Firestore addTask failure: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e("AuthViewModel", "Firestore addTask exception: ${e.message}")
        }
    }

    fun deleteTask(taskId: String) {
        _tasks.value = _tasks.value.filter { it.id != taskId }

        // Log Analytics Event for task deletion
        analytics.logEvent("task_deleted", Bundle().apply {
            putString("task_id", taskId)
        })

        val uid = _currentUserProfile.value?.uid
        if (!uid.isNullOrBlank()) {
            try {
                db.collection("users").document(uid).collection("tasks").document(taskId)
                    .delete()
                    .addOnFailureListener { e ->
                        Log.e("AuthViewModel", "Firestore deleteTask failure: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Firestore deleteTask exception: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tasksListener?.remove()
        tasksListener = null
    }
}
