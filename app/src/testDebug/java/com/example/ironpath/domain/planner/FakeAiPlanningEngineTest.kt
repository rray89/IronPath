package com.example.ironpath.domain.planner

import com.example.ironpath.testutil.FakeTimeProvider
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeAiPlanningEngineTest {
    private val catalog = DefaultExerciseCatalog()
    private val engine = FakeAiPlanningEngine(catalog)

    @Test
    fun `fake engine creates a deterministic validator compatible draft`() = runTest {
        val request =
            PlanningRequest(
                targetWeekStart = LocalDate.parse("2026-07-20"),
                intake =
                    PlanningIntake(
                        goal = PlanningGoal.GENERAL_FITNESS,
                        selectedDays = setOf(1, 3, 5),
                    ),
            )

        val result = engine.generate(request) as PlanningResult.Success
        val validation =
            PlanValidator(catalog, FakeTimeProvider())
                .validate(
                    result.draft,
                    PlanValidationContext(
                        expectedTargetWeekStart = request.targetWeekStart,
                        invokedEngineType = PlanningEngineType.DEBUG_FAKE_AI,
                        selectedDays = request.selectedDays,
                        experience = request.intake.experience,
                        availableEquipment = request.intake.availableEquipment,
                        forbiddenCautionTags = request.intake.forbiddenCautionTags,
                    ),
                )

        assertTrue(validation is PlanValidationResult.Valid)
        assertEquals(PlanningEngineType.DEBUG_FAKE_AI, result.draft.providerMetadata.engineType)
        assertEquals(listOf(1, 3, 5), result.draft.workouts.map { it.dayOfWeek })
        assertEquals("A balanced draft based on your structured intake.", result.draft.rationale)
    }

    @Test
    fun `fake engine honors beginner equipment and caution constraints`() = runTest {
        val request =
            PlanningRequest(
                targetWeekStart = LocalDate.parse("2026-07-20"),
                intake =
                    PlanningIntake(
                        goal = PlanningGoal.RETURN_TO_ROUTINE,
                        selectedDays = setOf(1, 3),
                        experience = TrainingExperience.BEGINNER,
                        availableEquipment = setOf(Equipment.BODYWEIGHT),
                        forbiddenCautionTags = setOf(ExerciseCautionTag.HIGH_IMPACT),
                    ),
            )

        val result = engine.generate(request) as PlanningResult.Success
        val entries =
            result.draft.workouts.flatMap(WorkoutDraft::exercises).map {
                catalog.require(it.catalogId)
            }

        assertTrue(entries.all { it.beginnerSuitable })
        assertTrue(
            entries.all { it.requiredEquipment.all(request.intake.availableEquipment::contains) }
        )
        assertTrue(entries.none { ExerciseCautionTag.HIGH_IMPACT in it.cautionTags })
    }

    @Test
    fun `fake engine returns typed failure when intake has no feasible catalog entries`() =
        runTest {
            val result =
                engine.generate(
                    PlanningRequest(
                        targetWeekStart = LocalDate.parse("2026-07-20"),
                        intake =
                            PlanningIntake(
                                goal = PlanningGoal.STRENGTH,
                                selectedDays = setOf(1),
                                availableEquipment = emptySet(),
                            ),
                    )
                )

            assertTrue(result is PlanningResult.Failure)
            assertTrue((result as PlanningResult.Failure).reason is PlanningFailure.InvalidRequest)
        }
}
