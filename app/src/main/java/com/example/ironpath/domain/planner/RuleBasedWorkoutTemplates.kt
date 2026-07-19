package com.example.ironpath.domain.planner

internal data class RuleBasedExerciseTemplate(
    val catalogId: ExerciseCatalogId,
    val sets: Int,
    val reps: Int,
    val weightKg: Double,
)

internal data class RuleBasedWorkoutTemplate(
    val title: String,
    val exercises: List<RuleBasedExerciseTemplate>,
)

internal object RuleBasedWorkoutTemplates {
    val allExerciseIds: Set<ExerciseCatalogId> =
        listOf(strength, hypertrophy, endurance, returnToRoutine)
            .flatten()
            .flatMap(RuleBasedWorkoutTemplate::exercises)
            .map(RuleBasedExerciseTemplate::catalogId)
            .toSet()

    fun forGoal(goal: TrainingGoal, dayCount: Int): List<RuleBasedWorkoutTemplate> {
        val pool =
            when (goal) {
                TrainingGoal.Strength -> strength
                TrainingGoal.Hypertrophy -> hypertrophy
                TrainingGoal.Endurance -> endurance
                TrainingGoal.Rehab -> returnToRoutine
            }
        return List(dayCount) { pool[it % pool.size] }
    }
}

private fun exercise(
    catalogId: ExerciseCatalogId,
    sets: Int,
    reps: Int,
    weightKg: Double,
) = RuleBasedExerciseTemplate(catalogId, sets, reps, weightKg)

private val strength =
    listOf(
        RuleBasedWorkoutTemplate(
            "Push A",
            listOf(
                exercise(ExerciseCatalogIds.BARBELL_BENCH_PRESS, 5, 5, 60.0),
                exercise(ExerciseCatalogIds.OVERHEAD_PRESS, 4, 6, 40.0),
                exercise(ExerciseCatalogIds.TRICEP_DIPS, 3, 8, 0.0),
            ),
        ),
        RuleBasedWorkoutTemplate(
            "Pull A",
            listOf(
                exercise(ExerciseCatalogIds.BARBELL_ROWS, 5, 5, 60.0),
                exercise(ExerciseCatalogIds.WEIGHTED_PULL_UPS, 4, 6, 10.0),
                exercise(ExerciseCatalogIds.BARBELL_CURLS, 3, 8, 25.0),
            ),
        ),
        RuleBasedWorkoutTemplate(
            "Legs",
            listOf(
                exercise(ExerciseCatalogIds.BARBELL_SQUATS, 5, 5, 80.0),
                exercise(ExerciseCatalogIds.ROMANIAN_DEADLIFT, 4, 6, 60.0),
                exercise(ExerciseCatalogIds.CALF_RAISES, 3, 12, 40.0),
            ),
        ),
        RuleBasedWorkoutTemplate(
            "Push B",
            listOf(
                exercise(ExerciseCatalogIds.INCLINE_DUMBBELL_PRESS, 4, 8, 25.0),
                exercise(ExerciseCatalogIds.DUMBBELL_LATERAL_RAISES, 3, 12, 8.0),
                exercise(ExerciseCatalogIds.TRICEP_ROPE_PUSHDOWNS, 3, 10, 20.0),
            ),
        ),
        RuleBasedWorkoutTemplate(
            "Pull B",
            listOf(
                exercise(ExerciseCatalogIds.DEADLIFT, 3, 5, 100.0),
                exercise(ExerciseCatalogIds.LAT_PULLDOWNS, 4, 8, 50.0),
                exercise(ExerciseCatalogIds.FACE_PULLS, 3, 15, 15.0),
            ),
        ),
    )

private val hypertrophy =
    listOf(
        RuleBasedWorkoutTemplate(
            "Chest/Tris",
            listOf(
                exercise(ExerciseCatalogIds.BARBELL_BENCH_PRESS, 4, 10, 50.0),
                exercise(ExerciseCatalogIds.DUMBBELL_INCLINE_FLYS, 3, 12, 12.0),
                exercise(ExerciseCatalogIds.TRICEP_ROPE_PUSHDOWNS, 3, 12, 15.0),
            ),
        ),
        RuleBasedWorkoutTemplate(
            "Back/Bis",
            listOf(
                exercise(ExerciseCatalogIds.BARBELL_ROWS, 4, 10, 50.0),
                exercise(ExerciseCatalogIds.LAT_PULLDOWNS, 3, 12, 45.0),
                exercise(ExerciseCatalogIds.BARBELL_CURLS, 3, 12, 20.0),
            ),
        ),
        RuleBasedWorkoutTemplate(
            "Legs",
            listOf(
                exercise(ExerciseCatalogIds.BARBELL_SQUATS, 4, 10, 60.0),
                exercise(ExerciseCatalogIds.LEG_PRESS, 3, 12, 100.0),
                exercise(ExerciseCatalogIds.CALF_RAISES, 4, 15, 30.0),
            ),
        ),
        RuleBasedWorkoutTemplate(
            "Shoulders/Arms",
            listOf(
                exercise(ExerciseCatalogIds.OVERHEAD_PRESS, 4, 10, 30.0),
                exercise(ExerciseCatalogIds.DUMBBELL_LATERAL_RAISES, 3, 15, 8.0),
                exercise(ExerciseCatalogIds.HAMMER_CURLS, 3, 12, 12.0),
            ),
        ),
    )

private val endurance =
    listOf(
        RuleBasedWorkoutTemplate(
            "Upper Circuit",
            listOf(
                exercise(ExerciseCatalogIds.PUSH_UPS, 3, 20, 0.0),
                exercise(ExerciseCatalogIds.DUMBBELL_ROWS, 3, 15, 12.0),
                exercise(ExerciseCatalogIds.SHOULDER_PRESS, 3, 15, 10.0),
            ),
        ),
        RuleBasedWorkoutTemplate(
            "Lower Circuit",
            listOf(
                exercise(ExerciseCatalogIds.BODYWEIGHT_SQUATS, 3, 25, 0.0),
                exercise(ExerciseCatalogIds.WALKING_LUNGES, 3, 20, 0.0),
                exercise(ExerciseCatalogIds.CALF_RAISES, 3, 20, 0.0),
            ),
        ),
        RuleBasedWorkoutTemplate(
            "Full Body",
            listOf(
                exercise(ExerciseCatalogIds.KETTLEBELL_SWINGS, 3, 20, 16.0),
                exercise(ExerciseCatalogIds.BURPEES, 3, 15, 0.0),
                exercise(ExerciseCatalogIds.PLANK_HOLD, 3, 1, 0.0),
            ),
        ),
    )

private val returnToRoutine =
    listOf(
        RuleBasedWorkoutTemplate(
            "Upper Mobility",
            listOf(
                exercise(ExerciseCatalogIds.BAND_PULL_APARTS, 3, 15, 0.0),
                exercise(ExerciseCatalogIds.WALL_SLIDES, 3, 12, 0.0),
                exercise(ExerciseCatalogIds.LIGHT_DUMBBELL_PRESS, 3, 12, 5.0),
            ),
        ),
        RuleBasedWorkoutTemplate(
            "Lower Mobility",
            listOf(
                exercise(ExerciseCatalogIds.GOBLET_SQUATS, 3, 12, 8.0),
                exercise(ExerciseCatalogIds.GLUTE_BRIDGES, 3, 15, 0.0),
                exercise(ExerciseCatalogIds.CALF_RAISES, 3, 15, 0.0),
            ),
        ),
    )
