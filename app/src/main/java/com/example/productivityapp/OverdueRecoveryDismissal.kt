package com.example.productivityapp

import android.content.Context
import java.time.LocalDate

/** Persists "Keep as is" dismissal for the current calendar day. */
object OverdueRecoveryDismissal {

    private const val PREFS_NAME = "home_overdue_recovery_prefs"
    /** v2: only written after the undo snackbar is dismissed without undo. */
    private const val KEY_DISMISSED_DATE = "dismissed_date_v2"

    fun isDismissedForToday(context: Context, today: LocalDate = LocalDate.now()): Boolean {
        val dismissed = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DISMISSED_DATE, null)
        return dismissed == today.toString()
    }

    fun dismissForToday(context: Context, today: LocalDate = LocalDate.now()) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISMISSED_DATE, today.toString())
            .apply()
    }

    fun clearForToday(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DISMISSED_DATE)
            .apply()
    }
}
