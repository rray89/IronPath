package com.example.ironpath.data.ai

import com.example.ironpath.domain.planner.OnDeviceExerciseProposal
import com.example.ironpath.domain.planner.OnDeviceModelClient
import com.example.ironpath.domain.planner.OnDeviceModelGeneration
import com.example.ironpath.domain.planner.OnDeviceModelPrompt
import com.example.ironpath.domain.planner.OnDeviceModelStatus
import com.example.ironpath.domain.planner.OnDevicePlanProposal
import com.example.ironpath.domain.planner.OnDeviceWorkoutProposal
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generateTypedContentRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class MlKitOnDeviceModelClient @Inject constructor() : OnDeviceModelClient {
    override suspend fun checkStatus(): OnDeviceModelStatus {
        var model: GenerativeModel? = null
        return try {
            model = Generation.getClient()
            val status = model.checkStatus().toOnDeviceStatus()
            if (
                status == OnDeviceModelStatus.AVAILABLE &&
                    !model.isStructuredOutputFeatureAvailable()
            ) {
                OnDeviceModelStatus.UNAVAILABLE
            } else {
                status
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            OnDeviceModelStatus.UNAVAILABLE
        } finally {
            model?.close()
        }
    }

    override suspend fun generate(prompt: OnDeviceModelPrompt): OnDeviceModelGeneration {
        val model = Generation.getClient()
        return try {
            val request =
                generateContentRequest(
                    SystemInstruction(prompt.systemInstruction),
                    TextPart(prompt.userPrompt),
                ) {
                    temperature = TEMPERATURE
                    candidateCount = 1
                    maxOutputTokens = MAX_OUTPUT_TOKENS
                }
            val response =
                model.generateContent(
                    generateTypedContentRequest(
                        generateContentRequest = request,
                        outputClass = MlKitPlanResponse::class,
                        includeSchemaInPrompt = true,
                    )
                )
            response.candidates.firstOrNull()?.response?.let { candidate ->
                OnDeviceModelGeneration.Success(candidate.toProposal())
            } ?: OnDeviceModelGeneration.MalformedOutput
        } finally {
            model.close()
        }
    }

    private fun Int.toOnDeviceStatus(): OnDeviceModelStatus =
        when (this) {
            FeatureStatus.AVAILABLE -> OnDeviceModelStatus.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> OnDeviceModelStatus.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> OnDeviceModelStatus.DOWNLOADING
            else -> OnDeviceModelStatus.UNAVAILABLE
        }

    private companion object {
        const val TEMPERATURE = 0.2f
        const val MAX_OUTPUT_TOKENS = 2_048
    }
}

internal fun MlKitPlanResponse.toProposal() =
    OnDevicePlanProposal(
        rationale = rationale,
        warnings = warnings,
        workouts =
            workouts.map { workout ->
                OnDeviceWorkoutProposal(
                    dayOfWeek = workout.dayOfWeek,
                    title = workout.title,
                    exercises =
                        workout.exercises.map { exercise ->
                            OnDeviceExerciseProposal(
                                catalogId = exercise.catalogId,
                                sets = exercise.sets,
                                reps = exercise.reps,
                                targetWeightKg = exercise.targetWeightKg,
                            )
                        },
                )
            },
    )
