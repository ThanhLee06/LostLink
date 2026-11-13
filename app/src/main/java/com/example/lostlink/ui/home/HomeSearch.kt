package com.example.lostlink.ui.home

import java.text.Normalizer
import java.util.Locale

enum class HomePostFilter(val label: String) { ALL("Tất cả"), LOST("Cần tìm (Mất đồ)"), FOUND("Nhặt được đồ") }

private val accentMarks = Regex("\\p{M}+")
internal fun foldHomeSearch(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(accentMarks, "").replace('đ', 'd').replace('Đ', 'D').lowercase(Locale.ROOT)

/** Purely local: preserves the Firestore order and never claims to search unloaded posts. */
fun filterLoadedHomePosts(posts: List<HomePost>, query: String, filter: HomePostFilter,
    categories: List<String> = emptyList(), areas: List<String> = emptyList(),
    timeFilter: HomeTimeFilter = HomeTimeFilter.ALL, now: Long = System.currentTimeMillis()): List<HomePost> {
    val words = foldHomeSearch(query.trim()).split(Regex("\\s+")).filter(String::isNotEmpty)
    val since = timeFilter.startAt(now)
    return posts.filter { post ->
        !post.status.equals("CLOSED", ignoreCase = true) &&
            (filter == HomePostFilter.ALL || post.type == filter.name) &&
            (since == null || post.createdAt in since..now) &&
            (categories.isEmpty() || HomeCategory.from(post.categoryId.ifBlank { post.category })?.id in categories) &&
            (areas.isEmpty() || HomeArea.entries.any { it.id in areas && it.matches(post) }) &&
            (words.isEmpty() || foldHomeSearch("${post.title} ${post.location}").let { text -> words.all(text::contains) })
    }
}
