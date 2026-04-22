package com.example.productivityapp

import android.content.Context
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

object DueDateHumanLabel {

    fun isOverdue(
        due: LocalDateTime?,
        status: TaskStatus? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): Boolean =
        status != TaskStatus.COMPLETE && due != null && due.isBefore(now)

    fun format(context: Context, due: LocalDateTime?, today: LocalDate, status: TaskStatus? = null): String {
        if (due == null) return context.getString(R.string.due_relative_none)
        val now = LocalDateTime.now()
        if (isOverdue(due, status, now)) {
            val locale = Locale.getDefault()
            val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", locale)
            return context.getString(R.string.due_overdue_was_due, due.format(fmt))
        }
        val locale = Locale.getDefault()
        val dueDay = due.toLocalDate()
        val days = ChronoUnit.DAYS.between(today, dueDay)
        val timeStr = DueDateTimeFormat.timeOnly(due)
        return when {
            days == 0L -> {
                if (due.toLocalTime() == LocalTime.MIDNIGHT) {
                    context.getString(R.string.due_relative_today)
                } else {
                    context.getString(R.string.due_relative_today_at, timeStr)
                }
            }
            days == 1L -> context.getString(R.string.due_relative_tomorrow_at, timeStr)
            days == -1L -> context.getString(R.string.due_relative_yesterday)
            days in 2L..6L ->
                dueDay.dayOfWeek.getDisplayName(TextStyle.FULL, locale) + " " + timeStr
            days in 7L..13L -> {
                val short = dueDay.dayOfWeek.getDisplayName(TextStyle.SHORT_STANDALONE, locale)
                    .replace(".", "")
                context.getString(R.string.due_relative_next_weekday, short) + " " + timeStr
            }
            else -> {
                val fmt = if (dueDay.year == today.year) {
                    DateTimeFormatter.ofPattern("MMM d 'at' h:mm a", locale)
                } else {
                    DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", locale)
                }
                due.format(fmt)
            }
        }
    }
}
