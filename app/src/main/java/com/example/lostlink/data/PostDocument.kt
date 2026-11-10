package com.example.lostlink.data

import com.example.lostlink.ui.post.AreaChoice
import com.example.lostlink.ui.post.PostAreas
import com.example.lostlink.ui.post.PostCategories
import com.example.lostlink.ui.post.PostFormState
import com.example.lostlink.ui.post.TimeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Shared write contract consumed by HomePost and PostDetail. */
object PostDocument {
    fun build(draft: PostFormState, author: Map<String, Any>, imageUrl: String?, createdAt: Any): Map<String, Any?> {
        val area = when (draft.areaChoice) {
            AreaChoice.KNOWN -> PostAreas.find(draft.areaId)?.label.orEmpty()
            AreaChoice.OTHER -> draft.areaOtherText.trim()
            AreaChoice.UNKNOWN -> "Không rõ khu vực"
        }
        val location = if (draft.locationNote.isBlank()) area else "$area (${draft.locationNote.trim()})"
        val format = SimpleDateFormat("HH:mm - dd/MM/yyyy", Locale.forLanguageTag("vi-VN")).apply {
            timeZone = TimeZone.getTimeZone("GMT+07:00")
        }
        fun at(value: Long?) = value?.let { format.format(Date(it)) }.orEmpty()
        val occurredAt = when (draft.timeMode) {
            TimeMode.EXACT -> at(draft.exactAtMillis)
            TimeMode.RANGE -> "${at(draft.rangeStartMillis)} – ${at(draft.rangeEndMillis)}"
            TimeMode.DESCRIBED -> draft.timeNote.trim()
            TimeMode.UNKNOWN -> ""
        }
        return mapOf(
            "title" to draft.title.trim(), "type" to draft.type.name,
            "categoryId" to draft.categoryId, "category" to PostCategories.find(draft.categoryId)?.label,
            "description" to draft.description.trim(),
            "areaId" to if (draft.areaChoice == AreaChoice.KNOWN) draft.areaId else "",
            "location" to location, "locationNote" to draft.locationNote.trim(),
            "areaChoice" to draft.areaChoice.name, "areaOtherText" to draft.areaOtherText.trim(),
            "occurredAt" to occurredAt, "timeMode" to draft.timeMode.name,
            "exactAtMillis" to draft.exactAtMillis, "rangeStartMillis" to draft.rangeStartMillis,
            "rangeEndMillis" to draft.rangeEndMillis, "timeNote" to draft.timeNote.trim(),
            "createdAt" to createdAt, "status" to "OPEN",
            "imageUrl" to imageUrl, "imageUrls" to listOfNotNull(imageUrl),
        ) + author
    }
}
