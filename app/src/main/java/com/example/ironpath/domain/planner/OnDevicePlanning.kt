package com.example.ironpath.domain.planner

enum class OnDeviceModelStatus {
    AVAILABLE,
    DOWNLOADABLE,
    DOWNLOADING,
    UNAVAILABLE,
}

data class OnDeviceModelPrompt(
    val systemInstruction: String,
    val userPrompt: String,
)

data class OnDevicePlanProposal(
    val rationale: String?,
    val warnings: List<String>,
    val workouts: List<OnDeviceWorkoutProposal>,
)

data class OnDeviceWorkoutProposal(
    val dayOfWeek: Int,
    val title: String,
    val exercises: List<OnDeviceExerciseProposal>,
)

data class OnDeviceExerciseProposal(
    val catalogId: String,
    val sets: Int,
    val reps: Int,
    val targetWeightKg: Double,
)

sealed interface OnDeviceModelGeneration {
    data class Success(val proposal: OnDevicePlanProposal) : OnDeviceModelGeneration

    data object MalformedOutput : OnDeviceModelGeneration
}

interface OnDeviceModelClient {
    suspend fun checkStatus(): OnDeviceModelStatus

    suspend fun generate(prompt: OnDeviceModelPrompt): OnDeviceModelGeneration
}
