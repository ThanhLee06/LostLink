package com.example.lostlink.ui.home

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

enum class HomeTimeFilter(val label: String) {
    ALL("Tất cả thời gian"),
    TODAY("Hôm nay"),
    THREE_DAYS("Trong 3 ngày qua"),
    THIS_WEEK("Trong tuần này"),
    THIS_MONTH("Trong tháng này");

    fun startAt(now: Long): Long? {
        if (this == ALL) return null
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT+07:00"), Locale.ROOT).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        when (this) {
            THREE_DAYS -> calendar.add(Calendar.DAY_OF_MONTH, -2)
            THIS_WEEK -> calendar.add(Calendar.DAY_OF_MONTH, -((calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7))
            THIS_MONTH -> calendar.set(Calendar.DAY_OF_MONTH, 1)
            else -> Unit
        }
        return calendar.timeInMillis
    }
}
