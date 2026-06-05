package com.example.productivityapp

import android.util.Log
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Computes the user's existing per-day roadmap workload so the roadmap Edge Function can
 * schedule a new task's steps around days that are already busy.
 */
object ExistingWorkload {

    private const val LOG_TAG = "ExistingWorkload"

    /**
     * Sums [RoadmapStep.estimatedHours] per calendar day for every existing roadmap step whose
     * recommended date falls within [today]..[dueDate] (inclusive) and has a positive hours value.
     *
     * Keys are ISO `YYYY-MM-DD` strings. [today] and [dueDate] must already be local-time calendar
     * days so the range matches the user's actual calendar.
     */
    fun aggregate(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
        dueDate: LocalDate,
    ): Map<String, Double> {
        if (dueDate.isBefore(today)) return emptyMap()
        val perDay = HashMap<String, Double>()
        for (task in tasks) {
            for (step in RoadmapStep.parseList(task.roadmap)) {
                val hours = step.estimatedHours?.takeIf { !it.isNaN() && it > 0.0 } ?: continue
                val on = RoadmapStep.recommendedLocalDate(step) ?: continue
                if (on.isBefore(today) || on.isAfter(dueDate)) continue
                val key = on.format(DateTimeFormatter.ISO_LOCAL_DATE)
                perDay[key] = (perDay[key] ?: 0.0) + hours
            }
        }
        return perDay
    }

    /**
     * Queries the current user's active tasks and aggregates their existing per-day roadmap hours
     * for the [today]..[dueDate] range. Returns an empty map (and logs) if the query fails.
     */
    fun loadForRange(
        accessToken: String,
        today: LocalDate,
        dueDate: LocalDate,
    ): Map<String, Double> {
        val tasks = when (val result = SupabaseTasksApi.listTasks(accessToken)) {
            is SupabaseTasksApi.ListResult.Success -> result.tasks
            is SupabaseTasksApi.ListResult.Failure -> {
                Log.w(LOG_TAG, "Could not load existing workload: ${result.message}")
                return emptyMap()
            }
        }
        return aggregate(tasks, today, dueDate)
    }
}
