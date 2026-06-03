package com.example.productivityapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class TodayPlanAdjustmentTest {

    private val today = LocalDate.of(2026, 6, 2)

    private fun simpleEntry(id: String = "s1") = TodayPlanEntry(
        task = SupabaseTasksApi.TaskRow(
            id = id,
            title = "Simple",
            dueDate = LocalDateTime.of(today, LocalTime.NOON),
            status = TaskStatus.NOT_STARTED,
            roadmap = null,
        ),
        step = null,
        stepIndex = TodayPlanEntry.SIMPLE_STEP_INDEX,
        recommendedOn = today,
        isSimple = true,
    )

    private fun complexEntry(
        id: String = "c1",
        hours: Double = 2.0,
        recommendedOn: LocalDate = today,
    ): TodayPlanEntry {
        val step = RoadmapStep(
            title = "Step",
            description = "",
            recommendedDate = recommendedOn.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE),
            estimatedHours = hours,
            priority = RoadmapStep.Priority.MEDIUM,
            completed = false,
        )
        return TodayPlanEntry(
            task = SupabaseTasksApi.TaskRow(
                id = id,
                title = "Complex",
                dueDate = LocalDateTime.of(today.plusDays(3), LocalTime.NOON),
                status = TaskStatus.NOT_STARTED,
                roadmap = null,
            ),
            step = step,
            stepIndex = 0,
            recommendedOn = recommendedOn,
            isSimple = false,
        )
    }

    @Test
    fun plannedHoursForIncomplete_includesSimpleTasks() {
        val entries = listOf(
            complexEntry(hours = 3.5),
            simpleEntry("s1"),
            simpleEntry("s2"),
            simpleEntry("s3"),
        )
        assertEquals(5.0, TodayPlanAdjustment.plannedHoursForIncomplete(entries), 0.001)
    }

    @Test
    fun computePlan_countsSimpleTasksTowardBudget() {
        val entries = listOf(
            complexEntry(hours = 3.5),
            simpleEntry("s1"),
            simpleEntry("s2"),
            simpleEntry("s3"),
        )
        val plan = TodayPlanAdjustment.computePlan(entries, availableHours = 2.0, today = today)
        assertEquals(5.0, plan.plannedHours, 0.001)
        assertFalse(plan.fitsAvailableTime)
    }

    @Test
    fun computePlan_reservesSimpleHoursWhenPackingComplexSteps() {
        val entries = listOf(
            complexEntry(id = "c1", hours = 2.0),
            complexEntry(id = "c2", hours = 2.0),
            simpleEntry("s1"),
        )
        val plan = TodayPlanAdjustment.computePlan(entries, availableHours = 2.5, today = today)
        assertFalse(plan.fitsAvailableTime)
        val focusHours = plan.recommendedFocus.sumOf { TodayPlanAdjustment.stepHours(it) }
        assertTrue(focusHours <= 2.0 + 0.01)
    }

    @Test
    fun computePlan_simpleOnlyOverBudget_doesNotFit() {
        val entries = listOf(simpleEntry("s1"), simpleEntry("s2"), simpleEntry("s3"))
        val plan = TodayPlanAdjustment.computePlan(entries, availableHours = 1.0, today = today)
        assertEquals(1.5, plan.plannedHours, 0.001)
        assertFalse(plan.fitsAvailableTime)
        assertTrue(plan.recommendedFocus.isEmpty())
    }
}
