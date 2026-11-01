package com.example.lostlink.ui.post

enum class AreaChoice { KNOWN, UNKNOWN, OTHER }

object PostAreas {
    data class Area(val id: String, val label: String)
    val all = listOf(
        Area("a", "Tòa A"),
        Area("b", "Tòa B"),
        Area("c", "Tòa C"),
        Area("d", "Tòa D"),
        Area("e", "Tòa E"),
        Area("canteen", "Căn tin"),
        Area("library", "Thư viện"),
        Area("parking", "Bãi giữ xe"),
        Area("Auditorium", "Hội Trường"),
    )
    fun find(id: String?) = all.find { it.id == id }
}


enum class TimeMode(val label: String) {
    EXACT("Biết chính xác"),
    RANGE("Khoảng thời gian"),
    DESCRIBED("Mô tả"),
    UNKNOWN("Không nhớ")
}
