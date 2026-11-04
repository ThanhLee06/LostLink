package com.example.lostlink.ui.profile

import com.example.lostlink.ui.home.HomePost
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UserProfile(
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val mssv: String = "",
    val joinTimestamp: Long = 0L,
    val isEmailVerified: Boolean = false,
    val allowEmailContact: Boolean = true,
) {
    val joinDateFormatted: String get() {
        if (joinTimestamp <= 0L) return "Chưa cập nhật"
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.forLanguageTag("vi-VN"))
        return sdf.format(Date(joinTimestamp))
    }

    val initials: String get() {
        val name = displayName.trim().ifEmpty { email.substringBefore('@') }
        val parts = name.split(" ").filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "U"
            parts.size == 1 -> parts[0].take(2).uppercase(Locale.ROOT)
            else -> "${parts.first().first()}${parts.last().first()}".uppercase(Locale.ROOT)
        }
    }

    companion object {
        fun extractMssv(email: String): String {
            val prefix = email.trim().substringBefore('@')
            val digitsMatch = Regex("""\d{8,12}""").find(prefix)?.value
            return digitsMatch ?: prefix.ifEmpty { "N/A" }
        }

        fun validateDisplayName(name: String): String? {
            val clean = name.trim().replace(Regex("""[\r\n\t]"""), " ")
            return when {
                clean.isEmpty() -> "Tên hiển thị không được để trống."
                clean.length < 2 -> "Tên hiển thị phải từ 2 đến 50 ký tự."
                clean.length > 50 -> "Tên hiển thị quá dài (tối đa 50 ký tự)."
                else -> null
            }
        }
    }
}

enum class ProfilePostTab {
    OPEN, COMPLETED
}

data class ProfileState(
    val loading: Boolean = true,
    val error: String? = null,
    val profile: UserProfile = UserProfile(),
    val userPosts: List<HomePost> = emptyList(),
    val selectedTab: ProfilePostTab = ProfilePostTab.OPEN,
    val notificationsEnabled: Boolean = true,
    val isSavingName: Boolean = false,
) {
    val openPostsCount get() = userPosts.count { !it.isResolved }
    val completedPostsCount get() = userPosts.count { it.isResolved }
    val totalPostsCount get() = userPosts.size

    val filteredPosts: List<HomePost> get() = when (selectedTab) {
        ProfilePostTab.OPEN -> userPosts.filter { !it.isResolved }
        ProfilePostTab.COMPLETED -> userPosts.filter { it.isResolved }
    }
}
