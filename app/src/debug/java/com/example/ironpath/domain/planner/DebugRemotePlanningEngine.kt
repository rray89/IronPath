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
    private val trainingNotesEvidence: SharedTrainingNotesEvidence,
) : PlanningEngine {
    @Inject
    constructor(
        experiment: RemotePlanningExperiment,
        transport: RemotePlanningTransport,
        promptBuilder: OnDevicePlanPromptBuilder,
        draftMapper: OnDevicePlanDraftMapper,
        planValidator: PlanValidator,
        trainingNotesEvidence: SharedTrainingNotesEvidence,
    ) : this(
        experiment = experiment,
        transport = transport,
        promptBuilder = promptBuilder,
        draftMapper = draftMapper,
        planValidator = planValidator,
        timeoutMillis = GENERATION_TIMEOUT_MILLIS,
        trainingNotesEvidence = trainingNotesEvidence,
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
            PlanningResult.Failure(PlanningFailure.Remote(provider, RemoteFailureKind.INTERNAL))
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
        val sharingRevision = request.remoteNoteSharingRevision
        if (
            sharingRevision != null &&
                (route != RemotePlanningRoute.DEEPSEEK || sharingRevision != configuration.revision)
        ) {
            return PlanningResult.Failure(
                PlanningFailure.Remote(route.label, RemoteFailureKind.CONFIGURATION)
            )
        }
        val intake = request.intake
        val shareNotes =
            sharingRevision != null &&
                (intake.injuryNotes
                    .boundedPromptText(OnDevicePlanPromptBuilder.MAX_INTAKE_TEXT_LENGTH)
                    .isNotEmpty() ||
                    intake.exercisePreferences
                        .boundedPromptText(OnDevicePlanPromptBuilder.MAX_PREFERENCE_TEXT_LENGTH)
                        .isNotEmpty() ||
                    intake.exerciseDislikes
                        .boundedPromptText(OnDevicePlanPromptBuilder.MAX_PREFERENCE_TEXT_LENGTH)
                        .isNotEmpty())
        val minimalRequest =
            request.copy(
                intake =
                    intake.copy(
                        injuryNotes = intake.injuryNotes.takeIf { shareNotes }.orEmpty(),
                        exercisePreferences =
                            intake.exercisePreferences.takeIf { shareNotes }.orEmpty(),
                        exerciseDislikes = intake.exerciseDislikes.takeIf { shareNotes }.orEmpty(),
                        recentTraining = RecentTrainingSummary.EMPTY,
                    ),
                remoteNoteSharingRevision = sharingRevision.takeIf { shareNotes },
            )
        val prompt =
            promptBuilder.build(minimalRequest, type).let {
                if (shareNotes) {
                    it.copy(systemInstruction = it.systemInstruction + SHARED_NOTES_INSTRUCTION)
                } else {
                    it
                }
            }
        val startedAt = TimeSource.Monotonic.markNow()
        return when (
            val transportResult =
                transport.generate(
                    apiKey = configuration.apiKey,
                    prompt = prompt,
                    optionId = configuration.optionId,
                )
        ) {
            is RemotePlanningTransportResult.Failure ->
                PlanningResult.Failure(PlanningFailure.Remote(route.label, transportResult.kind))
            is RemotePlanningTransportResult.Success -> {
                when (
                    val mapping =
                        draftMapper.map(
                            proposal =
                                if (shareNotes) transportResult.proposal.withoutProviderProse()
                                else transportResult.proposal,
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
                                                sharedTrainingNotesEvidence =
                                                    if (shareNotes)
                                                        trainingNotesEvidence.summarize(
                                                            mapping.draft,
                                                            request
                                                        )
                                                    else emptyList(),
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

    private fun OnDevicePlanProposal.withoutProviderProse() =
        copy(
            rationale = null,
            warnings = emptyList(),
            workouts =
                workouts.mapIndexed { index, workout ->
                    workout.copy(title = "Workout ${index + 1}")
                },
        )

    private companion object {
        const val SHARED_NOTES_INSTRUCTION =
            " Use injury notes as conservative context. Interpret exercise preferences and dislikes as " +
                "soft guidance about which movements to favor or avoid. " +
                "Equipment and structured movement limits take precedence. " +
                "Never repeat training notes in the response."

        const val GENERATION_TIMEOUT_MILLIS = 60_000L
    }
}
