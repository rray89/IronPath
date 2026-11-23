package com.example.ironpath.domain.planner

import javax.inject.Inject
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface RemotePlanningTransportResult {
    data class Success(val proposal: OnDevicePlanProposal, val usage: PlanningTokenUsage? = null) :
        RemotePlanningTransportResult

    data class Failure(val kind: RemoteFailureKind) : RemotePlanningTransportResult
}

interface RemotePlanningTransport {
    suspend fun generate(
        apiKey: String,
        prompt: OnDeviceModelPrompt,
        optionId: String = "GEMINI",
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
        if (experimentSnapshot.enabled && !experimentSnapshot.configured) {
            return PlanningResult.Failure(
                PlanningFailure.Remote(
                    RemotePlanningRoute.fromId(experimentSnapshot.optionId)?.label ?: "Remote AI",
                    RemoteFailureKind.CONFIGURATION
                )
            )
        }
        if (!experimentSnapshot.configured) {
            return PlanningResult.Failure(PlanningFailure.Unavailable)
        }
        val provider = RemotePlanningRoute.fromId(experimentSnapshot.optionId)?.label ?: "Remote AI"
        return try {
            withTimeoutOrNull(timeoutMillis) {
                coroutineScope {
                    val invalidation = launch {
                        experiment.state.first { it != experimentSnapshot }
                        this@coroutineScope.cancel("Remote configuration changed")
                    }
                    try {
                        generateWithinBudget(request, experimentSnapshot).also {
                            if (experiment.state.value != experimentSnapshot) {
                                throw CancellationException("Remote configuration changed")
                            }
                        }
                    } finally {
                        invalidation.cancel()
                    }
                }
            } ?: PlanningResult.Failure(PlanningFailure.Remote(provider, RemoteFailureKind.TIMEOUT))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            PlanningResult.Failure(PlanningFailure.Remote(provider, RemoteFailureKind.SERVICE))
        }
    }

    private suspend fun generateWithinBudget(
        request: PlanningRequest,
        configuration: RemotePlanningExperimentState,
    ): PlanningResult {
        val route =
            RemotePlanningRoute.fromId(configuration.optionId)
                ?: return PlanningResult.Failure(
                    PlanningFailure.Remote("Remote AI", RemoteFailureKind.CONFIGURATION)
                )
        val minimalRequest =
            request.copy(
                intake =
                    request.intake.copy(
                        injuryNotes = "",
                        exercisePreferences = "",
                        exerciseDislikes = "",
                        recentTraining = RecentTrainingSummary.EMPTY,
                    )
            )
        val startedAt = TimeSource.Monotonic.markNow()
        return when (
            val transportResult =
                transport.generate(
                    apiKey = configuration.apiKey,
                    prompt = promptBuilder.build(minimalRequest, type),
                    optionId = configuration.optionId,
                )
        ) {
            is RemotePlanningTransportResult.Failure ->
                PlanningResult.Failure(PlanningFailure.Remote(route.label, transportResult.kind))
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
                            PlanningFailure.Remote(
                                route.label,
                                RemoteFailureKind.MAPPING,
                                listOf(mapping.message)
                            )
                        )
                    is OnDeviceDraftMapping.Success -> {
                        when (
                            val validation =
                                planValidator.validate(
                                    mapping.draft.copy(
                                        providerMetadata =
                                            mapping.draft.providerMetadata.copy(
                                                sourceLabel = route.option.label,
                                                remoteConfigurationRevision =
                                                    configuration.revision,
                                                tokenUsage = transportResult.usage,
                                            )
                                    ),
                                    request.validationContext(type),
                                )
                        ) {
                            is PlanValidationResult.Valid ->
                                PlanningResult.Success(validation.validatedPlan.draft)
                            is PlanValidationResult.Invalid ->
                                PlanningResult.Failure(
                                    PlanningFailure.Remote(
                                        route.label,
                                        RemoteFailureKind.VALIDATION,
                                        validation.violations
                                            .map { v ->
                                                listOfNotNull(
                                                        v.workoutDay
                                                            ?.takeIf { it in 1..7 }
                                                            ?.let {
                                                                java.time.DayOfWeek.of(it)
                                                                    .name
                                                                    .take(3)
                                                            },
                                                        v.exerciseCatalogId?.value,
                                                        v.message
                                                    )
                                                    .joinToString(" · ")
                                            }
                                            .distinct()
                                            .take(3)
                                    )
                                )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val GENERATION_TIMEOUT_MILLIS = 60_000L
    }
}
