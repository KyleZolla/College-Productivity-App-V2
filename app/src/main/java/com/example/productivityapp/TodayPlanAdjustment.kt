package com.example.productivityapp

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.comparisons.compareBy
import kotlin.comparisons.nullsLast

/**
 * Recommends which incomplete roadmap steps to focus on today vs move to tomorrow,
 * respecting per-task roadmap order.
 */
object TodayPlanAdjustment {

    private const val HOUR_EPSILON = 0.01

    data class StepRef(
        val taskId: String,
        val stepIndex: Int,
    ) {
        fun storageKey(): String = "$taskId|$stepIndex"
    }

    data class Plan(
        val availableHours: Double,
        val plannedHours: Double,
        val recommendedFocus: List<TodayPlanEntry>,
        val moveLater: List<TodayPlanEntry>,
        /** Incomplete steps that would move later but cannot because the task is due today. */
        val blockedOnToday: List<TodayPlanEntry>,
        val fitsAvailableTime: Boolean,
    )

    fun incompleteComplexEntries(workEntries: List<TodayPlanEntry>): List<TodayPlanEntry> =
        workEntries.filter { !it.isSimple && !it.isCompleted }

    fun computePlan(
        workEntries: List<TodayPlanEntry>,
        availableHours: Double,
        today: LocalDate,
    ): Plan {
        val incomplete = incompleteComplexEntries(workEntries)
        val plannedHours = incomplete.sumOf { stepHours(it) }
        if (incomplete.isEmpty()) {
            return Plan(
                availableHours = availableHours,
                plannedHours = plannedHours,
                recommendedFocus = emptyList(),
                moveLater = emptyList(),
                blockedOnToday = emptyList(),
                fitsAvailableTime = true,
            )
        }
        if (plannedHours <= availableHours + HOUR_EPSILON) {
            return Plan(
                availableHours = availableHours,
                plannedHours = plannedHours,
                recommendedFocus = incomplete.sortedWith(displayOrderComparator(today)),
                moveLater = emptyList(),
                blockedOnToday = emptyList(),
                fitsAvailableTime = true,
            )
        }

        val orderedTaskGroups = orderTaskGroups(incomplete, today)
        val focus = ArrayList<TodayPlanEntry>()
        var usedHours = 0.0

        for (group in orderedTaskGroups) {
            for (entry in group) {
                val hours = stepHours(entry)
                if (focus.isEmpty()) {
                    focus.add(entry)
                    usedHours += hours
                    continue
                }
                if (usedHours + hours <= availableHours + HOUR_EPSILON) {
                    focus.add(entry)
                    usedHours += hours
                } else {
                    break
                }
            }
        }

        val focusKeys = focus.map { it.entryKey() }.toSet()
        val moveLater = incomplete.filter { it.entryKey() !in focusKeys }
        val blockedOnToday = moveLater.filter { isTaskDueToday(it.task, today) }
        val movableLater = moveLater.filter { !isTaskDueToday(it.task, today) }

        return Plan(
            availableHours = availableHours,
            plannedHours = plannedHours,
            recommendedFocus = focus.sortedWith(displayOrderComparator(today)),
            moveLater = movableLater.sortedWith(displayOrderComparator(today)),
            blockedOnToday = blockedOnToday.sortedWith(displayOrderComparator(today)),
            fitsAvailableTime = false,
        )
    }

    fun buildApplyUpdates(
        plan: Plan,
        today: LocalDate,
    ): List<OverdueStepRecovery.TaskRoadmapUpdate> {
        if (plan.moveLater.isEmpty()) return emptyList()
        val tomorrow = today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val byTask = plan.moveLater.groupBy { it.task.id }
        val updates = ArrayList<OverdueStepRecovery.TaskRoadmapUpdate>()
        for ((taskId, entries) in byTask) {
            val task = entries.first().task
            val steps = RoadmapStep.parseList(task.roadmap).toMutableList()
            for (entry in entries) {
                if (entry.stepIndex !in steps.indices) continue
                steps[entry.stepIndex] = steps[entry.stepIndex].copy(recommendedDate = tomorrow)
            }
            updates.add(
                OverdueStepRecovery.TaskRoadmapUpdate(
                    taskId = taskId,
                    roadmap = RoadmapStep.toJsonArray(steps),
                ),
            )
        }
        return updates
    }

