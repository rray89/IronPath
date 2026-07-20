package com.example.ironpath.domain.planner

import javax.inject.Inject
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

class OnDeviceAiPlanningEngine
internal constructor(
    private val modelClient: OnDeviceModelClient,
    private val promptBuilder: OnDevicePlanPromptBuilder,
    private val draftMapper: OnDevicePlanDraftMapper,
    private val planValidator: PlanValidator,
    private val timeoutMillis: Long,
) : PlanningEngine {
    @Inject
    constructor(
        modelClient: OnDeviceModelClient,
        promptBuilder: OnDevicePlanPromptBuilder,
        draftMapper: OnDevicePlanDraftMapper,
        planValidator: PlanValidator,
    ) : this(
        modelClient = modelClient,
        promptBuilder = promptBuilder,
        draftMapper = draftMapper,
        planValidator = planValidator,
        timeoutMillis = GENERATION_TIMEOUT_MILLIS,
    )

    override val type = PlanningEngineType.ON_DEVICE_AI

    override suspend fun generate(request: PlanningRequest): PlanningResult =
        try {
            withTimeoutOrNull(timeoutMillis) { generateWithinBudget(request) }
                ?: PlanningResult.Failure(PlanningFailure.Timeout)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            PlanningResult.Failure(PlanningFailure.ProviderError("On-device generation failed."))
        }

    private suspend fun generateWithinBudget(request: PlanningRequest): PlanningResult {
        if (modelClient.checkStatus() != OnDeviceModelStatus.AVAILABLE) {
            return PlanningResult.Failure(PlanningFailure.Unavailable)
        }

        val startedAt = TimeSource.Monotonic.markNow()
        return when (
            val initial = generateAndValidate(promptBuilder.build(request), request, startedAt)
        ) {
            is Attempt.Valid -> PlanningResult.Success(initial.draft)
            is Attempt.Invalid -> {
                when (
                    val repaired =
                        generateAndValidate(
                            promptBuilder.buildRepair(request, initial.violations),
                            request,
                            startedAt,
                        )
                ) {
                    is Attempt.Valid -> PlanningResult.Success(repaired.draft)
                    is Attempt.Invalid ->
                        PlanningResult.Failure(PlanningFailure.InvalidRequest(repaired.violations))
                }
            }
        }
    }

    private suspend fun generateAndValidate(
        prompt: OnDeviceModelPrompt,
        request: PlanningRequest,
        startedAt: TimeSource.Monotonic.ValueTimeMark,
    ): Attempt =
        when (val generation = modelClient.generate(prompt)) {
            OnDeviceModelGeneration.MalformedOutput ->
                Attempt.Invalid(listOf(MALFORMED_OUTPUT_MESSAGE))
            is OnDeviceModelGeneration.Success -> {
                when (
                    val mapping =
                        draftMapper.map(
                            proposal = generation.proposal,
                            request = request,
                            generationDurationMillis = startedAt.elapsedNow().inWholeMilliseconds,
                        )
                ) {
                    is OnDeviceDraftMapping.Invalid -> Attempt.Invalid(listOf(mapping.message))
                    is OnDeviceDraftMapping.Success -> {
                        when (
                            val validation =
                                planValidator.validate(
                                    mapping.draft,
                                    request.validationContext(type),
                                )
                        ) {
                            is PlanValidationResult.Valid ->
                                Attempt.Valid(validation.validatedPlan.draft)
                            is PlanValidationResult.Invalid ->
                                Attempt.Invalid(
                                    validation.violations.map(PlanViolation::message).distinct()
                                )
                        }
                    }
                }
            }
        }

    private sealed interface Attempt {
        data class Valid(val draft: PlanDraft) : Attempt

        data class Invalid(val violations: List<String>) : Attempt
    }

    private companion object {
        const val GENERATION_TIMEOUT_MILLIS = 90_000L
        const val MALFORMED_OUTPUT_MESSAGE =
            "The model response could not be read as a workout plan."
    }
}
