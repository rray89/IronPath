package com.example.ironpath.domain.planner

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugPlanningEngineRegistryTest {

    @Test
    fun `debug registry surfaces debug providers and excludes release providers`() {
        val ruleBasedEngine =
            RuleBasedPlanningEngine(RuleBasedPlanFactory(DefaultExerciseCatalog()))
        val fakeDebugEngine =
            object : PlanningEngine {
                override val type = PlanningEngineType.DEBUG_FAKE_AI

                override suspend fun generate(request: PlanningRequest): PlanningResult =
                    PlanningResult.Failure(PlanningFailure.Unavailable)
            }
        val registry =
            PlanningEngineRegistry(
                mapOf(
                    PlanningEngineType.RULE_BASED to ruleBasedEngine,
                    PlanningEngineType.DEBUG_FAKE_AI to fakeDebugEngine,
                )
            )

        val debugRegistry = DebugPlanningEngineRegistry(registry)

        assertEquals(setOf(PlanningEngineType.DEBUG_FAKE_AI), debugRegistry.availableTypes)
    }
}
