package com.example.lostlink.ui.post

import com.example.lostlink.ui.home.HomePost

internal fun editPostDraft(post: HomePost, title: String, categoryId: String,
    location: String, description: String): PostFormState {
    val sameLocation = location.trim() == post.location.trim()
    val areaId = PostAreas.normalizeId(post.areaId)
    val areaChoice = if (!sameLocation) AreaChoice.OTHER else
        runCatching { AreaChoice.valueOf(post.areaChoice) }.getOrNull()
            ?: if (areaId != null) AreaChoice.KNOWN else AreaChoice.OTHER
    val timeMode = runCatching { TimeMode.valueOf(post.timeMode) }.getOrNull()
        ?: if (post.occurredAt.isBlank()) TimeMode.UNKNOWN else TimeMode.DESCRIBED
    return PostFormState(
        clientPostId = post.id,
        type = if (post.isLost) PostType.LOST else PostType.FOUND,
        title = title,
        categoryId = PostCategories.normalizeId(categoryId),
        description = description,
        areaChoice = areaChoice,
        areaId = if (areaChoice == AreaChoice.KNOWN) areaId else null,
        areaOtherText = if (areaChoice == AreaChoice.OTHER)
            (if (sameLocation) post.areaOtherText.ifBlank { location } else location) else "",
        locationNote = if (sameLocation) post.locationNote else "",
        timeMode = timeMode,
        exactAtMillis = post.exactAtMillis,
        rangeStartMillis = post.rangeStartMillis,
        rangeEndMillis = post.rangeEndMillis,
        timeNote = post.timeNote.ifBlank { post.occurredAt },
    )
}
