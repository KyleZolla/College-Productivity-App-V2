package com.example.productivityapp

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object DueDateTimeFormat {
    private val locale: Locale get() = Locale.getDefault()

    fun displayFull(due: LocalDateTime): String =
        due.format(DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", locale))

    fun displayListRow(due: LocalDateTime): String =
        due.format(DateTimeFormatter.ofPattern("MMM d, yyyy '·' h:mm a", locale))

    fun timeOnly(due: LocalDateTime): String =
        due.format(DateTimeFormatter.ofPattern("h:mm a", locale))
}
