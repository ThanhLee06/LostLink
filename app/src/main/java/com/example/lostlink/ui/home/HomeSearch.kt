package com.example.lostlink.ui.home

import java.text.Normalizer
import java.util.Locale

enum class HomePostFilter(val label: String) { ALL("Tất cả"), LOST("Mất đồ"), FOUND("Nhặt được") }

private val accentMarks = Regex("\\p{M}+")
internal fun foldHomeSearch(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(accentMarks, "").replace('đ', 'd').replace('Đ', 'D').lowercase(Locale.ROOT)

/** Purely local: preserves the Firestore order and never claims to search unloaded posts. */
fun filterLoadedHomePosts(posts: List<HomePost>, query: String, filter: HomePostFilter): List<HomePost> {
    val words = foldHomeSearch(query.trim()).split(Regex("\\s+")).filter(String::isNotEmpty)
    return posts.filter { post ->
        (filter == HomePostFilter.ALL || post.type == filter.name) &&
            (words.isEmpty() || foldHomeSearch("${post.title} ${post.location}").let { text -> words.all(text::contains) })
    }
}
