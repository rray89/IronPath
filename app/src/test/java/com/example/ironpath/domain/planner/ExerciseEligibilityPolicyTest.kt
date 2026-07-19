package com.example.ironpath.domain.planner

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseEligibilityPolicyTest {
    private val catalog = DefaultExerciseCatalog()
    private val policy = ExerciseEligibilityPolicy(catalog)

    @Test
    fun `evaluation reports every blocking reason for the invoked engine`() {
        val evaluation =
            policy.evaluate(
                catalog.require(ExerciseCatalogIds.WEIGHTED_PULL_UPS),
                context(
                    engineType = PlanningEngineType.ON_DEVICE_AI,
                    experience = TrainingExperience.BEGINNER,
                    equipment = setOf(Equipment.BODYWEIGHT),
                    cautions = setOf(ExerciseCautionTag.SHOULDER),
                ),
            )

        assertFalse(evaluation.isEligible)
        assertEquals(
            setOf(
                ExerciseIneligibilityReason.MISSING_EQUIPMENT,
                ExerciseIneligibilityReason.AI_NOT_ALLOWED,
                ExerciseIneligibilityReason.BEGINNER_NOT_SUITABLE,
                ExerciseIneligibilityReason.FORBIDDEN_MOVEMENT,
            ),
            evaluation.reasons,
        )
    }

    @Test
    fun `rule based invocation does not apply AI representability restriction`() {
        val evaluation =
            policy.evaluate(
                catalog.require(ExerciseCatalogIds.WEIGHTED_PULL_UPS),
                context(
                    engineType = PlanningEngineType.RULE_BASED,
                    experience = TrainingExperience.ADVANCED,
                    equipment = setOf(Equipment.PULL_UP_BAR),
                ),
            )

        assertTrue(evaluation.isEligible)
        assertTrue(evaluation.reasons.isEmpty())
    }

    @Test
    fun `eligible entries applies equipment experience and caution constraints together`() {
        val entries =
            policy.eligibleEntries(
                context(
                    engineType = PlanningEngineType.ON_DEVICE_AI,
                    experience = TrainingExperience.BEGINNER,
                    equipment = setOf(Equipment.BODYWEIGHT),
                    cautions = setOf(ExerciseCautionTag.SHOULDER),
                )
            )

        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { it.requiredEquipment == setOf(Equipment.BODYWEIGHT) })
        assertTrue(entries.all(ExerciseCatalogEntry::beginnerSuitable))
        assertTrue(entries.all(ExerciseCatalogEntry::allowedInAiDraft))
        assertTrue(entries.none { ExerciseCautionTag.SHOULDER in it.cautionTags })
    }

    @Test
    fun `catalog explicitly classifies every exercise target load requirement`() {
        val expected =
            mapOf(
                ExerciseCatalogIds.BARBELL_BENCH_PRESS to true,
                ExerciseCatalogIds.OVERHEAD_PRESS to true,
                ExerciseCatalogIds.TRICEP_DIPS to false,
                ExerciseCatalogIds.BARBELL_ROWS to true,
                ExerciseCatalogIds.WEIGHTED_PULL_UPS to true,
                ExerciseCatalogIds.BARBELL_CURLS to true,
                ExerciseCatalogIds.BARBELL_SQUATS to true,
                ExerciseCatalogIds.ROMANIAN_DEADLIFT to true,
                ExerciseCatalogIds.CALF_RAISES to true,
                ExerciseCatalogIds.INCLINE_DUMBBELL_PRESS to true,
                ExerciseCatalogIds.DUMBBELL_LATERAL_RAISES to true,
                ExerciseCatalogIds.TRICEP_ROPE_PUSHDOWNS to true,
                ExerciseCatalogIds.DEADLIFT to true,
                ExerciseCatalogIds.LAT_PULLDOWNS to true,
                ExerciseCatalogIds.FACE_PULLS to true,
                ExerciseCatalogIds.DUMBBELL_INCLINE_FLYS to true,
                ExerciseCatalogIds.LEG_PRESS to true,
                ExerciseCatalogIds.HAMMER_CURLS to true,
                ExerciseCatalogIds.PUSH_UPS to false,
                ExerciseCatalogIds.DUMBBELL_ROWS to true,
                ExerciseCatalogIds.SHOULDER_PRESS to true,
                ExerciseCatalogIds.BODYWEIGHT_SQUATS to false,
                ExerciseCatalogIds.WALKING_LUNGES to false,
                ExerciseCatalogIds.KETTLEBELL_SWINGS to true,
                ExerciseCatalogIds.BURPEES to false,
                ExerciseCatalogIds.PLANK_HOLD to false,
                ExerciseCatalogIds.BAND_PULL_APARTS to false,
                ExerciseCatalogIds.WALL_SLIDES to false,
                ExerciseCatalogIds.LIGHT_DUMBBELL_PRESS to true,
                ExerciseCatalogIds.GOBLET_SQUATS to true,
                ExerciseCatalogIds.GLUTE_BRIDGES to false,
            )

        assertEquals(expected.keys, catalog.entries.map(ExerciseCatalogEntry::id).toSet())
        catalog.entries.forEach { entry ->
            assertEquals(entry.displayName, expected.getValue(entry.id), entry.requiresTargetLoad())
        }
    }

    private fun context(
        engineType: PlanningEngineType,
        experience: TrainingExperience,
        equipment: Set<Equipment>,
        cautions: Set<ExerciseCautionTag> = emptySet(),
    ) =
        PlanValidationContext(
            expectedTargetWeekStart = LocalDate.parse("2026-07-20"),
            invokedEngineType = engineType,
            selectedDays = setOf(1),
            experience = experience,
            availableEquipment = equipment,
            forbiddenCautionTags = cautions,
        )
}