    fun captureOriginalDates(entries: List<TodayPlanEntry>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (entry in entries) {
            if (entry.isSimple) continue
            val step = entry.step ?: continue
            out[StepRef(entry.task.id, entry.stepIndex).storageKey()] = step.recommendedDate
        }
        return out
    }

    fun buildRestoreUpdates(
        originalDates: Map<String, String>,
        tasks: List<SupabaseTasksApi.TaskRow>,
    ): List<OverdueStepRecovery.TaskRoadmapUpdate> {
        val updates = ArrayList<OverdueStepRecovery.TaskRoadmapUpdate>()
        val keysByTask = originalDates.keys.groupBy { key ->
            parseStorageKey(key)?.first ?: key.substringBeforeLast('|')
        }
        for ((taskId, keys) in keysByTask) {
            val task = tasks.find { it.id == taskId } ?: continue
            val steps = RoadmapStep.parseList(task.roadmap).toMutableList()
            for (key in keys) {
                val (_, stepIndex) = parseStorageKey(key) ?: continue
                val originalDate = originalDates[key] ?: continue
                if (stepIndex !in steps.indices) continue
                steps[stepIndex] = steps[stepIndex].copy(recommendedDate = originalDate)
            }
            updates.add(
                OverdueStepRecovery.TaskRoadmapUpdate(
                    taskId = taskId,
                    roadmap = RoadmapStep.toJsonArray(steps),
                ),
            )
        }
        return updates
    }

    fun parseStorageKey(key: String): Pair<String, Int>? {
        val separator = key.lastIndexOf('|')
        if (separator <= 0 || separator == key.lastIndex) return null
        val taskId = key.substring(0, separator)
        val stepIndex = key.substring(separator + 1).toIntOrNull() ?: return null
        return taskId to stepIndex
    }

    fun isTaskDueToday(task: SupabaseTasksApi.TaskRow, today: LocalDate): Boolean =
        task.dueDate?.toLocalDate() == today

    fun stepHours(entry: TodayPlanEntry): Double =
        entry.estimatedHoursForProgress().coerceAtLeast(0.0)

    private fun orderTaskGroups(
        incomplete: List<TodayPlanEntry>,
        today: LocalDate,
    ): List<List<TodayPlanEntry>> {
        val byTask = incomplete.groupBy { it.task.id }
        val cmp = adjustmentTaskComparator(today)
        return byTask.values
            .sortedWith { a, b -> cmp.compare(taskLead(a), taskLead(b)) }
            .map { group -> group.sortedBy { it.stepIndex } }
    }

    private fun taskLead(group: List<TodayPlanEntry>): TodayPlanEntry =
        group.minBy { it.stepIndex }

    private fun adjustmentTaskComparator(today: LocalDate): Comparator<TodayPlanEntry> =
        compareBy<TodayPlanEntry> { !it.recommendedOn.isBefore(today) }
            .thenBy { priorityRank(it) }
            .thenBy(nullsLast()) { it.task.dueDate }
            .thenBy { stepHours(it) }
            .thenBy { it.stepIndex }

    /** Keeps steps from the same task in roadmap order when displaying lists. */
    private fun displayOrderComparator(@Suppress("UNUSED_PARAMETER") today: LocalDate): Comparator<TodayPlanEntry> =
        compareBy<TodayPlanEntry>({ it.task.id }).thenBy { it.stepIndex }

    private fun priorityRank(entry: TodayPlanEntry): Int =
        when (entry.step?.priority) {
            RoadmapStep.Priority.HIGH -> 0
            RoadmapStep.Priority.MEDIUM -> 1
            RoadmapStep.Priority.LOW -> 2
            null -> 1
        }
}
