package com.example.lostlink.ui.myposts

import com.example.lostlink.ui.home.HomePost

enum class MyPostsTab(val label: String) {
    OPEN("Đang mở / Đang tìm"),
    RESOLVED("Đã giải quyết / Đã đóng")
}

data class MyPostsState(
    val loading: Boolean = true,
    val error: String? = null,
    val posts: List<HomePost> = emptyList(),
    val selectedTab: MyPostsTab = MyPostsTab.OPEN,
    val actionLoading: Boolean = false,
    val userMessage: String? = null
) {
    val openPosts: List<HomePost>
        get() = posts.filter {
            val s = it.status.uppercase()
            s != "RESOLVED" && s != "CLOSED"
        }

    val resolvedPosts: List<HomePost>
        get() = posts.filter {
            val s = it.status.uppercase()
            s == "RESOLVED" || s == "CLOSED"
        }

    val currentList: List<HomePost>
        get() = when (selectedTab) {
            MyPostsTab.OPEN -> openPosts
            MyPostsTab.RESOLVED -> resolvedPosts
        }
}

