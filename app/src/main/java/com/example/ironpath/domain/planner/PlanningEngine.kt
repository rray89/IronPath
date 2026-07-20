package com.example.ironpath.domain.planner

import dagger.MapKey
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

enum class PlanningEngineType(val debugOnly: Boolean) {
    RULE_BASED(false),
    ON_DEVICE_AI(false),
    DEBUG_FAKE_AI(true),
    DEBUG_REMOTE_AI(true),
}

data class PlanningRequest(
    val targetWeekStart: LocalDate,
    val intake: PlanningIntake,
) {
    constructor(
        targetWeekStart: LocalDate,
        goal: PlanningGoal,
        selectedDays: Set<Int>,
    ) : this(targetWeekStart, PlanningIntake(goal = goal, selectedDays = selectedDays))

    val goal: PlanningGoal
        get() = intake.goal

    val selectedDays: Set<Int>
        get() = intake.selectedDays
}

data class PlanDraft(
    val targetWeekStart: LocalDate,
    val workouts: List<WorkoutDraft>,
    val rationale: String? = null,
    val warnings: List<String> = emptyList(),
    val providerMetadata: PlanningProviderMetadata,
)

data class WorkoutDraft(
    val dayOfWeek: Int,
    val scheduledDate: LocalDate,
    val title: String,
    val exercises: List<ExerciseDraft>,
)

data class ExerciseDraft(
    val catalogId: ExerciseCatalogId,
    val sets: Int,
    val reps: Int,
    val targetWeightKg: Double,
)

data class PlanningProviderMetadata(
    val engineType: PlanningEngineType,
    val generationDurationMillis: Long,
    val fallbackReason: String? = null,
)

sealed interface PlanningResult {
    data class Success(val draft: PlanDraft) : PlanningResult

    data class Failure(val reason: PlanningFailure) : PlanningResult
}

sealed interface PlanningFailure {
    data class InvalidRequest(val violations: List<String>) : PlanningFailure

    data object Unavailable : PlanningFailure

    data object Timeout : PlanningFailure

    data class ProviderError(val message: String?) : PlanningFailure
}

interface PlanningEngine {
    val type: PlanningEngineType

    /**
     * Generates a draft without blocking the main thread. Implementations must let coroutine
     * cancellation propagate; cancellation is never converted to a [PlanningResult.Failure]. A
     * provider's own deadline is returned as [PlanningFailure.Timeout], not coroutine cancellation.
     */
    suspend fun generate(request: PlanningRequest): PlanningResult
}

@MapKey
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PlanningEngineKey(val value: PlanningEngineType)

@Singleton
class PlanningEngineRegistry
@Inject
constructor(private val engines: Map<PlanningEngineType, @JvmSuppressWildcards PlanningEngine>) {
    init {
        check(engines.all { (key, engine) -> key == engine.type }) {
            "Planning engine map keys must match engine types"
        }
    }

    val availableTypes: Set<PlanningEngineType> = engines.keys

    fun find(type: PlanningEngineType): PlanningEngine? = engines[type]

    fun require(type: PlanningEngineType): PlanningEngine =
        checkNotNull(find(type)) { "Planning engine is not registered: $type" }
}
