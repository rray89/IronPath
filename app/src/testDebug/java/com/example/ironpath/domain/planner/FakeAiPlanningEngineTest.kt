package com.example.ironpath.domain.planner

import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeAiPlanningEngineTest {
    private val catalog = DefaultExerciseCatalog()
    private val engine = FakeAiPlanningEngine(catalog)

    @Test
    fun `every debug goal seeds target loads that can be accepted without editing`() = runTest {
        PlanningGoal.entries.forEach { goal ->
            val result =
                engine.generate(
                    PlanningRequest(
                        targetWeekStart = LocalDate.parse("2026-07-20"),
                        intake = PlanningIntake(goal = goal, selectedDays = setOf(1, 3, 5)),
                    )
                )

            assertTrue(
                "Expected debug success for $goal but was $result",
                result is PlanningResult.Success,
            )
            (result as PlanningResult.Success)
                .draft
                .workouts
                .flatMap(WorkoutDraft::exercises)
                .forEach { exercise ->
                    val entry = catalog.require(exercise.catalogId)
                    assertTrue(
                        "Missing target load for ${entry.displayName} in $goal",
                        !entry.requiresTargetLoad() || exercise.targetWeightKg > 0.0,
                    )
                }
        }
    }
}
