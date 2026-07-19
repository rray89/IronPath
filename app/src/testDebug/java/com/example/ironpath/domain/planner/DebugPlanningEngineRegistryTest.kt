package com.example.ironpath.domain.planner

import com.example.ironpath.domain.time.TimeProvider
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugPlanningEngineRegistryTest {

    @Test
    fun `debug registry surfaces debug providers and excludes release providers`() {
        val catalog = DefaultExerciseCatalog()
        val timeProvider =
            object : TimeProvider {
                override val zoneId: ZoneId = ZoneId.of("America/Vancouver")

                override fun now(): Instant = Instant.parse("2026-07-16T19:00:00Z")
            }
        val ruleBasedEngine =
            RuleBasedPlanningEngine(
                RuleBasedPlanFactory(catalog),
                PlanValidator(catalog, timeProvider),
            )
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
