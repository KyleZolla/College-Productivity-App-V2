package com.example.productivityapp

import android.content.Context
import java.time.LocalDate

/** Today-only available work time (minutes), stored locally until cleared or the day changes. */
object TodayPlanTimeLimitStore {

    private const val PREFS = "today_plan_time_limit"
    private const val KEY_DATE = "date"
    private const val KEY_MINUTES = "minutes"

    fun getAvailableMinutes(context: Context, today: LocalDate = LocalDate.now()): Int? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedDate = prefs.getString(KEY_DATE, null) ?: return null
        if (savedDate != today.toString()) {
            clear(context)
            return null
        }
        val minutes = prefs.getInt(KEY_MINUTES, -1)
        return minutes.takeIf { it > 0 }
    }

    fun setAvailableMinutes(context: Context, minutes: Int, today: LocalDate = LocalDate.now()) {
        if (minutes <= 0) {
            clear(context)
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DATE, today.toString())
            .putInt(KEY_MINUTES, minutes)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DATE)
            .remove(KEY_MINUTES)
            .apply()
    }

    fun minutesToHours(minutes: Int): Double = minutes / 60.0
}
