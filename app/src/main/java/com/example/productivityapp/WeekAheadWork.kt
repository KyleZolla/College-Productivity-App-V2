package com.example.productivityapp

import java.time.LocalDate
import java.time.LocalDateTime

object WeekAheadWork {

    /** Inclusive calendar days from [today] through [today] + 7 days (8 days total). */
    fun weekDates(today: LocalDate): List<LocalDate> =
        (0..7).map { today.plusDays(it.toLong()) }

    fun isInWeekRange(date: LocalDate, today: LocalDate): Boolean {
        val end = today.plusDays(7)
        return !date.isBefore(today) && !date.isAfter(end)
    }

    fun tasksDueInWeek(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): List<SupabaseTasksApi.TaskRow> {
        val end = today.plusDays(7)
        return tasks.filter { task ->
            if (task.status == TaskStatus.COMPLETE) return@filter false
            val dueDay = task.dueDate?.toLocalDate() ?: return@filter false
            !dueDay.isBefore(today) && !dueDay.isAfter(end)
        }
    }

    fun plannedRoadmapHoursForDay(
        tasks: List<SupabaseTasksApi.TaskRow>,
        day: LocalDate,
    ): Double {
        var total = 0.0
        for (task in tasks) {
            if (task.status == TaskStatus.COMPLETE) continue
            if (TaskKind.isSimpleTask(task)) {
                val planOn = TodayPlanWork.simpleTaskPlanLocalDate(task) ?: continue
                if (planOn == day) total += TodayPlanWork.SIMPLE_TASK_HOURS
                continue
            }
            total += plannedRoadmapHoursForSteps(RoadmapStep.parseList(task.roadmap), day)
        }
        return total
    }

    /** Calendar day used for week-ahead grouping: plan day for simple tasks, due day otherwise. */
    fun taskWeekAheadDay(task: SupabaseTasksApi.TaskRow): LocalDate? =
        if (TaskKind.isSimpleTask(task)) {
            TodayPlanWork.simpleTaskPlanLocalDate(task)
        } else {
            task.dueDate?.toLocalDate()
        }

    fun plannedRoadmapHoursForSteps(steps: List<RoadmapStep>, day: LocalDate): Double {
        var total = 0.0
        for (step in steps) {
            if (step.completed) continue
            val on = RoadmapStep.recommendedLocalDate(step) ?: continue
            if (on != day) continue
            total += stepHours(step)
        }
        return total
    }

    fun totalPlannedRoadmapHours(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): Double = weekDates(today).sumOf { plannedRoadmapHoursForDay(tasks, it) }

    fun busiestDay(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): Pair<LocalDate, Double>? {
        var bestDay: LocalDate? = null
        var bestHours = 0.0
        for (day in weekDates(today)) {
            val hours = plannedRoadmapHoursForDay(tasks, day)
            if (hours > bestHours) {
                bestHours = hours
                bestDay = day
            }
        }
        return bestDay?.let { it to bestHours }
    }

    fun nextDueTaskInWeek(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): SupabaseTasksApi.TaskRow? {
        val end = today.plusDays(7)
        return tasks
            .filter { task ->
                if (task.status == TaskStatus.COMPLETE) return@filter false
                val due = task.dueDate ?: return@filter false
                val dueDay = due.toLocalDate()
                !dueDay.isBefore(today) && !dueDay.isAfter(end)
            }
            .minWithOrNull(
                compareBy<SupabaseTasksApi.TaskRow> { it.dueDate }
                    .thenBy { it.title.lowercase() },
            )
    }

    fun dayBreakdowns(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): List<WeekAheadDayBreakdown> {
        val dueTasks = tasksDueInWeek(tasks, today)
        return weekDates(today).map { day ->
            WeekAheadDayBreakdown(
                date = day,
                plannedHours = plannedRoadmapHoursForDay(tasks, day),
                tasksDue = dueTasks
                    .filter { taskWeekAheadDay(it) == day }
                    .sortedWith(
                        compareBy<SupabaseTasksApi.TaskRow> { taskWeekAheadDay(it) }
                            .thenBy { it.dueDate }
                            .thenBy { it.title.lowercase() },
                    ),
            )
        }
    }

    fun computeSummary(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): WeekAheadSummary {
        val dueTasks = tasksDueInWeek(tasks, today)
        val busiest = busiestDay(tasks, today)
        return WeekAheadSummary(
            tasksDueCount = dueTasks.size,
            totalPlannedHours = totalPlannedRoadmapHours(tasks, today),
            busiestDay = busiest?.first,
            busiestDayHours = busiest?.second ?: 0.0,
            nextDueTask = nextDueTaskInWeek(tasks, today),
            dayBreakdowns = dayBreakdowns(tasks, today),
        )
    }

    fun formatPlannedHours(hours: Double): String {
        if (hours <= 0.0) return "0"
        val rounded = kotlin.math.round(hours * 10.0) / 10.0
        return if (kotlin.math.abs(rounded - rounded.toInt()) < 0.05) {
            rounded.toInt().toString()
        } else {
            String.format(java.util.Locale.getDefault(), "%.1f", rounded)
        }
    }

    fun dueDayLabel(due: LocalDateTime, today: LocalDate): String {
        val dueDay = due.toLocalDate()
        return when (java.time.temporal.ChronoUnit.DAYS.between(today, dueDay)) {
            0L -> "today"
            1L -> "tomorrow"
            else -> dueDay.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.FULL,
                java.util.Locale.getDefault(),
            )
        }
    }

    private fun stepHours(step: RoadmapStep): Double =
        step.estimatedHours?.takeIf { !it.isNaN() && it > 0.0 } ?: 0.0
}

data class WeekAheadDayBreakdown(
    val date: LocalDate,
    val plannedHours: Double,
    val tasksDue: List<SupabaseTasksApi.TaskRow>,
)

data class WeekAheadSummary(
    val tasksDueCount: Int,
    val totalPlannedHours: Double,
    val busiestDay: LocalDate?,
    val busiestDayHours: Double,
    val nextDueTask: SupabaseTasksApi.TaskRow?,
    val dayBreakdowns: List<WeekAheadDayBreakdown>,
)
