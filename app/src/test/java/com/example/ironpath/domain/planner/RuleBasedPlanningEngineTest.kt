package com.example.ironpath.domain.planner

import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedPlanningEngineTest {

    private val catalog = DefaultExerciseCatalog()
    private val engine = RuleBasedPlanningEngine(RuleBasedPlanFactory(catalog))

    @Test
    fun `generate returns a catalog backed draft without Room entities`() = runTest {
        val targetWeek = LocalDate.parse("2026-07-20")

        val result =
            engine.generate(
                PlanningRequest(
                    targetWeekStart = targetWeek,
                    goal = PlanningGoal.STRENGTH,
                    selectedDays = setOf(1, 3, 5),
                )
            )

        assertTrue(result is PlanningResult.Success)
        val draft = (result as PlanningResult.Success).draft
        assertEquals(targetWeek, draft.targetWeekStart)
        assertEquals(listOf(1, 3, 5), draft.workouts.map { it.dayOfWeek })
        assertEquals(
            listOf(targetWeek, targetWeek.plusDays(2), targetWeek.plusDays(4)),
            draft.workouts.map { it.scheduledDate },
        )
        assertTrue(
            draft.workouts
                .flatMap { it.exercises }
                .all { exercise -> catalog.find(exercise.catalogId) != null }
        )
        assertEquals(PlanningEngineType.RULE_BASED, draft.providerMetadata.engineType)
        assertTrue(draft.providerMetadata.generationDurationMillis >= 0)
    }

    @Test
    fun `generate rejects an empty training day request with typed failure`() = runTest {
        val result =
            engine.generate(
                PlanningRequest(
                    targetWeekStart = LocalDate.parse("2026-07-20"),
                    goal = PlanningGoal.HYPERTROPHY,
                    selectedDays = emptySet(),
                )
            )

        assertTrue(result is PlanningResult.Failure)
        assertTrue((result as PlanningResult.Failure).reason is PlanningFailure.InvalidRequest)
    }

    @Test
    fun `generate rejects a target week that does not start on Monday`() = runTest {
        val result =
            engine.generate(
                PlanningRequest(
                    targetWeekStart = LocalDate.parse("2026-07-21"),
                    goal = PlanningGoal.STRENGTH,
                    selectedDays = setOf(1),
                )
            )

        assertTrue(result is PlanningResult.Failure)
        val failure = (result as PlanningResult.Failure).reason as PlanningFailure.InvalidRequest
        assertTrue("Target week must start on Monday" in failure.violations)
    }

    @Test
    fun `generate rejects training days outside ISO range`() = runTest {
        val result =
            engine.generate(
                PlanningRequest(
                    targetWeekStart = LocalDate.parse("2026-07-20"),
                    goal = PlanningGoal.STRENGTH,
                    selectedDays = setOf(0, 8),
                )
            )

        assertTrue(result is PlanningResult.Failure)
        val failure = (result as PlanningResult.Failure).reason as PlanningFailure.InvalidRequest
        assertTrue("Training days must use ISO values 1 through 7" in failure.violations)
    }

    @Test
    fun `registry exposes the rule based engine by stable type`() {
        val registry = PlanningEngineRegistry(mapOf(PlanningEngineType.RULE_BASED to engine))

        assertEquals(setOf(PlanningEngineType.RULE_BASED), registry.availableTypes)
        assertEquals(engine, registry.require(PlanningEngineType.RULE_BASED))
    }

    @Test
    fun `registry rejects a map key that disagrees with the engine type`() {
        assertThrows(IllegalStateException::class.java) {
            PlanningEngineRegistry(mapOf(PlanningEngineType.ON_DEVICE_AI to engine))
        }
    }

    @Test
    fun `factory fails fast when a rule template drifts from the catalog`() {
        val incompleteCatalog =
            object : ExerciseCatalog {
                override val entries = catalog.entries.drop(1)

                override fun find(id: ExerciseCatalogId): ExerciseCatalogEntry? =
                    entries.firstOrNull { it.id == id }
            }

        assertThrows(IllegalStateException::class.java) {
            RuleBasedPlanFactory(incompleteCatalog)
                .create(
                    request =
                        PlanningRequest(
                            targetWeekStart = LocalDate.parse("2026-07-20"),
                            goal = PlanningGoal.STRENGTH,
                            selectedDays = setOf(1),
                        ),
                    providerMetadata =
                        PlanningProviderMetadata(
                            engineType = PlanningEngineType.RULE_BASED,
                            generationDurationMillis = 1,
                        ),
                )
        }
    }
}
