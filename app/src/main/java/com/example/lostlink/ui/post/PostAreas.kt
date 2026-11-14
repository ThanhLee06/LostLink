package com.example.lostlink.ui.post

enum class AreaChoice { KNOWN, UNKNOWN, OTHER }

object PostAreas {
    data class Area(val id: String, val label: String)

    val all = listOf(
        Area("campus_1", "Cơ sở 1 (Võ Oanh)"),
        Area("campus_2", "Cơ sở 2 (Quận 12)"),
        Area("campus_3", "Cơ sở 3 (Thủ Đức)"),
        Area("dormitory", "Ký túc xá"),
        Area("a", "Tòa A"),
        Area("b", "Tòa B"),
        Area("c", "Tòa C"),
        Area("d", "Tòa D"),
        Area("e", "Tòa E"),
        Area("canteen", "Căn tin"),
        Area("library", "Thư viện"),
        Area("parking", "Bãi giữ xe"),
        Area("auditorium", "Hội trường"),
    )

    /** Map of approved legacy/alias IDs to canonical IDs for reads and prefill. */
    val legacyAliases = mapOf("Auditorium" to "auditorium")

    fun find(id: String?) = all.find { it.id == id }

    fun normalizeId(id: String?): String? {
        val cleaned = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        find(cleaned)?.let { return it.id }
        return legacyAliases[cleaned]
    }

    fun findByIdOrAlias(id: String?) = find(normalizeId(id))
}

enum class TimeMode(val label: String) {
    EXACT("Biết chính xác"),
    RANGE("Khoảng thời gian"),
    DESCRIBED("Mô tả"),
    UNKNOWN("Không nhớ")
}
