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
    val description: String? = null
) {
    val isEmpty get() = title == null && category == null && description == null
}

data class PostFormState(
    val type: PostType = PostType.LOST,
    val title: String = "",
    val categoryId: String? = null,
    val description: String = "",
    val errors: FormErrors = FormErrors()
) {
    // Chỉ đổi loại tin thì chưa tính là có dữ liệu cần giữ
    val hasUnsavedChanges: Boolean
        get() = title.isNotBlank() || categoryId != null || description.isNotBlank()
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
            }
        )
    }

    private val longDigits = Regex("\\d{9,}")
    fun looksSensitive(text: String) = longDigits.containsMatchIn(text.replace(" ", ""))
}