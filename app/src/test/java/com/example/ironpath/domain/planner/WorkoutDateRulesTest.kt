package com.example.ironpath.domain.planner

import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.testutil.FakeTimeProvider
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutDateRulesTest {

    private fun workout(
        id: String,
        scheduledDate: String,
        dayOfWeek: Int = 1,
        status: WorkoutStatus = WorkoutStatus.Upcoming,
    ) =
        PlannedWorkout(
            id = id,
            weeklyPlanId = "plan-1",
            dayOfWeek = dayOfWeek,
            scheduledDate = scheduledDate,
            title = id,
            status = status,
        )

    @Test
    fun `today ignores invalid completed skipped and past workouts`() {
        val today = LocalDate.parse("2026-07-16")
        val expected = workout("today", today.toString(), dayOfWeek = 4)
        val workouts =
            listOf(
                workout("invalid", "not-a-date"),
                workout("completed", today.toString(), status = WorkoutStatus.Completed),
                workout("skipped", today.toString(), status = WorkoutStatus.Skipped),
                workout("past", today.minusDays(1).toString()),
                expected,
            )

        assertEquals(expected, workouts.findWorkoutScheduledToday(today))
    }

    @Test
    fun `next upcoming sorts by scheduled date then day of week`() {
        val today = LocalDate.parse("2026-07-16")
        val laterDay = workout("later-day", "2026-07-18", dayOfWeek = 6)
        val tieSecond = workout("tie-second", "2026-07-17", dayOfWeek = 5)
        val expected = workout("tie-first", "2026-07-17", dayOfWeek = 2)

        assertEquals(
            expected,
            listOf(laterDay, tieSecond, expected).findNextUpcomingWorkout(today),
        )
    }

    @Test
    fun `same weekday next week is not today`() {
        val today = LocalDate.parse("2026-07-16")
        val nextWeek =
            workout(
                id = "next-week",
                scheduledDate = today.plusWeeks(1).toString(),
                dayOfWeek = today.dayOfWeek.value,
            )

        assertNull(listOf(nextWeek).findWorkoutScheduledToday(today))
        assertEquals(nextWeek, listOf(nextWeek).findNextUpcomingWorkout(today))
    }

    @Test
    fun `year boundary returns January workout after December`() {
        val today = LocalDate.parse("2026-12-31")
        val januaryWorkout = workout("new-year", "2027-01-01", dayOfWeek = 5)

        assertEquals(
            januaryWorkout,
            listOf(
                    workout("past", "2026-12-30", dayOfWeek = 3),
                    januaryWorkout,
                )
                .findNextUpcomingWorkout(today),
        )
    }

    @Test
    fun `DST transition dates remain calendar-date based in America Vancouver`() {
        val timeProvider =
            FakeTimeProvider(
                instant = Instant.parse("2026-03-08T09:30:00Z"),
                zoneId = ZoneId.of("America/Vancouver"),
            )
        val workout = workout("dst-day", "2026-03-08", dayOfWeek = 7)

        assertEquals(workout, listOf(workout).findWorkoutScheduledToday(timeProvider.today()))

        timeProvider.advanceBy(Duration.ofHours(2))
        assertEquals(LocalDate.parse("2026-03-08"), timeProvider.today())
        assertEquals(workout, listOf(workout).findWorkoutScheduledToday(timeProvider.today()))
    }

    @Test
    fun `empty list returns null for today and next`() {
        val today = LocalDate.parse("2026-07-16")

        assertNull(emptyList<PlannedWorkout>().findWorkoutScheduledToday(today))
        assertNull(emptyList<PlannedWorkout>().findNextUpcomingWorkout(today))
    }
}
