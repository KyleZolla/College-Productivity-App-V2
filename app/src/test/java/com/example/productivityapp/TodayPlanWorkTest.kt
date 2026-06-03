package com.example.productivityapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class TodayPlanWorkTest {

    private val today = LocalDate.of(2026, 6, 2) // Tuesday

    private fun simpleTask(
        id: String,
        due: LocalDateTime,
        status: TaskStatus = TaskStatus.NOT_STARTED,
    ) = SupabaseTasksApi.TaskRow(
        id = id,
        title = "Simple",
        dueDate = due,
        status = status,
        roadmap = null,
    )

    @Test
    fun simpleTaskPlanLocalDate_atOrBeforeNoon_usesDayBefore() {
        val dueDay = today.plusDays(1)
        assertEquals(
            today,
            TodayPlanWork.simpleTaskPlanLocalDate(
                simpleTask("1", LocalDateTime.of(dueDay, LocalTime.of(11, 0))),
            ),
        )
        assertEquals(
            today,
            TodayPlanWork.simpleTaskPlanLocalDate(
                simpleTask("2", LocalDateTime.of(dueDay, LocalTime.NOON)),
            ),
        )
    }

    @Test
    fun simpleTaskPlanLocalDate_afterNoon_usesDueDay() {
        val dueDay = today.plusDays(1)
        assertEquals(
            dueDay,
            TodayPlanWork.simpleTaskPlanLocalDate(
                simpleTask("1", LocalDateTime.of(dueDay, LocalTime.of(12, 1))),
            ),
        )
    }

    @Test
    fun collectSimpleTodayPlanEntries_includesNoonDueTaskOnPriorDay() {
        val dueTomorrowNoon = LocalDateTime.of(today.plusDays(1), LocalTime.NOON)
        val entries = TodayPlanWork.collectSimpleTodayPlanEntries(
            listOf(simpleTask("1", dueTomorrowNoon)),
            today,
        )
        assertEquals(1, entries.size)
        assertEquals(today, entries[0].recommendedOn)
    }

    @Test
    fun collectSimpleTodayPlanEntries_excludesAfternoonDueUntilDueDay() {
        val dueTomorrowAfternoon = LocalDateTime.of(today.plusDays(1), LocalTime.of(15, 0))
        val entries = TodayPlanWork.collectSimpleTodayPlanEntries(
            listOf(simpleTask("1", dueTomorrowAfternoon)),
            today,
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun collectSimpleFuturePlanEntries_putsAfternoonDueOnDueDay() {
        val dueTomorrowAfternoon = LocalDateTime.of(today.plusDays(1), LocalTime.of(15, 0))
        val entries = TodayPlanWork.collectSimpleFuturePlanEntries(
            listOf(simpleTask("1", dueTomorrowAfternoon)),
            today,
        )
        assertEquals(1, entries.size)
        assertEquals(today.plusDays(1), entries[0].recommendedOn)
    }

    @Test
    fun hasIncompleteOverdueSteps_whenNoonDueMissedYesterday() {
        val dueTodayNoon = LocalDateTime.of(today, LocalTime.NOON)
        assertTrue(
            TodayPlanWork.hasIncompleteOverdueSteps(
                listOf(simpleTask("1", dueTodayNoon)),
                today,
            ),
        )
    }

    @Test
    fun hasIncompleteOverdueSteps_falseWhenAfternoonDueToday() {
        val dueTodayAfternoon = LocalDateTime.of(today, LocalTime.of(18, 0))
        assertFalse(
            TodayPlanWork.hasIncompleteOverdueSteps(
                listOf(simpleTask("1", dueTodayAfternoon)),
                today,
            ),
        )
    }
}
