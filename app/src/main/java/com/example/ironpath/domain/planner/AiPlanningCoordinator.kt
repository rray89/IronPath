package com.example.ironpath.domain.planner

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

data class AiPlanningCandidate(
    val type: PlanningEngineType,
    val priority: Int,
)

object MainAiPlanningChain {
    val candidates =
        setOf(
            AiPlanningCandidate(PlanningEngineType.ON_DEVICE_AI, priority = 0),
            AiPlanningCandidate(PlanningEngineType.RULE_BASED, priority = 100),
        )
}

sealed interface AiPlanningOutcome {
    data class Validated(
        val draft: ValidatedPlanDraft,
        val effectiveEngineType: PlanningEngineType,
    ) : AiPlanningOutcome

    data class Invalid(val violations: List<PlanViolation>) : AiPlanningOutcome

    data class Failure(val reason: PlanningFailure) : AiPlanningOutcome
}

@Singleton
class AiPlanningCoordinator
@Inject
constructor(
    private val planningEngineRegistry: PlanningEngineRegistry,
    private val planValidator: PlanValidator,
    candidates: Set<@JvmSuppressWildcards AiPlanningCandidate>,
) {
    private val orderedCandidates = candidates.sortedBy(AiPlanningCandidate::priority)

    init {
        check(
            orderedCandidates.map(AiPlanningCandidate::type).distinct().size ==
                orderedCandidates.size
        ) {
            "AI planning candidates must have unique engine types"
        }
        check(
            orderedCandidates.map(AiPlanningCandidate::priority).distinct().size ==
                orderedCandidates.size
        ) {
            "AI planning candidates must have unique priorities"
        }
    }

    val aiAvailable: Boolean =
        orderedCandidates.any { candidate ->
            candidate.type != PlanningEngineType.RULE_BASED &&
                planningEngineRegistry.find(candidate.type) != null
        }

    val ruleBasedAvailable: Boolean =
        planningEngineRegistry.find(PlanningEngineType.RULE_BASED) != null

    suspend fun generateWithAi(request: PlanningRequest): AiPlanningOutcome =
        generate(request, orderedCandidates)

    suspend fun generateRuleBased(request: PlanningRequest): AiPlanningOutcome =
        generate(
            request,
            orderedCandidates.filter { it.type == PlanningEngineType.RULE_BASED },
        )

    private suspend fun generate(
        request: PlanningRequest,
        candidates: List<AiPlanningCandidate>,
    ): AiPlanningOutcome {
        val failedAttempts = mutableListOf<FailedAttempt>()
        var lastInvalid: List<PlanViolation>? = null

        candidates.forEach { candidate ->
            val engine = planningEngineRegistry.find(candidate.type)
            if (engine == null) {
                failedAttempts += FailedAttempt(candidate.type, PlanningFailure.Unavailable)
                return@forEach
            }
            val result =
                try {
                    engine.generate(request)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    PlanningResult.Failure(
                        PlanningFailure.ProviderError("The planning provider could not finish.")
                    )
                }
            when (result) {
                is PlanningResult.Failure -> {
                    failedAttempts += FailedAttempt(candidate.type, result.reason)
                }
                is PlanningResult.Success -> {
                    when (
                        val validation =
                            planValidator.validate(
                                result.draft,
                                request.validationContext(candidate.type),
                            )
                    ) {
                        is PlanValidationResult.Invalid -> {
                            lastInvalid = validation.violations
                            failedAttempts +=
                                FailedAttempt(
                                    candidate.type,
                                    PlanningFailure.InvalidRequest(
                                        validation.violations.map(PlanViolation::message)
                                    ),
                                )
                        }
                        is PlanValidationResult.Valid -> {
                            val fallbackReason = fallbackReason(candidate.type, failedAttempts)
                            if (fallbackReason == null) {
                                return AiPlanningOutcome.Validated(
                                    validation.validatedPlan,
                                    candidate.type,
                                )
                            }
                            val fallbackDraft =
                                validation.validatedPlan.draft.copy(
                                    providerMetadata =
                                        validation.validatedPlan.draft.providerMetadata.copy(
                                            fallbackReason =
                                                fallbackReason.normalizedModelText(
                                                    MAX_FALLBACK_REASON_LENGTH
                                                )
                                        )
                                )
                            when (
                                val fallbackValidation =
                                    planValidator.validate(
                                        fallbackDraft,
                                        request.validationContext(candidate.type),
                                    )
                            ) {
                                is PlanValidationResult.Valid ->
                                    return AiPlanningOutcome.Validated(
                                        fallbackValidation.validatedPlan,
                                        candidate.type,
                                    )
                                is PlanValidationResult.Invalid -> {
                                    lastInvalid = fallbackValidation.violations
                                }
                            }
                        }
                    }
                }
            }
        }

        return lastInvalid?.let { AiPlanningOutcome.Invalid(it) }
            ?: AiPlanningOutcome.Failure(
                failedAttempts.lastOrNull()?.failure ?: PlanningFailure.Unavailable
            )
    }

    private fun fallbackReason(
        effectiveType: PlanningEngineType,
        failedAttempts: List<FailedAttempt>,
    ): String? {
        if (failedAttempts.isEmpty()) return null
        val failure =
            failedAttempts.firstOrNull { it.type == PlanningEngineType.ON_DEVICE_AI }?.failure
                ?: failedAttempts.first().failure
        val destination =
            when (effectiveType) {
                PlanningEngineType.RULE_BASED -> "this plan uses the rule-based generator."
                PlanningEngineType.DEBUG_FAKE_AI -> "the debug AI provider generated this draft."
                PlanningEngineType.DEBUG_REMOTE_AI -> "another AI provider generated this draft."
                PlanningEngineType.ON_DEVICE_AI -> return null
            }
        val cause =
            when (failure) {
                PlanningFailure.Unavailable -> "On-device AI is unavailable, so "
                PlanningFailure.Timeout -> "On-device AI took too long, so "
                is PlanningFailure.InvalidRequest -> "The AI draft did not pass safety checks, so "
                is PlanningFailure.ProviderError -> "On-device AI could not finish, so "
            }
        return cause + destination
    }

    private data class FailedAttempt(
        val type: PlanningEngineType,
        val failure: PlanningFailure,
    )

    private companion object {
        const val MAX_FALLBACK_REASON_LENGTH = 180
    }
}
