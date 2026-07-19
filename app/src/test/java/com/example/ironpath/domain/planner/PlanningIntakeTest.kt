package com.example.ironpath.domain.planner

import com.example.ironpath.testutil.FakeTimeProvider
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningIntakeTest {
    private val catalog = DefaultExerciseCatalog()
    private val engine =
        RuleBasedPlanningEngine(
            RuleBasedPlanFactory(catalog),
            PlanValidator(catalog, FakeTimeProvider()),
        )

    @Test
    fun `planning goals expose the five canonical values and stable user facing labels`() {
        assertEquals(
            listOf(
                PlanningGoal.STRENGTH,
                PlanningGoal.HYPERTROPHY,
                PlanningGoal.GENERAL_FITNESS,
                PlanningGoal.RETURN_TO_ROUTINE,
                PlanningGoal.MAINTENANCE,
            ),
            PlanningGoal.entries,
        )
        assertEquals("Return to routine", PlanningGoal.RETURN_TO_ROUTINE.displayLabel)
        assertEquals("return-to-routine", PlanningGoal.RETURN_TO_ROUTINE.slug)
    }

    @Test
    fun `every planning goal has an explicit nonempty rule based strategy`() = runTest {
        val firstWorkouts =
            PlanningGoal.entries.associateWith { goal ->
                val result =
                    engine.generate(
                        PlanningRequest(
                            targetWeekStart = LocalDate.parse("2026-07-20"),
                            intake = PlanningIntake(goal = goal, selectedDays = setOf(1)),
                        )
                    ) as PlanningResult.Success
                result.draft.workouts.single()
            }

        assertEquals(PlanningGoal.entries.size, firstWorkouts.values.map { it.title }.toSet().size)
        assertTrue(firstWorkouts.values.all { it.exercises.isNotEmpty() })
        assertTrue(
            firstWorkouts.getValue(PlanningGoal.MAINTENANCE).exercises.sumOf { it.sets } <
                firstWorkouts.getValue(PlanningGoal.HYPERTROPHY).exercises.sumOf { it.sets }
        )
        assertNotEquals(
            firstWorkouts.getValue(PlanningGoal.GENERAL_FITNESS).title,
            firstWorkouts.getValue(PlanningGoal.MAINTENANCE).title,
        )
    }

    @Test
    fun `rule based engine rejects more than six selected days`() = runTest {
        val result =
            engine.generate(
                PlanningRequest(
                    targetWeekStart = LocalDate.parse("2026-07-20"),
                    intake =
                        PlanningIntake(
                            goal = PlanningGoal.GENERAL_FITNESS,
                            selectedDays = (1..7).toSet(),
                        ),
                )
            )

        assertTrue(result is PlanningResult.Failure)
        val failure = (result as PlanningResult.Failure).reason as PlanningFailure.InvalidRequest
        assertTrue("Choose no more than six training days" in failure.violations)
    }

    @Test
    fun `catalog reverse lookup normalizes case and surrounding whitespace`() {
        val entry = catalog.findByNormalizedName("  BARBELL BENCH PRESS  ")

        assertEquals(ExerciseCatalogIds.BARBELL_BENCH_PRESS, entry?.id)
        assertNull(catalog.findByNormalizedName("Unknown free text movement"))
    }
}
