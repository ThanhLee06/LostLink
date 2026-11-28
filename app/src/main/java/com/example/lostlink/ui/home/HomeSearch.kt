package com.example.lostlink.ui.home

import java.text.Normalizer
import java.util.Locale

enum class HomePostFilter(val label: String) { ALL("Tất cả"), LOST("Cần tìm (Mất đồ)"), FOUND("Nhặt được đồ") }

private val accentMarks = Regex("\\p{M}+")
private val repeatedChars = Regex("([a-z])\\1+")
private val telexSuffix = Regex("[sfrxjw1-9]+$")
private val vowels = setOf('a', 'e', 'i', 'o', 'u', 'y')

private data class ConceptGroup(val suggestions: List<String>, val terms: Set<String>)

private val conceptGroups = listOf(
    ConceptGroup(listOf("Ví tiền", "Ví da", "Bóp tiền"), setOf("vi", "bop", "tui tien", "vi tien", "bop tien", "vi da", "bop da", "wallet")),
    ConceptGroup(listOf("Thẻ sinh viên", "MSSV"), setOf("the sinh vien", "the sv", "mssv", "student card")),
    ConceptGroup(listOf("Giấy tờ", "CCCD", "Bằng lái"), setOf("giay to", "cccd", "cmnd", "can cuoc", "bang lai", "cavet", "ho chieu")),
    ConceptGroup(listOf("Điện thoại", "iPhone", "Samsung"), setOf("dien thoai", "dt", "dtdd", "smartphone", "iphone", "samsung", "oppo", "xiaomi")),
    ConceptGroup(listOf("Laptop", "Máy tính", "Tai nghe"), setOf("laptop", "may tinh", "may tinh xach tay", "macbook", "notebook", "ipad", "tai nghe", "airpods", "sac", "pin du phong", "chuot")),
    ConceptGroup(listOf("Chìa khóa", "Chìa khóa xe"), setOf("chia khoa", "khoa xe", "smartkey", "moc khoa", "chia phong")),
    ConceptGroup(listOf("Balo", "Túi xách", "Cặp sách"), setOf("balo", "ba lo", "tui xach", "cap sach", "tui deo", "vali")),
    ConceptGroup(listOf("Thư viện"), setOf("thu vien", "library", "phong doc")),
    ConceptGroup(listOf("Căn tin"), setOf("can tin", "canteen", "nha an")),
    ConceptGroup(listOf("Bãi giữ xe"), setOf("bai giu xe", "bai xe", "nha xe", "ham xe")),
    ConceptGroup(listOf("Ký túc xá"), setOf("ky tuc xa", "ktx", "dorm")),
    ConceptGroup(listOf("Hội trường"), setOf("hoi truong", "auditorium")),
)

private val knownVocab: Set<String> = conceptGroups.flatMap { it.terms }.flatMap { it.split(" ") }.toSet() + setOf(
    "vi", "bop", "tui", "tien", "da", "the", "sinh", "vien", "giay", "to", "dien", "thoai",
    "may", "tinh", "chia", "khoa", "xe", "balo", "cap", "sach", "thu", "can", "tin", "bai",
    "giu", "nha", "hoi", "truong", "mat", "nhat", "tim", "roi", "quen", "den", "nau", "xanh", "trang",
)

