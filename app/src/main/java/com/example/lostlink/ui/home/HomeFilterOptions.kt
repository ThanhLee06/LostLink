package com.example.lostlink.ui.home

private val filterSeparators = Regex("[^a-z0-9]+")
private fun filterKey(value: String) = foldHomeSearch(value).replace(filterSeparators, " ").trim()

enum class HomeCategory(val id: String, val label: String, vararg aliases: String) {
    WALLET("wallet", "Ví tiền", "ví"),
    STUDENT_CARD("student_card", "Thẻ sinh viên"),
    ELECTRONICS("electronics", "Điện thoại/Laptop", "phone", "laptop", "điện thoại", "máy tính xách tay"),
    KEYS("keys", "Chìa khóa"),
    BAG("bag", "Balo/Túi xách", "túi, balo", "balo", "ba lô", "túi xách"),
    DOCUMENTS("documents", "Giấy tờ", "giấy tờ, thẻ"),
    OTHER("other", "Đồ dùng khác", "khác");

    private val keys = (listOf(id, label) + aliases).map(::filterKey).toSet()

    companion object {
        fun from(value: String): HomeCategory? {
            val key = filterKey(value)
            return entries.firstOrNull { key in it.keys }
        }
    }
}

enum class HomeArea(val id: String, val label: String, val campus: Boolean = false, vararg aliases: String) {
    CAMPUS_1("campus_1", "Cơ sở 1 (Võ Oanh)", true, "cơ sở 1", "cs1", "cs 1", "võ oanh"),
    CAMPUS_2("campus_2", "Cơ sở 2 (Quận 12)", true, "cơ sở 2", "cs2", "cs 2", "quận 12"),
    CAMPUS_3("campus_3", "Cơ sở 3 (Thủ Đức)", true, "cơ sở 3", "cs3", "cs 3", "thủ đức"),
    DORMITORY("dormitory", "Ký túc xá", true, "ktx"),
    A("a", "Tòa A", false, "khu a", "giảng đường a"),
    B("b", "Tòa B", false, "khu b", "giảng đường b"),
    C("c", "Tòa C", false, "khu c", "giảng đường c"),
    D("d", "Tòa D", false, "khu d", "giảng đường d"),
    E("e", "Tòa E", false, "khu e", "giảng đường e"),
    CANTEEN("canteen", "Căn tin", false, "căng tin"),
    LIBRARY("library", "Thư viện"),
    PARKING("parking", "Bãi giữ xe", false, "bãi xe"),
    AUDITORIUM("auditorium", "Hội trường", false, "Auditorium");

    private val keys = (listOf(id, label) + aliases).map(::filterKey).toSet()
    private val phrases = (listOf(label) + aliases.filter { filterKey(it).length > 1 }).map(::filterKey)

    fun matches(post: HomePost): Boolean {
        if (filterKey(post.areaId) in keys) return true
        val location = " ${filterKey(post.location)} "
        return phrases.any { " $it " in location }
    }
}
