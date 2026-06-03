package com.example.productivityapp

import java.time.LocalDate

/**
 * One row in Today's Plan — either a roadmap step (complex task) or a whole simple task.
 */
data class TodayPlanEntry(
    val task: SupabaseTasksApi.TaskRow,
    val step: RoadmapStep?,
    val stepIndex: Int,
    /** For complex steps: recommended date. For simple tasks: plan day (may be before the due date). */
    val recommendedOn: LocalDate,
    val isSimple: Boolean,
) {
    val isCompleted: Boolean
        get() = if (isSimple) task.status == TaskStatus.COMPLETE else step!!.completed

    fun entryKey(): String =
        if (isSimple) "${task.id}:simple" else "${task.id}:$stepIndex"

    fun estimatedHoursForProgress(): Double =
        if (isSimple) TodayPlanWork.SIMPLE_TASK_HOURS else step?.estimatedHours ?: 0.0

    companion object {
        const val SIMPLE_STEP_INDEX = -1
    }
}