internal fun foldHomeSearch(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(accentMarks, "").replace('đ', 'd').replace('Đ', 'D').lowercase(Locale.ROOT)

private fun tryVocabOrTelex(word: String): String? {
    if (word in knownVocab) return word
    if (word.length >= 3 && word.last() in "sfrxjw123456789") {
        val base = word.replace(telexSuffix, "")
        if (base.length >= 2 && base.any { it in vowels } && base in knownVocab) return base
    }
    return null
}

internal fun canonicalizeHomeToken(raw: String): String {
    val w = foldHomeSearch(raw).trim()
    if (w.isEmpty() || w in knownVocab) return w
    val collapsed = w.replace(repeatedChars, "$1")
    tryVocabOrTelex(collapsed)?.let { return it }
    for (i in 0 until collapsed.length - 1) {
        val swapped = collapsed.substring(0, i) + collapsed[i + 1] + collapsed[i] + collapsed.substring(i + 2)
        tryVocabOrTelex(swapped)?.let { return it }
    }
    return collapsed
}

internal fun expandHomeQueryGroups(query: String): List<Set<String>> {
    val words = foldHomeSearch(query.trim()).split(Regex("\\s+")).filter(String::isNotEmpty)
    if (words.isEmpty()) return emptyList()
    val canons = words.map(::canonicalizeHomeToken)
    val result = mutableListOf<Set<String>>()
    var i = 0
    while (i < words.size) {
        var matchedPhrase = false
        for (span in listOf(3, 2)) {
            if (i + span <= words.size) {
                val phrase = canons.subList(i, i + span).joinToString(" ")
                val group = conceptGroups.firstOrNull { phrase in it.terms }
                if (group != null) {
                    result.add(group.terms + phrase + words.subList(i, i + span).joinToString(" "))
                    i += span
                    matchedPhrase = true
                    break
                }
            }
        }
        if (matchedPhrase) continue
        val raw = words[i]
        val canon = canons[i]
        val eq = mutableSetOf(raw, canon)
        conceptGroups.filter { canon in it.terms || raw in it.terms }.forEach { eq.addAll(it.terms) }
        result.add(eq)
        i++
    }
    return result
}

private val nonWordRegex = Regex("[^a-z0-9]+")

private fun hasWholePhrase(haystack: String, needle: String): Boolean {
    val cleanHay = haystack.replace(nonWordRegex, " ").trim()
    val cleanNeedle = needle.replace(nonWordRegex, " ").trim()
    return cleanNeedle.isNotEmpty() && " $cleanHay ".contains(" $cleanNeedle ")
}

/** Gợi ý từ khóa tức thì khi người dùng gõ từ đồng nghĩa, lỗi Telex hoặc lặp chữ (bóp, túi tiền, vis, vsi, viiii -> Ví tiền). */
fun suggestHomeSearchChips(query: String, posts: List<HomePost> = emptyList()): List<String> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    val foldedQuery = foldHomeSearch(trimmed)
    val words = foldedQuery.split(Regex("\\s+")).filter(String::isNotEmpty)
    val canons = words.map(::canonicalizeHomeToken)
    val joinedCanon = canons.joinToString(" ")
    val suggestions = linkedSetOf<String>()

    for (group in conceptGroups) {
        val hit = group.terms.any { term ->
            if (" " in term) hasWholePhrase(joinedCanon, term) else canons.any { it == term || (it.length >= 2 && term.startsWith(it)) }
        }
        if (hit) {
            group.suggestions.forEach { s ->
                if (!foldHomeSearch(s).equals(foldedQuery, ignoreCase = true)) suggestions.add(s)
            }
        }
    }
    val groups = expandHomeQueryGroups(trimmed)
    for (post in posts) {
        if (suggestions.size >= 5) break
        val hay = foldHomeSearch("${post.title} ${post.location} ${post.categoryId}")
        if (groups.isNotEmpty() && groups.all { eq -> eq.any { hasWholePhrase(hay, it) } }) {
            if (!foldHomeSearch(post.title).equals(foldedQuery, ignoreCase = true)) {
                suggestions.add(post.title)
            }
        }
    }
    return suggestions.take(5)
}

/** Purely local: preserves the Firestore order and never claims to search unloaded posts. */
fun filterLoadedHomePosts(
    posts: List<HomePost>,
    query: String,
    filter: HomePostFilter,
    categories: List<String> = emptyList(),
    areas: List<String> = emptyList(),
    timeFilter: HomeTimeFilter = HomeTimeFilter.ALL,
    now: Long = System.currentTimeMillis(),
): List<HomePost> {
    val rawWords = foldHomeSearch(query.trim()).split(Regex("\\s+")).filter(String::isNotEmpty)
    val groups = expandHomeQueryGroups(query)
    val since = timeFilter.startAt(now)
    return posts.filter { post ->
        !post.status.equals("CLOSED", ignoreCase = true) &&
            (filter == HomePostFilter.ALL || post.type == filter.name) &&
            (since == null || post.createdAt in since..now) &&
            (categories.isEmpty() || HomeCategory.from(post.categoryId.ifBlank { post.category })?.id in categories) &&
            (areas.isEmpty() || HomeArea.entries.any { it.id in areas && it.matches(post) }) &&
            (groups.isEmpty() || foldHomeSearch("${post.title} ${post.location} ${post.description} ${post.categoryId} ${post.areaId}").let { text ->
                rawWords.all(text::contains) || groups.all { eq -> eq.any { hasWholePhrase(text, it) } }
            })
    }
}
