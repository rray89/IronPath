package com.example.ironpath.domain.planner

import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WorkoutStatus
import java.time.LocalDate

fun List<PlannedWorkout>.findWorkoutScheduledToday(
    today: LocalDate = LocalDate.now()
): PlannedWorkout? =
    scheduledUpcomingWorkoutsOnOrAfter(today).firstOrNull { it.date == today }?.workout

fun List<PlannedWorkout>.findNextUpcomingWorkout(
    today: LocalDate = LocalDate.now()
): PlannedWorkout? = scheduledUpcomingWorkoutsOnOrAfter(today).firstOrNull()?.workout

private fun List<PlannedWorkout>.scheduledUpcomingWorkoutsOnOrAfter(
    today: LocalDate,
): List<ScheduledWorkout> =
    mapNotNull { workout ->
            val scheduledDate =
                runCatching { LocalDate.parse(workout.scheduledDate) }.getOrNull()
                    ?: return@mapNotNull null
            ScheduledWorkout(workout = workout, date = scheduledDate)
        }
        .filter { it.workout.status == WorkoutStatus.Upcoming && !it.date.isBefore(today) }
        .sortedWith(compareBy<ScheduledWorkout> { it.date }.thenBy { it.workout.dayOfWeek })

private data class ScheduledWorkout(
    val workout: PlannedWorkout,
    val date: LocalDate,
)
