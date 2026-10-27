package com.example.lostlink.ui.post


import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class PostFormViewModel : ViewModel() {

    private val _state = MutableStateFlow(PostFormState())
    val state: StateFlow<PostFormState> = _state.asStateFlow()

    private var ownerUid: String? = null

    /** Gọi khi màn hình mở và mỗi khi uid đăng nhập thay đổi. */
    fun bindUser(uid: String?) {
        if (uid != ownerUid) {
            ownerUid = uid
            _state.value = PostFormState()
        }
    }

    // Đổi loại tin chỉ đổi type, giữ nguyên các trường còn lại
    fun onTypeChange(type: PostType) = _state.update { it.copy(type = type) }

    // Sửa trường nào thì xóa lỗi của trường đó
    fun onTitleChange(v: String) = _state.update {
        it.copy(title = v, errors = it.errors.copy(title = null))
    }

    fun onCategoryChange(id: String) = _state.update {
        it.copy(categoryId = id, errors = it.errors.copy(category = null))
    }

    fun onDescriptionChange(v: String) = _state.update {
        it.copy(description = v, errors = it.errors.copy(description = null))
    }

    /** Trả về true nếu hợp lệ. Nếu sai, lỗi được gắn vào đúng từng ô. */
    fun validate(): Boolean {
        val errors = PostFormValidator.validate(_state.value)
        _state.update { it.copy(errors = errors) }
        return errors.isEmpty
    }

    /** Gọi sau khi đăng thành công hoặc khi người dùng chọn bỏ nháp. */
    fun clearDraft() {
        _state.value = PostFormState()
    }
}