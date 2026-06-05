package com.example.productivityapp

import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Collects and reschedules incomplete roadmap steps scheduled before today. */
object OverdueStepRecovery {

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** Due times at/after this are treated as leaving the due calendar day open for work. */
    private val WORKDAY_END_THRESHOLD = LocalTime.of(18, 0)

    /** Fallback when a step has no [RoadmapStep.estimatedHours] for load balancing. */
    private const val UNKNOWN_STEP_HOURS = 0.5

    data class OverdueStepRef(
        val taskId: String,
        val taskTitle: String,
        val stepIndex: Int,
        /** True for simple (roadmap-less) tasks rescheduled by moving their due date. */
        val isSimple: Boolean = false,
    )

    data class Summary(
        val steps: List<OverdueStepRef>,
    ) {
        val stepCount: Int get() = steps.size
        val taskCount: Int get() = steps.map { it.taskId }.distinct().size
        val singleTaskTitle: String?
            get() = if (taskCount == 1) steps.first().taskTitle else null
    }

    data class TaskRoadmapUpdate(
        val taskId: String,
        val roadmap: JSONArray,
    )

    /** Reschedules a simple task by moving its due date (its plan day is derived from the due date). */
    data class TaskDueUpdate(
        val taskId: String,
        val dueDate: LocalDateTime,
    )

    /** Combined output of a reschedule: roadmap-step moves for complex tasks, due-date moves for simple ones. */
    data class RescheduleUpdates(
        val roadmapUpdates: List<TaskRoadmapUpdate>,
        val dueUpdates: List<TaskDueUpdate>,
    ) {
        val isEmpty: Boolean get() = roadmapUpdates.isEmpty() && dueUpdates.isEmpty()
    }

    enum class RescheduleMode { ADD_TO_TODAY, SPREAD_OUT }

    fun collectSummary(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): Summary {
        val steps = ArrayList<OverdueStepRef>()
        for (task in tasks) {
            if (task.status == TaskStatus.COMPLETE) continue
            if (TaskKind.isSimpleTask(task)) {
                val planOn = TodayPlanWork.simpleTaskPlanLocalDate(task) ?: continue
                if (planOn.isBefore(today)) {
                    steps.add(
                        OverdueStepRef(
                            taskId = task.id,
                            taskTitle = task.title,
                            stepIndex = TodayPlanEntry.SIMPLE_STEP_INDEX,
                            isSimple = true,
                        ),
                    )
                }
                continue
            }
            val roadmapSteps = RoadmapStep.parseList(task.roadmap)
            roadmapSteps.forEachIndexed { index, step ->
                if (step.completed) return@forEachIndexed
                val scheduled = RoadmapStep.recommendedLocalDate(step) ?: return@forEachIndexed
                if (scheduled.isBefore(today)) {
                    steps.add(
                        OverdueStepRef(
                            taskId = task.id,
                            taskTitle = task.title,
                            stepIndex = index,
                        ),
                    )
                }
            }
        }
        return Summary(steps)
    }

    fun buildUpdates(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
        mode: RescheduleMode,
    ): RescheduleUpdates {
        val summary = collectSummary(tasks, today)
        if (summary.steps.isEmpty()) return RescheduleUpdates(emptyList(), emptyList())

        val (simpleRefs, roadmapRefs) = summary.steps.partition { it.isSimple }

        val roadmapUpdates = ArrayList<TaskRoadmapUpdate>()
        for ((taskId, refs) in roadmapRefs.groupBy { it.taskId }) {
            val task = tasks.find { it.id == taskId } ?: continue
            val roadmapSteps = RoadmapStep.parseList(task.roadmap).toMutableList()
            val overdueIndices = refs
                .sortedWith(compareBy({ it.stepIndex }, { roadmapSteps[it.stepIndex].recommendedDate }))
                .map { it.stepIndex }
            val rescheduled = when (mode) {
                RescheduleMode.ADD_TO_TODAY -> applyAddToToday(roadmapSteps, overdueIndices, today)
                RescheduleMode.SPREAD_OUT -> applySpreadOut(
                    steps = roadmapSteps,
                    overdueIndices = overdueIndices,
                    today = today,
                    taskDueDateTime = task.dueDate,
                )
            }
            roadmapUpdates.add(TaskRoadmapUpdate(taskId, RoadmapStep.toJsonArray(rescheduled)))
        }

        // Simple tasks have no separate work date, so both modes simply move them onto today's plan.
        val dueUpdates = ArrayList<TaskDueUpdate>()
        for (ref in simpleRefs) {
            val task = tasks.find { it.id == ref.taskId } ?: continue
            val newDue = TodayPlanWork.simpleTaskDueForPlanDay(task, today) ?: continue
            dueUpdates.add(TaskDueUpdate(ref.taskId, newDue))
        }

        return RescheduleUpdates(roadmapUpdates, dueUpdates)
    }

    fun applyUpdatesToSnapshot(
        tasks: List<SupabaseTasksApi.TaskRow>,
        updates: List<TaskRoadmapUpdate>,
    ): List<SupabaseTasksApi.TaskRow> {
        if (updates.isEmpty()) return tasks
        val byId = updates.associateBy { it.taskId }
        return tasks.map { task ->
            val update = byId[task.id] ?: return@map task
            task.copy(roadmap = update.roadmap)
        }
    }

