package com.example.productivityapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class WeekAheadWorkTest {

    private val today = LocalDate.of(2026, 6, 1) // Monday

    private fun task(
        id: String,
        title: String,
        due: LocalDateTime? = null,
        status: TaskStatus = TaskStatus.NOT_STARTED,
    ) = SupabaseTasksApi.TaskRow(
        id = id,
        title = title,
        dueDate = due,
        status = status,
        roadmap = null,
    )

    private fun step(
        date: String,
        hours: Double? = 1.0,
        completed: Boolean = false,
    ) = RoadmapStep(
        title = "Step",
        description = "",
        recommendedDate = date,
        estimatedHours = hours,
        priority = RoadmapStep.Priority.MEDIUM,
        completed = completed,
    )

    @Test
    fun weekDates_includesTodayThroughSevenDaysLater() {
        val dates = WeekAheadWork.weekDates(today)
        assertEquals(8, dates.size)
        assertEquals(today, dates.first())
        assertEquals(today.plusDays(7), dates.last())
    }

    @Test
    fun tasksDueInWeek_countsActiveTasksWithDueDateInRange() {
        val tasks = listOf(
            task("1", "In range", LocalDateTime.of(today.plusDays(2), LocalTime.NOON)),
            task("2", "Today", LocalDateTime.of(today, LocalTime.MIDNIGHT)),
            task("3", "Too late", LocalDateTime.of(today.plusDays(8), LocalTime.NOON)),
            task("4", "Overdue", LocalDateTime.of(today.minusDays(1), LocalTime.NOON)),
            task("5", "No due"),
            task("6", "Done", LocalDateTime.of(today.plusDays(1), LocalTime.NOON), status = TaskStatus.COMPLETE),
        )
        assertEquals(2, WeekAheadWork.tasksDueInWeek(tasks, today).size)
    }

    @Test
    fun plannedHours_sumsIncompleteRoadmapStepsInRange() {
        val steps = listOf(
            step("2026-06-01", hours = 1.5),
            step("2026-06-02", hours = 2.0),
            step("2026-06-02", hours = 1.0, completed = true),
            step("2026-06-09", hours = 5.0),
            step("2026-06-03", hours = null),
        )
        val total = WeekAheadWork.weekDates(today).sumOf { day ->
            WeekAheadWork.plannedRoadmapHoursForSteps(steps, day)
        }
        assertEquals(3.5, total, 0.001)
    }

    @Test
    fun plannedHours_ignoresSimpleTasks() {
        val tasks = listOf(
            task("1", "Simple", due = LocalDateTime.of(today, LocalTime.NOON)),
        )
        assertEquals(0.0, WeekAheadWork.totalPlannedRoadmapHours(tasks, today), 0.001)
    }

    @Test
    fun plannedRoadmapHoursForSteps_aggregatesByDay() {
        val steps = listOf(
            step("2026-06-02", hours = 2.0),
            step("2026-06-04", hours = 2.0),
            step("2026-06-03", hours = 1.0),
        )
        assertEquals(2.0, WeekAheadWork.plannedRoadmapHoursForSteps(steps, LocalDate.of(2026, 6, 2)), 0.001)
        assertEquals(1.0, WeekAheadWork.plannedRoadmapHoursForSteps(steps, LocalDate.of(2026, 6, 3)), 0.001)
        assertEquals(2.0, WeekAheadWork.plannedRoadmapHoursForSteps(steps, LocalDate.of(2026, 6, 4)), 0.001)
    }

    @Test
    fun busiestDay_returnsNullWhenNoHours() {
        val tasks = listOf(task("1", "Simple", due = LocalDateTime.of(today, LocalTime.NOON)))
        assertNull(WeekAheadWork.busiestDay(tasks, today))
    }

    @Test
    fun nextDueTask_picksClosestDueDateInWeek() {
        val tasks = listOf(
            task("1", "Later", LocalDateTime.of(today.plusDays(5), LocalTime.NOON)),
            task("2", "Soon", LocalDateTime.of(today.plusDays(1), LocalTime.of(15, 0))),
            task("3", "Same day earlier", LocalDateTime.of(today.plusDays(1), LocalTime.of(9, 0))),
        )
        val next = WeekAheadWork.nextDueTaskInWeek(tasks, today)
        assertEquals("3", next?.id)
    }

    @Test
    fun computeSummary_countsDueTasksAndNextTask() {
        val tasks = listOf(
            task("1", "Econ Quiz", LocalDateTime.of(today.plusDays(1), LocalTime.NOON)),
            task("2", "Reading", LocalDateTime.of(today.plusDays(3), LocalTime.NOON)),
        )
        val summary = WeekAheadWork.computeSummary(tasks, today)
        assertEquals(2, summary.tasksDueCount)
        assertEquals("1", summary.nextDueTask?.id)
        assertEquals(8, summary.dayBreakdowns.size)
    }

    @Test
    fun dueDayLabel_usesTodayTomorrowOrWeekday() {
        val dueToday = LocalDateTime.of(today, LocalTime.NOON)
        val dueTomorrow = LocalDateTime.of(today.plusDays(1), LocalTime.NOON)
        val dueWednesday = LocalDateTime.of(today.plusDays(2), LocalTime.NOON)
        assertEquals("today", WeekAheadWork.dueDayLabel(dueToday, today))
        assertEquals("tomorrow", WeekAheadWork.dueDayLabel(dueTomorrow, today))
        assertEquals("Wednesday", WeekAheadWork.dueDayLabel(dueWednesday, today))
    }

    @Test
    fun formatPlannedHours_trimsTrailingZero() {
        assertEquals("8", WeekAheadWork.formatPlannedHours(8.0))
        assertEquals("8.5", WeekAheadWork.formatPlannedHours(8.5))
    }
}
