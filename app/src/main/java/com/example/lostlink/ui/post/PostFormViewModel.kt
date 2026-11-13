package com.example.lostlink.ui.post

import android.net.Uri
import com.example.lostlink.data.UploadedMedia
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.lostlink.data.PostCreationDataSource
import com.example.lostlink.data.PostRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PostFormViewModel(private val repository: PostCreationDataSource) : ViewModel() {

    private val _state = MutableStateFlow(PostFormState())
    val state: StateFlow<PostFormState> = _state.asStateFlow()

    private var ownerUid: String? = null
    private var session = 0L
    private var submission: Job? = null
    private var uploadedImageUrl: UploadedMedia? = null

    fun bindUser(uid: String?) {
        if (uid != ownerUid) {
            session++
            submission?.cancel()
            submission = null
            uploadedImageUrl = null
            ownerUid = uid
            _state.value = PostFormState()
        }
    }

    fun openForUser(uid: String, isLost: Boolean) {
        bindUser(uid)
        if (!_state.value.hasUnsavedChanges) {
            edit { it.copy(type = if (isLost) PostType.LOST else PostType.FOUND) }
        }
    }

    private fun edit(change: (PostFormState) -> PostFormState) {
        _state.update {
            if (it.isSubmitting || it.awaitingConfirmation || it.createdPostId != null) it
            else change(it).copy(submitError = null)
        }
    }

    fun onTypeChange(type: PostType) = edit { it.copy(type = type) }

    fun onTitleChange(v: String) = edit {
        it.copy(title = v, errors = it.errors.copy(title = null))
    }

    fun onCategoryChange(id: String) = edit {
        it.copy(categoryId = id, errors = it.errors.copy(category = null))
    }

    fun onDescriptionChange(v: String) = edit {
        it.copy(description = v, errors = it.errors.copy(description = null))
    }

    // ───── Khu vực ─────
    fun onAreaChoiceChange(c: AreaChoice) = edit {
        it.copy(
            areaChoice = c,
            areaId = if (c == AreaChoice.KNOWN) it.areaId else null,
            areaOtherText = if (c == AreaChoice.OTHER) it.areaOtherText else "",
            errors = it.errors.copy(area = null)
        )
    }

    fun onAreaChange(id: String) = edit {
        it.copy(areaId = id, errors = it.errors.copy(area = null))
    }

    fun onAreaOtherChange(v: String) = edit {
        it.copy(areaOtherText = v, errors = it.errors.copy(area = null))
    }

    fun onLocationNoteChange(v: String) = edit { it.copy(locationNote = v) }

    // ───── Thời gian ─────
    fun onTimeModeChange(m: TimeMode) = edit {
        it.copy(
            timeMode = m,
            exactAtMillis = if (m == TimeMode.EXACT) it.exactAtMillis else null,
            rangeStartMillis = if (m == TimeMode.RANGE) it.rangeStartMillis else null,
            rangeEndMillis = if (m == TimeMode.RANGE) it.rangeEndMillis else null,
            timeNote = if (m == TimeMode.DESCRIBED) it.timeNote else "",
            errors = it.errors.copy(time = null)
        )
    }

    fun onExactTimeChange(ms: Long?) = edit {
        it.copy(exactAtMillis = ms, errors = it.errors.copy(time = null))
    }

    fun onRangeChange(start: Long?, end: Long?) = edit {
        it.copy(
            rangeStartMillis = start,
            rangeEndMillis = end,
            errors = it.errors.copy(time = null)
        )
    }

    fun onTimeNoteChange(v: String) = edit {
        it.copy(timeNote = v, errors = it.errors.copy(time = null))
    }

    // ───── Hình ảnh ─────
    fun onImageSelected(uri: Uri?) = edit {
        if (uri == null) it
        else {
            uploadedImageUrl = null
            it.copy(imageUri = uri, errors = it.errors.copy(image = null))
        }
    }

    fun onRemoveImage() = edit {
        uploadedImageUrl = null
        it.copy(imageUri = null, errors = it.errors.copy(image = null))
    }

    fun onImageError(errorMsg: String) = edit {
        it.copy(errors = it.errors.copy(image = errorMsg))
    }

    fun clearImageError() = edit {
        it.copy(errors = it.errors.copy(image = null))
    }

    fun validate(): Boolean {
        val errors = PostFormValidator.validate(_state.value)
        _state.update { it.copy(errors = errors) }
        return errors.isEmpty
    }

    fun clearDraft() {
        if (_state.value.isSubmitting || _state.value.awaitingConfirmation) return
        uploadedImageUrl = null
        _state.value = PostFormState()
    }

    fun consumeCreatedPost(id: String): Boolean {
        if (_state.value.createdPostId != id) return false
        uploadedImageUrl = null
        _state.value = PostFormState()
        return true
    }

    fun submitPost() {
        val current = _state.value
        if (current.isSubmitting || current.createdPostId != null) return
        val uid = ownerUid
        if (uid == null || !repository.isActiveAccount(uid)) {
            _state.update { it.copy(submitError = "Phiên đăng nhập không còn hợp lệ. Vui lòng đăng nhập lại.") }
            return
        }
        if (!validate()) return
        val snapshot = _state.value
        val version = session
        // Set synchronously so two consecutive clicks cannot launch two submissions.
        _state.update { it.copy(isSubmitting = true, submitError = null, submitProgress = "Đang kiểm tra bài đăng…") }
        submission = viewModelScope.launch {
            var writeAttempted = snapshot.awaitingConfirmation
            fun isCurrent(): Boolean = version == session && ownerUid == uid
            fun confirmed() {
                if (isCurrent() && repository.isActiveAccount(uid)) {
                    uploadedImageUrl = null
                    _state.value = PostFormState(createdPostId = snapshot.clientPostId)
                }
            }
            try {
                if (repository.postExists(uid, snapshot.clientPostId)) {
                    confirmed()
                    return@launch
                }
                if (!isCurrent()) return@launch
                if (snapshot.imageUri != null && uploadedImageUrl == null) {
                    _state.update { it.copy(submitProgress = "Đang tải ảnh lên…") }
                    val url = repository.uploadImage(uid, snapshot.clientPostId, snapshot.imageUri)
                    if (!isCurrent()) return@launch
                    uploadedImageUrl = url
                }
                if (!isCurrent()) return@launch
                _state.update { it.copy(submitProgress = "Đang lưu bài đăng…") }
                writeAttempted = true
                repository.createPost(uid, snapshot.clientPostId, snapshot, uploadedImageUrl.takeIf { snapshot.imageUri != null })
                confirmed()
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (isCurrent()) {
                    _state.update {
                        it.copy(
                            awaitingConfirmation = writeAttempted,
                            submitError = if (writeAttempted)
                                "Chưa xác nhận được bài đã lưu. Bấm Thử lại để kiểm tra và tiếp tục, tránh đăng trùng."
                            else "Chưa thể đăng tin. Kiểm tra kết nối hoặc ảnh đã chọn rồi thử lại.",
                        )
                    }
                }
            } finally {
                if (isCurrent()) _state.update { it.copy(isSubmitting = false, submitProgress = null) }
            }
        }
    }

    /**
     * Dùng để test nhanh dữ liệu mẫu
     */
    fun loadSampleData() {
        edit {
            uploadedImageUrl = null
            PostFormState(
                type = PostType.LOST,
                title = "Ví da nam màu đen",
                categoryId = "wallet",
                description = "Ví da màu đen có CMND và thẻ sinh viên để quên",
                areaChoice = AreaChoice.KNOWN,
                areaId = "library",
                locationNote = "Bàn học tầng 2 gần giá sách",
                timeMode = TimeMode.EXACT,
                exactAtMillis = System.currentTimeMillis() - 3600000,
                errors = FormErrors(),
            )
        }
    }

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { PostFormViewModel(PostRepository(context.applicationContext)) }
        }
    }
}