    fun applyDueUpdatesToSnapshot(
        tasks: List<SupabaseTasksApi.TaskRow>,
        updates: List<TaskDueUpdate>,
    ): List<SupabaseTasksApi.TaskRow> {
        if (updates.isEmpty()) return tasks
        val byId = updates.associateBy { it.taskId }
        return tasks.map { task ->
            val update = byId[task.id] ?: return@map task
            task.copy(dueDate = update.dueDate)
        }
    }

    internal fun applyAddToToday(
        steps: List<RoadmapStep>,
        overdueIndices: List<Int>,
        today: LocalDate,
    ): List<RoadmapStep> {
        if (overdueIndices.isEmpty()) return steps
        val todayStr = today.format(dateFormatter)
        val mutable = steps.toMutableList()
        for (index in overdueIndices) {
            if (index !in mutable.indices) continue
            mutable[index] = mutable[index].copy(recommendedDate = todayStr)
        }
        return mutable
    }

    internal fun applySpreadOut(
        steps: List<RoadmapStep>,
        overdueIndices: List<Int>,
        today: LocalDate,
        taskDueDateTime: LocalDateTime?,
    ): List<RoadmapStep> {
        if (overdueIndices.isEmpty()) return steps
        val availableDates = availableScheduleDates(today, taskDueDateTime)
        val mutable = steps.toMutableList()
        val overdueSet = overdueIndices.toSet()
        val dayLoads = initialDayLoads(mutable, availableDates, overdueSet)
        val assignedLoads = mutableMapOf<LocalDate, Double>()

        fun latestPriorIncompleteDate(beforeIndex: Int): LocalDate? {
            var latest: LocalDate? = null
            for (i in 0 until beforeIndex) {
                if (mutable[i].completed) continue
                val date = RoadmapStep.recommendedLocalDate(mutable[i]) ?: continue
                if (latest == null || date.isAfter(latest)) latest = date
            }
            return latest
        }

        fun earliestLaterIncompleteDate(afterIndex: Int): LocalDate? {
            var earliest: LocalDate? = null
            for (i in (afterIndex + 1) until mutable.size) {
                if (mutable[i].completed) continue
                if (i in overdueSet) continue
                val date = RoadmapStep.recommendedLocalDate(mutable[i]) ?: continue
                if (earliest == null || date.isBefore(earliest)) earliest = date
            }
            return earliest
        }

        fun totalLoadOn(day: LocalDate): Double =
            (dayLoads[day] ?: 0.0) + (assignedLoads[day] ?: 0.0)

        for (index in overdueIndices.sorted()) {
            if (index !in mutable.indices) continue

            val minDate = maxOf(latestPriorIncompleteDate(index) ?: today, today)
            val lastAvailable = availableDates.last()
            val laterCap = earliestLaterIncompleteDate(index)
            val maxDate = when {
                laterCap != null -> minOf(laterCap, lastAvailable)
                else -> lastAvailable
            }
            if (maxDate.isBefore(minDate)) {
                mutable[index] = mutable[index].copy(recommendedDate = maxDate.format(dateFormatter))
                val hours = stepHours(mutable[index])
                assignedLoads[maxDate] = (assignedLoads[maxDate] ?: 0.0) + hours
                continue
            }

            val candidates = availableDates.filter { !it.isBefore(minDate) && !it.isAfter(maxDate) }
            val targetDate = candidates.minWithOrNull(
                compareBy<LocalDate>({ totalLoadOn(it) }, { it }),
            ) ?: maxDate

            mutable[index] = mutable[index].copy(recommendedDate = targetDate.format(dateFormatter))
            assignedLoads[targetDate] = (assignedLoads[targetDate] ?: 0.0) + stepHours(mutable[index])
        }
        return mutable
    }

    internal fun stepHours(step: RoadmapStep): Double =
        step.estimatedHours?.takeIf { it > 0.0 } ?: UNKNOWN_STEP_HOURS

    internal fun initialDayLoads(
        steps: List<RoadmapStep>,
        availableDates: List<LocalDate>,
        overdueSet: Set<Int>,
    ): Map<LocalDate, Double> {
        val loads = availableDates.associateWith { 0.0 }.toMutableMap()
        steps.forEachIndexed { index, step ->
            if (step.completed) return@forEachIndexed
            if (index in overdueSet) return@forEachIndexed
            val day = RoadmapStep.recommendedLocalDate(step) ?: return@forEachIndexed
            if (day !in loads) return@forEachIndexed
            loads[day] = (loads[day] ?: 0.0) + stepHours(step)
        }
        return loads
    }

    /** True when the due calendar day still has meaningful work time (e.g. due at 11 PM, not noon). */
    internal fun dueDateCountsAsWorkDay(taskDueDateTime: LocalDateTime): Boolean =
        !taskDueDateTime.toLocalTime().isBefore(WORKDAY_END_THRESHOLD)

    internal fun availableScheduleDates(today: LocalDate, taskDueDateTime: LocalDateTime?): List<LocalDate> {
        if (taskDueDateTime == null) {
            return listOf(today)
        }
        val dueDate = taskDueDateTime.toLocalDate()
        val lastSchedulableDay = if (dueDateCountsAsWorkDay(taskDueDateTime)) {
            dueDate
        } else {
            dueDate.minusDays(1)
        }
        if (lastSchedulableDay.isBefore(today)) {
            return listOf(today)
        }
        val dates = ArrayList<LocalDate>()
        var cursor = today
        while (!cursor.isAfter(lastSchedulableDay)) {
            dates.add(cursor)
            cursor = cursor.plusDays(1)
        }
        return dates.ifEmpty { listOf(today) }
    }
}
