package com.example.productivityapp

import java.time.LocalDate
import java.time.ZoneId

object TodayPlanWork {

    /** True when any active task has an incomplete roadmap step scheduled before [today]. */
    fun hasIncompleteOverdueSteps(
        tasks: List<SupabaseTasksApi.TaskRow>,
        today: LocalDate,
    ): Boolean {
        for (task in tasks) {
            val steps = RoadmapStep.parseList(task.roadmap)
            for (step in steps) {
                val on = RoadmapStep.recommendedLocalDate(step) ?: continue
                if (on.isBefore(today) && !step.completed) return true
            }
        }
        return false
    }

    /**
     * Steps that count toward today's progress bar: still to do (overdue or due today), plus anything
     * checked off today. Completed overdue steps stay in the pool when finished today so the bar fills
     * up instead of shrinking. Past completions (no [RoadmapStep.completedAt] or an earlier date) are excluded.
     */
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

    fun wasCompletedToday(
        step: RoadmapStep,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean = step.completed && RoadmapStep.completionLocalDate(step, zone) == today
}
