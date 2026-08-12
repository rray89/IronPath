package com.example.ironpath.data.backup

import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupBundleValidatorTest {
    @Test
    fun validate_rejectsInvalidDateWeekdayAndNumberValues() {
        val valid = representativeBundle()

        assertThrows(IllegalArgumentException::class.java) {
            BackupBundleValidator.validate(
                valid.copy(
                    weeklyPlans = listOf(valid.weeklyPlans.single().copy(startDate = "2026-99-40"))
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupBundleValidator.validate(
                valid.copy(
                    plannedWorkouts = listOf(valid.plannedWorkouts.single().copy(dayOfWeek = 8))
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupBundleValidator.validate(
                valid.copy(
                    plannedExercises = listOf(valid.plannedExercises.single().copy(weightKg = -0.5))
                )
            )
        }
    }

    @Test
    fun validate_rejectsBlankIdentifiersAndBrokenPlanDateRanges() {
        val valid = representativeBundle()

        assertThrows(IllegalArgumentException::class.java) {
            BackupBundleValidator.validate(
                valid.copy(weeklyPlans = listOf(valid.weeklyPlans.single().copy(id = "")))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupBundleValidator.validate(
                valid.copy(
                    weeklyPlans =
                        listOf(
                            valid.weeklyPlans
                                .single()
                                .copy(startDate = "2026-07-14", endDate = "2026-07-20")
                        )
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupBundleValidator.validate(
                valid.copy(
                    weeklyPlans = listOf(valid.weeklyPlans.single().copy(endDate = "2026-07-26")),
                    plannedWorkouts =
                        listOf(valid.plannedWorkouts.single().copy(scheduledDate = "2026-07-20")),
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupBundleValidator.validate(
                valid.copy(
                    weeklyPlans =
                        listOf(
                            valid.weeklyPlans
                                .single()
                                .copy(
                                    startDate = "2026-07-20",
                                    endDate = "2026-07-19",
                                )
                        )
                )
            )
        }
    }

    @Test
    fun validate_rejectsCrossEntityScheduleAndSiblingOrderViolations() {
        val valid = representativeBundle()
        val workout = valid.plannedWorkouts.single()
        val exercise = valid.plannedExercises.single()

        assertThrows(IllegalArgumentException::class.java) {
            BackupBundleValidator.validate(
                valid.copy(plannedWorkouts = listOf(workout.copy(scheduledDate = "2026-07-20")))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupBundleValidator.validate(
                valid.copy(plannedWorkouts = listOf(workout.copy(scheduledDate = "2026-07-14")))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupBundleValidator.validate(
                valid.copy(plannedWorkouts = listOf(workout, workout.copy(id = "workout-b")))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupBundleValidator.validate(
                valid.copy(plannedExercises = listOf(exercise, exercise.copy(id = "exercise-b")))
            )
        }
    }

    @Test
    fun validate_rejectsDuplicateRoomRecordIdentityAndIncorrectNormalization() {
        val valid = representativeBundle()
        val record =
            PersonalRecord(
                id = "record-a",
                exerciseName = "Bench Press",
                normalizedExerciseName = "bench press",
                weightKg = 100.0,
                achievedOn = "2026-07-13",
                createdAt = 1,
            )

        assertThrows(IllegalArgumentException::class.java) {
            BackupBundleValidator.validate(
                valid.copy(
                    personalRecords = listOf(record, record.copy(id = "record-b")),
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupBundleValidator.validate(
                valid.copy(
                    personalRecords = listOf(record.copy(normalizedExerciseName = "wrong")),
                )
            )
        }
    }

    private fun representativeBundle(): BackupBundle {
        val plan =
            WeeklyPlan(
                id = "plan-a",
                startDate = "2026-07-13",
                endDate = "2026-07-19",
                createdAt = 1_700_000_000_000,
            )
        val workout =
            PlannedWorkout(
                id = "workout-a",
                weeklyPlanId = plan.id,
                dayOfWeek = 1,
                scheduledDate = plan.startDate,
                title = "Strength A",
            )
        return BackupBundle(
            localChangeRevision = 1,
            weeklyPlans = listOf(plan),
            plannedWorkouts = listOf(workout),
            plannedExercises =
                listOf(
                    PlannedExercise(
                        id = "exercise-a",
                        plannedWorkoutId = workout.id,
                        name = "Squat",
                        sets = 3,
                        reps = 5,
                        weightKg = 100.0,
                        orderIndex = 0,
                    )
                ),
            workoutLogs = emptyList(),
            loggedExercises = emptyList(),
            loggedSets = emptyList(),
            personalRecords = emptyList(),
        )
    }
}
