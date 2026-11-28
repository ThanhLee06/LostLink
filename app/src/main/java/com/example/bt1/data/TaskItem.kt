package com.example.bt1.data

import com.google.firebase.firestore.DocumentId
import com.google.gson.annotations.SerializedName

data class SubtaskItem(
    @SerializedName("title")
    val title: String = "",
    @SerializedName("done")
    val done: Boolean = false
)

data class TaskItem(
    @DocumentId
    @SerializedName("id")
    val id: String = "",

    @SerializedName("title")
    val title: String = "",

    @SerializedName("note")
    val note: String = "",

    @SerializedName("category")
    val category: String = "study",

    @SerializedName("date")
    val date: String = "2026-10-20",

    @SerializedName("time")
    val time: String = "09:00",

    @SerializedName("priority")
    val priority: String = "medium",

    @SerializedName("remind")
    val remind: Boolean = true,

    @SerializedName("done")
    val done: Boolean = false,

    @SerializedName("subtasks")
    val subtasks: List<SubtaskItem> = emptyList(),

    val userId: String = ""
) {
    // Computed property for UI compatibility with previous code
    val isCompleted: Boolean
        get() = done

    // Helper to format category for display on UI
    val displayCategory: String
        get() = when (category.lowercase()) {
            "study", "học tập" -> "Học tập"
            "life", "cuộc sống" -> "Cuộc sống"
            else -> category.replaceFirstChar { it.titlecase() }
        }
}
