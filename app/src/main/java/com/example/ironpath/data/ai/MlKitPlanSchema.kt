package com.example.ironpath.data.ai

import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide

@Generable(description = "A safe one-week strength-training plan")
data class MlKitPlanResponse(
    @param:Guide(description = "A concise explanation of the plan") val rationale: String?,
    @param:Guide(description = "Zero to five concise safety notes", maxItems = 5)
    val warnings: List<String>,
    @param:Guide(
        description = "One workout for each requested training day",
        minItems = 1,
        maxItems = 6,
    )
    val workouts: List<MlKitWorkoutResponse>,
)

@Generable(description = "One workout in the requested week")
data class MlKitWorkoutResponse(
    @param:Guide(
        description = "ISO day of week where Monday is 1",
        minimum = 1.0,
        maximum = 7.0,
    )
    val dayOfWeek: Int,
    @param:Guide(description = "A short workout title") val title: String,
    @param:Guide(description = "The ordered exercise prescriptions", minItems = 1, maxItems = 8)
    val exercises: List<MlKitExerciseResponse>,
)

@Generable(description = "One exercise prescription using an eligible catalog ID")
data class MlKitExerciseResponse(
    @param:Guide(description = "An exact exercise catalog ID supplied in the prompt")
    val catalogId: String,
    @param:Guide(description = "Number of working sets", minimum = 1.0, maximum = 6.0)
    val sets: Int,
    @param:Guide(description = "Repetitions per set", minimum = 1.0, maximum = 30.0) val reps: Int,
    @param:Guide(description = "Target load in kilograms", minimum = 0.0, maximum = 300.0)
    val targetWeightKg: Double,
)
