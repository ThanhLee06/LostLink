package com.example.bt1.data

data class NotificationItem(
    val id: String,
    val title: String,
    val description: String,
    val time: String,
    val type: String = "reminder", // "upcoming", "overdue", "success", "reminder"
    val isRead: Boolean = false
)
