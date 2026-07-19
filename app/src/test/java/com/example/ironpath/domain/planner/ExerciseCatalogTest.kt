package com.example.ironpath.domain.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseCatalogTest {

    private val catalog = DefaultExerciseCatalog()

    @Test
    fun `catalog exposes unique stable ids for every rule based exercise`() {
        val entries = catalog.entries

        assertEquals(31, entries.size)
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertTrue(entries.all { it.id.value.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) })
        assertEquals(
            setOf(
                "band-pull-aparts",
                "barbell-bench-press",
                "barbell-curls",
                "barbell-rows",
                "barbell-squats",
                "bodyweight-squats",
                "burpees",
                "calf-raises",
                "deadlift",
                "dumbbell-incline-flys",
                "dumbbell-lateral-raises",
                "dumbbell-rows",
                "face-pulls",
                "glute-bridges",
                "goblet-squats",
                "hammer-curls",
                "incline-dumbbell-press",
                "kettlebell-swings",
                "lat-pulldowns",
                "leg-press",
                "light-dumbbell-press",
                "overhead-press",
                "plank-hold",
                "push-ups",
                "romanian-deadlift",
                "shoulder-press",
                "tricep-dips",
                "tricep-rope-pushdowns",
                "walking-lunges",
                "wall-slides",
                "weighted-pull-ups",
            ),
            entries.map { it.id.value }.toSet(),
        )
        assertEquals(
            "Barbell Bench Press",
            catalog.require(ExerciseCatalogId("barbell-bench-press")).displayName,
        )
    }

    @Test
    fun `catalog includes validation metadata at useful boundaries`() {
        val deadlift = catalog.require(ExerciseCatalogId("deadlift"))
        val bodyweightSquat = catalog.require(ExerciseCatalogId("bodyweight-squats"))

        assertEquals(PrimaryMuscleGroup.BACK, deadlift.primaryMuscleGroup)
        assertEquals(setOf(Equipment.BARBELL), deadlift.requiredEquipment)
        assertFalse(deadlift.beginnerSuitable)
        assertTrue(ExerciseCautionTag.LOWER_BACK in deadlift.cautionTags)
        assertTrue(bodyweightSquat.beginnerSuitable)
        assertEquals(setOf(Equipment.BODYWEIGHT), bodyweightSquat.requiredEquipment)
        assertTrue(bodyweightSquat.allowedInAiDraft)
    }

    @Test
    fun `catalog declares equipment and an explicit AI deny boundary`() {
        val calfRaises = catalog.require(ExerciseCatalogIds.CALF_RAISES)
        val weightedPullUps = catalog.require(ExerciseCatalogIds.WEIGHTED_PULL_UPS)

        assertTrue(catalog.entries.none { it.requiredEquipment.isEmpty() })
        assertEquals(setOf(Equipment.MACHINE), calfRaises.requiredEquipment)
        assertFalse(weightedPullUps.allowedInAiDraft)
    }

    @Test
    fun `every rule based template resolves through the catalog`() {
        RuleBasedWorkoutTemplates.allExerciseIds.forEach { id -> assertNotNull(catalog.find(id)) }
    }
}
