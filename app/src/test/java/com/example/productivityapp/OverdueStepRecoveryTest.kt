package com.example.productivityapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class OverdueStepRecoveryTest {

    private fun step(
        date: String,
        completed: Boolean = false,
        estimatedHours: Double? = 1.0,
    ) = RoadmapStep(
        title = "Step",
        description = "",
        recommendedDate = date,
        estimatedHours = estimatedHours,
        priority = RoadmapStep.Priority.MEDIUM,
        completed = completed,
    )

    @Test
    fun addToToday_movesAllOverdueStepsToToday() {
        val today = LocalDate.of(2026, 5, 30)
        val steps = listOf(
            step("2026-05-28"),
            step("2026-05-29"),
            step("2026-06-01"),
        )
        val updated = OverdueStepRecovery.applyAddToToday(steps, overdueIndices = listOf(0, 1), today)
        assertEquals("2026-05-30", updated[0].recommendedDate)
        assertEquals("2026-05-30", updated[1].recommendedDate)
        assertEquals("2026-06-01", updated[2].recommendedDate)
    }

    @Test
    fun spreadOut_prefersLighterDayWhenLoadsDiffer() {
        val today = LocalDate.of(2026, 5, 30)
        val due = LocalDateTime.of(today.plusDays(4), LocalTime.of(23, 59))
        val steps = listOf(step("2026-05-28"), step("2026-05-27"))
        val updated = OverdueStepRecovery.applySpreadOut(
            steps = steps,
            overdueIndices = listOf(0, 1),
            today = today,
            taskDueDateTime = due,
        )
        assertEquals("2026-05-30", updated[0].recommendedDate)
        assertEquals("2026-05-31", updated[1].recommendedDate)
    }

    @Test
    fun spreadOut_whenDueTodayAtNoon_assignsAllToToday() {
        val today = LocalDate.of(2026, 5, 30)
        val due = LocalDateTime.of(today, LocalTime.NOON)
        val steps = listOf(step("2026-05-28"), step("2026-05-27"))
        val updated = OverdueStepRecovery.applySpreadOut(
            steps = steps,
            overdueIndices = listOf(0, 1),
            today = today,
            taskDueDateTime = due,
        )
        assertEquals("2026-05-30", updated[0].recommendedDate)
        assertEquals("2026-05-30", updated[1].recommendedDate)
    }

    @Test
    fun spreadOut_doesNotScheduleLaterStepBeforeEarlierIncompleteStep() {
        val today = LocalDate.of(2026, 5, 30)
        val future = "2026-06-05"
        val steps = listOf(
            step("2026-05-20", completed = true),
            step("2026-05-21", completed = true),
            step(future),
            step("2026-06-06", completed = true),
            step("2026-06-07", completed = true),
            step("2026-05-25"),
        )
        val updated = OverdueStepRecovery.applySpreadOut(
            steps = steps,
            overdueIndices = listOf(5),
            today = today,
            taskDueDateTime = LocalDateTime.of(today.plusDays(10), LocalTime.of(23, 59)),
        )
        assertEquals(future, updated[2].recommendedDate)
        val step5Date = RoadmapStep.recommendedLocalDate(updated[5])!!
        val step3Date = RoadmapStep.recommendedLocalDate(updated[2])!!
        assertFalse(step5Date.isBefore(step3Date))
    }

    @Test
    fun dueDateCountsAsWorkDay_dependsOnDueTime() {
        val date = LocalDate.of(2026, 6, 1)
        assertFalse(
            OverdueStepRecovery.dueDateCountsAsWorkDay(LocalDateTime.of(date, LocalTime.NOON)),
        )
        assertTrue(
            OverdueStepRecovery.dueDateCountsAsWorkDay(LocalDateTime.of(date, LocalTime.of(18, 0))),
        )
        assertTrue(
            OverdueStepRecovery.dueDateCountsAsWorkDay(LocalDateTime.of(date, LocalTime.of(23, 59))),
        )
    }

    @Test
    fun availableScheduleDates_excludesDueDayWhenDueAtNoon() {
        val today = LocalDate.of(2026, 5, 30)
        val due = LocalDateTime.of(today.plusDays(2), LocalTime.NOON)
        val dates = OverdueStepRecovery.availableScheduleDates(today, due)
        assertEquals(listOf(today, today.plusDays(1)), dates)
    }

    @Test
    fun availableScheduleDates_includesDueDayWhenDueLate() {
        val today = LocalDate.of(2026, 5, 30)
        val due = LocalDateTime.of(today.plusDays(2), LocalTime.of(23, 59))
        val dates = OverdueStepRecovery.availableScheduleDates(today, due)
        assertEquals(listOf(today, today.plusDays(1), today.plusDays(2)), dates)
    }

    @Test
    fun spreadOut_balancesHoursAndAvoidsCrowdingBusyDay() {
        val today = LocalDate.of(2026, 5, 30)
        val tomorrow = "2026-05-31"
        val due = LocalDateTime.of(today.plusDays(2), LocalTime.NOON)
        val steps = listOf(
            step("2026-05-27", estimatedHours = 2.0),
            step("2026-05-28", estimatedHours = 2.0),
            step("2026-05-29", estimatedHours = 2.0),
            step(tomorrow, estimatedHours = 2.0),
            step(tomorrow, estimatedHours = 2.0),
        )
        val updated = OverdueStepRecovery.applySpreadOut(
            steps = steps,
            overdueIndices = listOf(0, 1, 2),
            today = today,
            taskDueDateTime = due,
        )
        assertEquals("2026-05-30", updated[0].recommendedDate)
        assertEquals("2026-05-30", updated[1].recommendedDate)
        assertEquals("2026-05-30", updated[2].recommendedDate)
        assertEquals(tomorrow, updated[3].recommendedDate)
        assertEquals(tomorrow, updated[4].recommendedDate)
    }

    @Test
    fun spreadOut_movesOverflowToNextDayWhenTodayWouldBeHeavier() {
        val today = LocalDate.of(2026, 5, 30)
        val tomorrow = "2026-05-31"
        val due = LocalDateTime.of(today.plusDays(2), LocalTime.NOON)
        val steps = listOf(
            step("2026-05-27", estimatedHours = 2.0),
            step("2026-05-28", estimatedHours = 2.0),
            step("2026-05-29", estimatedHours = 3.0),
            step(tomorrow, estimatedHours = 1.0),
            step(tomorrow, estimatedHours = 1.0),
        )
        val updated = OverdueStepRecovery.applySpreadOut(
            steps = steps,
            overdueIndices = listOf(0, 1, 2),
            today = today,
            taskDueDateTime = due,
        )
        assertEquals("2026-05-30", updated[0].recommendedDate)
        assertEquals("2026-05-30", updated[1].recommendedDate)
        assertEquals("2026-05-31", updated[2].recommendedDate)
        assertEquals(tomorrow, updated[3].recommendedDate)
        assertEquals(tomorrow, updated[4].recommendedDate)
    }
}
