package com.example.ironpath.domain.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MainAiPlanningChainTest {
    @Test
    fun `production candidates contain no debug-only engines`() {
        assertFalse(MainAiPlanningChain.candidates.any { it.type.debugOnly })
        assertEquals(
            listOf(PlanningEngineType.ON_DEVICE_AI, PlanningEngineType.RULE_BASED),
            MainAiPlanningChain.candidates.sortedBy(AiPlanningCandidate::priority).map { it.type },
        )
    }
}
