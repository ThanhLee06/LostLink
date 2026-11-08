package com.example.lostlink.ui.post

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lostlink.data.PostCreationDataSource
import com.example.lostlink.data.PostRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class PostFormViewModel(
    private val repositoryProvider: () -> PostCreationDataSource = { PostRepository() }
) : ViewModel() {

    private val repository by lazy { repositoryProvider() }
    private val _state = MutableStateFlow(PostFormState())
    val state: StateFlow<PostFormState> = _state.asStateFlow()

    private var ownerUid: String? = null

    fun bindUser(uid: String?) {
        if (uid != ownerUid) {
            ownerUid = uid
            clearDraft()
        }
    }

    fun setInitialType(isLost: Boolean) {
        if (!_state.value.hasUnsavedChanges) {
            _state.update { it.copy(type = if (isLost) PostType.LOST else PostType.FOUND) }
        }
    }

    fun onTypeChange(type: PostType) = _state.update { it.copy(type = type) }

    fun onTitleChange(v: String) = _state.update {
        it.copy(title = v, errors = it.errors.copy(title = null))
    }

    fun onCategoryChange(id: String) = _state.update {
        it.copy(categoryId = id, errors = it.errors.copy(category = null))
    }

    fun onDescriptionChange(v: String) = _state.update {
        it.copy(description = v, errors = it.errors.copy(description = null))
    }

    // ───── Khu vực ─────
    fun onAreaChoiceChange(c: AreaChoice) = _state.update {
        it.copy(
            areaChoice = c,
            areaId = if (c == AreaChoice.KNOWN) it.areaId else null,
            areaOtherText = if (c == AreaChoice.OTHER) it.areaOtherText else "",
            errors = it.errors.copy(area = null)
        )
    }

    fun onAreaChange(id: String) = _state.update {
        it.copy(areaId = id, errors = it.errors.copy(area = null))
    }

    fun onAreaOtherChange(v: String) = _state.update {
        it.copy(areaOtherText = v, errors = it.errors.copy(area = null))
    }

    fun onLocationNoteChange(v: String) = _state.update { it.copy(locationNote = v) }

    // ───── Thời gian ─────
    fun onTimeModeChange(m: TimeMode) = _state.update {
        it.copy(
            timeMode = m,
            exactAtMillis = if (m == TimeMode.EXACT) it.exactAtMillis else null,
            rangeStartMillis = if (m == TimeMode.RANGE) it.rangeStartMillis else null,
            rangeEndMillis = if (m == TimeMode.RANGE) it.rangeEndMillis else null,
            timeNote = if (m == TimeMode.DESCRIBED) it.timeNote else "",
            errors = it.errors.copy(time = null)
        )
    }

    fun onExactTimeChange(ms: Long?) = _state.update {
        it.copy(exactAtMillis = ms, errors = it.errors.copy(time = null))
    }

    fun onRangeChange(start: Long?, end: Long?) = _state.update {
        it.copy(
            rangeStartMillis = start,
            rangeEndMillis = end,
            errors = it.errors.copy(time = null)
        )
    }

    fun onTimeNoteChange(v: String) = _state.update {
        it.copy(timeNote = v, errors = it.errors.copy(time = null))
    }

    // ───── Hình ảnh ─────
    fun onImageSelected(uri: Uri?) = _state.update {
        if (uri == null) it // Người dùng hủy chọn ảnh -> giữ nguyên state hiện tại
        else it.copy(imageUri = uri, uploadedImageUrl = null, errors = it.errors.copy(image = null))
    }

    fun onRemoveImage() = _state.update {
        it.copy(imageUri = null, uploadedImageUrl = null, errors = it.errors.copy(image = null))
    }

    fun onImageError(errorMsg: String) = _state.update {
        it.copy(errors = it.errors.copy(image = errorMsg))
    }

    fun clearImageError() = _state.update {
        it.copy(errors = it.errors.copy(image = null))
    }

    fun clearSubmitError() = _state.update {
        it.copy(submitError = null)
    }

    fun validate(): Boolean {
        val errors = PostFormValidator.validate(_state.value)
        _state.update { it.copy(errors = errors) }
        return errors.isEmpty
    }

    fun clearDraft() {
        _state.value = PostFormState(clientPostId = UUID.randomUUID().toString())
    }

   
    fun submitPost(
        context: Context,
        onSuccess: (postId: String) -> Unit
    ) {
        val current = _state.value
        if (current.isSubmitting) return

        if (!validate()) {
            val firstError = listOfNotNull(
                _state.value.errors.title,
                _state.value.errors.category,
                _state.value.errors.description,
                _state.value.errors.area,
                _state.value.errors.time,
                _state.value.errors.image
            ).firstOrNull() ?: "Vui lòng điền đầy đủ các thông tin bắt buộc"
            try {
                android.widget.Toast.makeText(context, firstError, android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
            return
        }

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isSubmitting = true,
                    submitError = null,
                    submitProgress = if (it.imageUri != null && it.uploadedImageUrl == null) {
                        "Đang tải ảnh lên..."
                    } else {
                        "Đang lưu bài đăng..."
                    }
                )
            }

            val stateSnapshot = _state.value

            // 1. Tải ảnh nếu có
            val uploadResult = repository.uploadImageIfNeeded(
                context = context,
                imageUri = stateSnapshot.imageUri,
                cachedUrl = stateSnapshot.uploadedImageUrl
            )

            if (uploadResult.isFailure) {
                val errorMsg = uploadResult.exceptionOrNull()?.localizedMessage ?: "Tải ảnh thất bại."
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        submitError = errorMsg,
                        submitProgress = null
                    )
                }
                return@launch
            }

            val uploadedUrl = uploadResult.getOrNull()
            _state.update {
                it.copy(
                    uploadedImageUrl = uploadedUrl,
                    submitProgress = "Đang lưu bài viết vào hệ thống..."
                )
            }

            // 2. Lưu bài viết vào Firestore với clientPostId ổn định
            val createResult = repository.createPost(
                state = _state.value,
                imageUrl = uploadedUrl
            )

            if (createResult.isSuccess) {
                val createdPostId = createResult.getOrThrow()
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        submitProgress = null,
                        submitError = null
                    )
                }
                clearDraft()
                onSuccess(createdPostId)
            } else {
                val errorMsg = createResult.exceptionOrNull()?.localizedMessage ?: "Lưu bài viết thất bại."
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        submitError = errorMsg,
                        submitProgress = null
                    )
                }
            }
        }
    }

    /**
     * Dùng để test nhanh dữ liệu mẫu
     */
    fun loadSampleData() {
        _state.value = PostFormState(
            type = PostType.LOST,
            title = "Ví da nam màu đen",
            categoryId = "wallet",
            description = "Ví da màu đen có CMND và thẻ sinh viên để quên",
            areaChoice = AreaChoice.KNOWN,
            areaId = "library",
            locationNote = "Bàn học tầng 2 gần giá sách",
            timeMode = TimeMode.EXACT,
            exactAtMillis = System.currentTimeMillis() - 3600000,
            errors = FormErrors()
        )
    }
}
