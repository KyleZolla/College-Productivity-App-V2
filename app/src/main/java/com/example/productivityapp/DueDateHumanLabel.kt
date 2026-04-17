package com.example.productivityapp

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

object DueDateHumanLabel {

    fun format(context: Context, due: LocalDate?, today: LocalDate): String {
        if (due == null) return context.getString(R.string.due_relative_none)
        val locale = Locale.getDefault()
        val days = ChronoUnit.DAYS.between(today, due)
        return when {
            days == 0L -> context.getString(R.string.due_relative_today)
            days == 1L -> context.getString(R.string.due_relative_tomorrow)
            days == -1L -> context.getString(R.string.due_relative_yesterday)
            days in 2L..6L ->
                due.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            days in 7L..13L -> {
                val short = due.dayOfWeek.getDisplayName(TextStyle.SHORT_STANDALONE, locale)
                    .replace(".", "")
                context.getString(R.string.due_relative_next_weekday, short)
            }
            else -> {
                val fmt = if (due.year == today.year) {
                    DateTimeFormatter.ofPattern("MMM d", locale)
                } else {
                    DateTimeFormatter.ofPattern("MMM d, yyyy", locale)
                }
                due.format(fmt)
            }
        }
    }
}
