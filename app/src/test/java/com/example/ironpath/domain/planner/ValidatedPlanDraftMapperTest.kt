package com.example.ironpath.domain.planner

import com.example.ironpath.testutil.FakeIdProvider
import com.example.ironpath.testutil.FakeTimeProvider
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ValidatedPlanDraftMapperTest {
    private val timeProvider = FakeTimeProvider()
    private val catalog = DefaultExerciseCatalog()
    private val validator = PlanValidator(catalog, timeProvider)

    @Test
    fun `mapper preserves dates catalog names parent ids and dense exercise order`() {
        val targetMonday = LocalDate.parse("2026-07-20")
        val draft =
            PlanDraft(
                targetWeekStart = targetMonday,
                workouts =
                    listOf(
                        WorkoutDraft(
                            dayOfWeek = 1,
                            scheduledDate = targetMonday,
                            title = "Push",
                            exercises =
                                listOf(
                                    ExerciseDraft(
                                        ExerciseCatalogIds.PUSH_UPS,
                                        sets = 3,
                                        reps = 12,
                                        targetWeightKg = 0.0,
                                    ),
                                    ExerciseDraft(
                                        ExerciseCatalogIds.HAMMER_CURLS,
                                        sets = 4,
                                        reps = 8,
                                        targetWeightKg = 12.5,
                                    ),
                                ),
                        ),
                        WorkoutDraft(
                            dayOfWeek = 4,
                            scheduledDate = targetMonday.plusDays(3),
                            title = "Legs",
                            exercises =
                                listOf(
                                    ExerciseDraft(
                                        ExerciseCatalogIds.GOBLET_SQUATS,
                                        sets = 3,
                                        reps = 10,
                                        targetWeightKg = 20.0,
                                    )
                                ),
                        ),
                    ),
                providerMetadata =
                    PlanningProviderMetadata(
                        engineType = PlanningEngineType.ON_DEVICE_AI,
                        generationDurationMillis = 42,
                    ),
            )
        val validation =
            validator.validate(
                draft,
                PlanValidationContext(
                    expectedTargetWeekStart = targetMonday,
                    invokedEngineType = PlanningEngineType.ON_DEVICE_AI,
                    selectedDays = setOf(1, 4),
                    experience = TrainingExperience.INTERMEDIATE,
                    availableEquipment = Equipment.entries.toSet(),
                ),
            ) as PlanValidationResult.Valid
        val mapper =
            ValidatedPlanDraftMapper(PlanEntityMapper(FakeIdProvider(), timeProvider, catalog))

        val result = mapper.map(validation.validatedPlan)

        assertEquals("test-id-1", result.plan.id)
        assertEquals("2026-07-20", result.plan.startDate)
        assertEquals("2026-07-26", result.plan.endDate)
        assertEquals(timeProvider.epochMillis(), result.plan.createdAt)
        assertEquals(listOf("test-id-2", "test-id-5"), result.workouts.map { it.id })
        assertEquals(listOf(1, 4), result.workouts.map { it.dayOfWeek })
        assertEquals(
            listOf("Push-ups", "Hammer Curls", "Goblet Squats"),
            result.exercises.map { it.name }
        )
        assertEquals(
            listOf("test-id-2", "test-id-2", "test-id-5"),
            result.exercises.map { it.plannedWorkoutId }
        )
        assertEquals(listOf(0, 1, 0), result.exercises.map { it.orderIndex })
        assertEquals(listOf("test-id-3", "test-id-4", "test-id-6"), result.exercises.map { it.id })
    }
}
