package com.example.bt1.ui.components

fun getUserInitials(name: String): String {
    if (name.isBlank()) return "MA"
    val parts = name.trim().split("\\s+".toRegex())
    return if (parts.size >= 2) {
        "${parts[0].take(1)}${parts.last().take(1)}".uppercase()
    } else {
        name.take(2).uppercase()
    }
}
