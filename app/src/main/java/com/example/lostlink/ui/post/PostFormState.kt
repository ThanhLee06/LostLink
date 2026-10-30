package com.example.lostlink.ui.post

enum class PostType(val code: String, val label: String) {
    LOST("lost", "Mất đồ"),
    FOUND("found", "Nhặt được")
}

data class Category(val id: String, val label: String)

object PostCategories {
    val all = listOf(
        Category("wallet", "Ví"),
        Category("bag", "Túi, balo"),
        Category("keys", "Chìa khóa"),
        Category("phone", "Điện thoại"),
        Category("documents", "Giấy tờ, thẻ"),
        Category("other", "Khác")
    )

    fun find(id: String?) = all.firstOrNull { it.id == id }
}

data class FormErrors(
    val title: String? = null,
    val category: String? = null,
    val description: String? = null,
    val area: String? = null,
    val time: String? = null
) {
    val isEmpty get() = title == null && category == null && description == null && area == null && time == null
}

data class PostFormState(
    val type: PostType = PostType.LOST,
    val title: String = "",
    val categoryId: String? = null,
    val description: String = "",

    // Khu vực
    val areaChoice: AreaChoice = AreaChoice.KNOWN,
    val areaId: String? = null,
    val areaOtherText: String = "",
    val locationNote: String = "",

    // Thời gian
    val timeMode: TimeMode = TimeMode.UNKNOWN,
    val exactAtMillis: Long? = null,
    val rangeStartMillis: Long? = null,
    val rangeEndMillis: Long? = null,
    val timeNote: String = "",

    val errors: FormErrors = FormErrors()
) {
    val hasUnsavedChanges: Boolean
        get() = title.isNotBlank() || categoryId != null || description.isNotBlank()
                || areaId != null || areaOtherText.isNotBlank() || locationNote.isNotBlank()
                || timeMode != TimeMode.UNKNOWN
}

object PostFormValidator {
    const val TITLE_MAX = 80
    const val DESCRIPTION_MIN = 10
    const val DESCRIPTION_MAX = 500

    fun validate(s: PostFormState): FormErrors {
        val title = s.title.trim()
        val desc = s.description.trim()
        return FormErrors(
            title = when {
                title.isEmpty() -> "Nhập tên đồ, ví dụ: Ví màu nâu"
                title.length > TITLE_MAX -> "Tên đồ tối đa $TITLE_MAX ký tự"
                else -> null
            },
            category = if (s.categoryId == null) "Chọn danh mục cho món đồ" else null,
            description = when {
                desc.isEmpty() -> "Mô tả vài đặc điểm để dễ nhận ra món đồ"
                desc.length < DESCRIPTION_MIN -> "Mô tả hơi ngắn, thêm màu sắc hoặc kiểu dáng nhé"
                desc.length > DESCRIPTION_MAX -> "Mô tả tối đa $DESCRIPTION_MAX ký tự"
                else -> null
            },
            area = validateArea(s),
            time = validateTime(s)
        )
    }

    private fun validateArea(s: PostFormState): String? = when (s.areaChoice) {
        AreaChoice.KNOWN -> if (s.areaId == null) "Chọn khu vực, hoặc chọn Không rõ" else null
        AreaChoice.OTHER -> if (s.areaOtherText.isBlank()) "Nhập tên khu vực" else null
        AreaChoice.UNKNOWN -> null
    }

    private fun validateTime(s: PostFormState): String? {
        val now = System.currentTimeMillis()
        return when (s.timeMode) {
            TimeMode.EXACT -> {
                val at = s.exactAtMillis
                when {
                    at == null -> "Chọn ngày và giờ, hoặc chọn Không nhớ"
                    at > now -> "Thời điểm không thể ở tương lai"
                    else -> null
                }
            }
            TimeMode.RANGE -> {
                val st = s.rangeStartMillis
                val en = s.rangeEndMillis
                when {
                    st == null || en == null -> "Chọn đủ thời điểm bắt đầu và kết thúc"
                    st > en -> "Thời điểm bắt đầu không được sau kết thúc"
                    en > now -> "Thời điểm không thể ở tương lai"
                    else -> null
                }
            }
            TimeMode.DESCRIBED -> if (s.timeNote.isBlank()) "Nhập mô tả thời gian, ví dụ: sáng nay" else null
            TimeMode.UNKNOWN -> null
        }
    }

    private val longDigits = Regex("\\d{9,}")
    fun looksSensitive(text: String) = longDigits.containsMatchIn(text.replace(" ", ""))
}
