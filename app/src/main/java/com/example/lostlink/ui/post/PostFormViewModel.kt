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

    fun bindUser(uid: String?) {
        if (uid != ownerUid) {
            ownerUid = uid
            _state.value = PostFormState()
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

    fun validate(): Boolean {
        val errors = PostFormValidator.validate(_state.value)
        _state.update { it.copy(errors = errors) }
        return errors.isEmpty
    }

    fun clearDraft() {
        _state.value = PostFormState()
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
