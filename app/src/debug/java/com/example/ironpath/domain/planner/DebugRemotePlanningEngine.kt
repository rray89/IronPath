package com.example.ironpath.domain.planner

import javax.inject.Inject
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

sealed interface RemotePlanningTransportResult {
    data class Success(val proposal: OnDevicePlanProposal) : RemotePlanningTransportResult

    data object ProviderFailure : RemotePlanningTransportResult
}

interface RemotePlanningTransport {
    suspend fun generate(
        apiKey: String,
        prompt: OnDeviceModelPrompt,
    ): RemotePlanningTransportResult
}

class DebugRemotePlanningEngine
internal constructor(
    private val experiment: RemotePlanningExperiment,
    private val transport: RemotePlanningTransport,
    private val promptBuilder: OnDevicePlanPromptBuilder,
    private val draftMapper: OnDevicePlanDraftMapper,
    private val planValidator: PlanValidator,
    private val timeoutMillis: Long,
) : PlanningEngine {
    @Inject
    constructor(
        experiment: RemotePlanningExperiment,
        transport: RemotePlanningTransport,
        promptBuilder: OnDevicePlanPromptBuilder,
        draftMapper: OnDevicePlanDraftMapper,
        planValidator: PlanValidator,
    ) : this(
        experiment = experiment,
        transport = transport,
        promptBuilder = promptBuilder,
        draftMapper = draftMapper,
        planValidator = planValidator,
        timeoutMillis = GENERATION_TIMEOUT_MILLIS,
    )

    override val type = PlanningEngineType.DEBUG_REMOTE_AI

    override suspend fun generate(request: PlanningRequest): PlanningResult {
        val experimentSnapshot = experiment.state.value
        if (!experimentSnapshot.configured) {
            return PlanningResult.Failure(PlanningFailure.Unavailable)
        }
        return try {
            withTimeoutOrNull(timeoutMillis) {
                generateWithinBudget(
                    request = request,
                    apiKey = experimentSnapshot.apiKey,
                )
            } ?: PlanningResult.Failure(PlanningFailure.Timeout)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            providerFailure()
        }
    }

    private suspend fun generateWithinBudget(
        request: PlanningRequest,
        apiKey: String,
    ): PlanningResult {
        val startedAt = TimeSource.Monotonic.markNow()
        return when (
            val transportResult =
                transport.generate(
                    apiKey = apiKey,
                    prompt = promptBuilder.build(request, type),
                )
        ) {
            RemotePlanningTransportResult.ProviderFailure -> providerFailure()
            is RemotePlanningTransportResult.Success -> {
                when (
                    val mapping =
                        draftMapper.map(
                            proposal = transportResult.proposal,
                            request = request,
                            generationDurationMillis = startedAt.elapsedNow().inWholeMilliseconds,
                            engineType = type,
                        )
                ) {
                    is OnDeviceDraftMapping.Invalid ->
                        PlanningResult.Failure(
                            PlanningFailure.InvalidRequest(listOf(mapping.message))
                        )
                    is OnDeviceDraftMapping.Success -> {
                        when (
                            val validation =
                                planValidator.validate(
                                    mapping.draft,
                                    request.validationContext(type),
                                )
                        ) {
                            is PlanValidationResult.Valid ->
                                PlanningResult.Success(validation.validatedPlan.draft)
                            is PlanValidationResult.Invalid ->
                                PlanningResult.Failure(
                                    PlanningFailure.InvalidRequest(
                                        validation.violations.map(PlanViolation::message).distinct()
                                    )
                                )
                        }
                    }
                }
            }
        }
    }

    private fun providerFailure() =
        PlanningResult.Failure(
            PlanningFailure.ProviderError("The remote planning experiment could not finish.")
        )

    private companion object {
        const val GENERATION_TIMEOUT_MILLIS = 60_000L
    }
}
