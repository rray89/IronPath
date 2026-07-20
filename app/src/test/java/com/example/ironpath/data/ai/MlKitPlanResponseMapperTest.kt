package com.example.ironpath.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class MlKitPlanResponseMapperTest {
    @Test
    fun `typed response maps every nested field into the owned proposal`() {
        val response =
            MlKitPlanResponse(
                rationale = "Keep the first week conservative.",
                warnings = listOf("Stop if pain increases."),
                workouts =
                    listOf(
                        MlKitWorkoutResponse(
                            dayOfWeek = 3,
                            title = "Midweek Strength",
                            exercises =
                                listOf(
                                    MlKitExerciseResponse(
                                        catalogId = "goblet-squat",
                                        sets = 3,
                                        reps = 8,
                                        targetWeightKg = 18.0,
                                    )
                                ),
                        )
                    ),
            )

        val proposal = response.toProposal()

        assertEquals(response.rationale, proposal.rationale)
        assertEquals(response.warnings, proposal.warnings)
        assertEquals(1, proposal.workouts.size)
        assertEquals(3, proposal.workouts.single().dayOfWeek)
        assertEquals("Midweek Strength", proposal.workouts.single().title)
        assertEquals("goblet-squat", proposal.workouts.single().exercises.single().catalogId)
        assertEquals(3, proposal.workouts.single().exercises.single().sets)
        assertEquals(8, proposal.workouts.single().exercises.single().reps)
        assertEquals(18.0, proposal.workouts.single().exercises.single().targetWeightKg, 0.0)
    }
}
