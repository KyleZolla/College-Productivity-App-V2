package com.example.productivityapp

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object TodayPlanWork {

    const val SIMPLE_TASK_HOURS = 0.5

    /** Simple tasks due at/before this time appear on the prior day's plan (not the due day). */
    private val SIMPLE_TASK_PLAN_DAY_THRESHOLD = LocalTime.NOON

    /** Calendar day (device zone) when the task was marked complete, if [TaskRow.completedAt] is set. */
    fun taskCompletionLocalDate(
        task: SupabaseTasksApi.TaskRow,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDate? {
        val raw = task.completedAt?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return try {
            Instant.parse(raw).atZone(zone).toLocalDate()
        } catch (_: Exception) {
            runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
        }
    }

    fun wasTaskCompletedToday(
        task: SupabaseTasksApi.TaskRow,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean = taskCompletionLocalDate(task, zone) == today

    /** True when any active task has an incomplete roadmap step scheduled before [today]. */
    fun hasIncompleteOverdueSteps(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): Boolean {
        for (task in tasks) {
            if (TaskKind.isSimpleTask(task)) {
                val planOn = simpleTaskPlanLocalDate(task) ?: continue
                if (planOn.isBefore(today) && task.status != TaskStatus.COMPLETE) return true
                continue
            }
            val steps = RoadmapStep.parseList(task.roadmap)
            for (step in steps) {
                val on = RoadmapStep.recommendedLocalDate(step) ?: continue
                if (on.isBefore(today) && !step.completed) return true
            }
        }
        return false
    }

    fun simpleTaskDueLocalDate(task: SupabaseTasksApi.TaskRow): LocalDate? =
        task.dueDate?.toLocalDate()

    /** Calendar day when a simple task belongs on Today's Plan / Get ahead (may be before the due date). */
    fun simpleTaskPlanLocalDate(task: SupabaseTasksApi.TaskRow): LocalDate? {
        val due = task.dueDate ?: return null
        val dueDay = due.toLocalDate()
        return if (!due.toLocalTime().isAfter(SIMPLE_TASK_PLAN_DAY_THRESHOLD)) {
            dueDay.minusDays(1)
        } else {
            dueDay
        }
    }

    /**
     * Inverse of [simpleTaskPlanLocalDate]: the due date that lands a simple task on [planDay]'s plan,
     * preserving the task's original time of day. Returns null when the task has no due date.
     */
    fun simpleTaskDueForPlanDay(
        task: SupabaseTasksApi.TaskRow,
        planDay: LocalDate,
    ): LocalDateTime? {
        val time = (task.dueDate ?: return null).toLocalTime()
        val dueDay = if (!time.isAfter(SIMPLE_TASK_PLAN_DAY_THRESHOLD)) planDay.plusDays(1) else planDay
        return LocalDateTime.of(dueDay, time)
    }

    /** Simple tasks scheduled today or earlier on the plan (includes completed — used for scope/progress/review). */
    fun collectSimpleTodayPlanEntries(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): List<TodayPlanEntry> {
        val out = ArrayList<TodayPlanEntry>()
        for (task in tasks) {
            if (!TaskKind.isSimpleTask(task)) continue
            val planOn = simpleTaskPlanLocalDate(task) ?: continue
            if (planOn.isAfter(today)) continue
            out.add(
                TodayPlanEntry(
                    task = task,
                    step = null,
                    stepIndex = TodayPlanEntry.SIMPLE_STEP_INDEX,
                    recommendedOn = planOn,
                    isSimple = true,
                ),
            )
        }
        return out
    }

    /** Incomplete simple tasks due after [today], plus completed ones pinned in Get ahead. */
    fun collectSimpleFuturePlanEntries(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
        getAheadPinnedKeys: Set<String> = emptySet(),
    ): List<TodayPlanEntry> {
        val out = ArrayList<TodayPlanEntry>()
        for (task in tasks) {
            if (!TaskKind.isSimpleTask(task)) continue
            val planOn = simpleTaskPlanLocalDate(task) ?: continue
            if (!planOn.isAfter(today)) continue
            val pinKey = "${task.id}:simple"
            if (task.status == TaskStatus.COMPLETE &&
                pinKey !in getAheadPinnedKeys &&
                !wasTaskCompletedToday(task, today)
            ) {
                continue
            }
            out.add(
                TodayPlanEntry(
                    task = task,
                    step = null,
                    stepIndex = TodayPlanEntry.SIMPLE_STEP_INDEX,
                    recommendedOn = planOn,
                    isSimple = true,
                ),
            )
        }
        return out
    }

    fun collectComplexTodayPlanEntries(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): List<TodayPlanEntry> {
        val out = ArrayList<TodayPlanEntry>()
        for (task in tasks) {
            if (TaskKind.isSimpleTask(task)) continue
            val steps = RoadmapStep.parseList(task.roadmap)
            steps.forEachIndexed { index, step ->
                val on = RoadmapStep.recommendedLocalDate(step) ?: return@forEachIndexed
                if (on.isAfter(today)) return@forEachIndexed
                out.add(
                    TodayPlanEntry(
                        task = task,
                        step = step,
                        stepIndex = index,
                        recommendedOn = on,
                        isSimple = false,
                    ),
                )
            }
        }
        return out
    }

    fun collectComplexFuturePlanEntries(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): List<TodayPlanEntry> {
        val out = ArrayList<TodayPlanEntry>()
        for (task in tasks) {
            if (TaskKind.isSimpleTask(task)) continue
            val steps = RoadmapStep.parseList(task.roadmap)
            steps.forEachIndexed { index, step ->
                val on = RoadmapStep.recommendedLocalDate(step) ?: return@forEachIndexed
                if (!on.isAfter(today)) return@forEachIndexed
                out.add(
                    TodayPlanEntry(
                        task = task,
                        step = step,
                        stepIndex = index,
                        recommendedOn = on,
                        isSimple = false,
                    ),
                )
            }
        }
        return out
    }

    fun collectTodayPlanScopeEntries(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): List<TodayPlanEntry> =
        collectComplexTodayPlanEntries(tasks, today) + collectSimpleTodayPlanEntries(tasks, today)

    fun collectFuturePlanEntries(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
        getAheadPinnedKeys: Set<String> = emptySet(),
    ): List<TodayPlanEntry> =
        collectComplexFuturePlanEntries(tasks, today) +
            collectSimpleFuturePlanEntries(tasks, today, getAheadPinnedKeys)

    fun countsTowardTodayProgress(
        step: RoadmapStep,
        recommendedOn: LocalDate,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (recommendedOn.isAfter(today)) return false
        if (!step.completed) return true
        return RoadmapStep.completionLocalDate(step, zone) == today
    }

    fun simpleCountsTowardTodayProgress(
        task: SupabaseTasksApi.TaskRow,
        dueOn: LocalDate,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (dueOn.isAfter(today)) return false
        if (task.status != TaskStatus.COMPLETE) return true
        return wasTaskCompletedToday(task, today, zone)
    }

    fun wasCompletedToday(
        step: RoadmapStep,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean = step.completed && RoadmapStep.completionLocalDate(step, zone) == today

    /** All simple tasks first, then all complex steps — never under a complex task header. */
    fun sortTodayPlanEntries(entries: List<TodayPlanEntry>, today: LocalDate): List<TodayPlanEntry> {
        val simple = entries.filter { it.isSimple }.sortedWith(simplePlanComparator(today))
        val complex = entries.filter { !it.isSimple }.sortedWith(complexPlanComparator(today))
        return simple + complex
    }

    /** Within a future focus day: simple tasks first, then complex steps in roadmap order. */
    fun sortFutureDayEntries(entries: List<TodayPlanEntry>): List<TodayPlanEntry> {
        val simple = entries.filter { it.isSimple }.sortedBy { it.task.dueDate }
        val complex = entries.filter { !it.isSimple }.sortedWith(
            compareBy<TodayPlanEntry> { it.task.dueDate }
                .thenBy { it.stepIndex },
        )
        return simple + complex
    }

    private fun simplePlanComparator(today: LocalDate): Comparator<TodayPlanEntry> =
        compareBy<TodayPlanEntry> { !it.recommendedOn.isBefore(today) }
            .thenBy { it.task.dueDate }

    private fun complexPlanComparator(today: LocalDate): Comparator<TodayPlanEntry> =
        compareBy<TodayPlanEntry> { !it.recommendedOn.isBefore(today) }
            .thenBy { it.recommendedOn }
            .thenBy { it.stepIndex }
}
