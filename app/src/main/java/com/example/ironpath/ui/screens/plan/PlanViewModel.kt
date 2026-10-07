package com.example.ironpath.ui.screens.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.account.ProfileGenerationToken
import com.example.ironpath.domain.planner.AiPlanDraftReviewState
import com.example.ironpath.domain.planner.AiPlanReviewEditor
import com.example.ironpath.domain.planner.ExerciseCatalogEntry
import com.example.ironpath.domain.planner.ExerciseCatalogId
import com.example.ironpath.domain.planner.ExerciseDraft
import com.example.ironpath.domain.planner.GeneratedPlan
import com.example.ironpath.domain.planner.PlanGenerator
import com.example.ironpath.domain.planner.PlanningGoal
import com.example.ironpath.domain.planner.RemotePlanningExperiment
import com.example.ironpath.domain.planner.ValidatedPlanDraft
import com.example.ironpath.domain.planner.ValidatedPlanDraftMapper
import com.example.ironpath.domain.planner.findNextUpcomingWorkout
import com.example.ironpath.domain.planner.findWorkoutScheduledToday
import com.example.ironpath.domain.time.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlanViewModel
@Inject
constructor(
    private val planRepository: PlanRepository,
    private val planGenerator: PlanGenerator,
    private val sessionRepository: SessionRepository,
    private val timeProvider: TimeProvider,
    private val aiPlanReviewEditor: AiPlanReviewEditor,
    private val validatedPlanDraftMapper: ValidatedPlanDraftMapper,
    private val profileGenerationToken: ProfileGenerationToken? = null,
    private val remotePlanningExperiment: RemotePlanningExperiment? = null,
) : ViewModel() {

    private var acceptInProgress = false
    private var aiAcceptJob: Job? = null
    private var reviewConfigurationRevision: Long? = null

    // -- Review state (in-memory, not yet saved) --
    private val _generatedPlan = MutableStateFlow<GeneratedPlan?>(null)
    val generatedPlan: StateFlow<GeneratedPlan?> = _generatedPlan.asStateFlow()

    private val _aiReviewState = MutableStateFlow<AiPlanReviewUiState?>(null)
    val aiReviewState: StateFlow<AiPlanReviewUiState?> = _aiReviewState.asStateFlow()
    private var mappedAiPlan: MappedAiPlan? = null
    private var pendingAiReview: PendingAiReview? = null

    init {
        remotePlanningExperiment?.let { experiment ->
            var observedRevision = experiment.state.value.revision
            viewModelScope.launch {
                experiment.state.collect { state ->
                    if (state.revision != observedRevision) {
                        observedRevision = state.revision
                        if (
                            reviewConfigurationRevision != null &&
                                reviewConfigurationRevision != state.revision
                        ) {
                            aiAcceptJob?.cancel()
                            clearAiReview()
                        }
                    }
                }
            }
        }
        profileGenerationToken?.let { token ->
            viewModelScope.launch { runCatching { token.initialize() } }
        }
    }

    // -- Persisted plan observation --
    private val activePlan = planRepository.observeActivePlan()
    private val activeSession = sessionRepository.observeActiveSession()

    private val activeWorkouts =
        activePlan.flatMapLatest { plan ->
            if (plan != null) {
                planRepository.observeWorkoutsForPlan(plan.id)
            } else {
                flowOf(emptyList())
            }
        }

    val planUiState =
        combine(activePlan, activeWorkouts, _generatedPlan, activeSession, _aiReviewState) {
                plan,
                workouts,
                generated,
                session,
                aiReview ->
                when {
                    aiReview != null -> PlanUiState.AiReview(aiReview)
                    generated != null -> PlanUiState.Review(generated)
                    plan != null -> {
                        val today = timeProvider.today()
                        val todayWorkout = workouts.findWorkoutScheduledToday(today)
                        val nextWorkout = todayWorkout ?: workouts.findNextUpcomingWorkout(today)
                        PlanUiState.Accepted(
                            planned = workouts.size,
                            completed = workouts.count { it.status.name == "Completed" },
                            workouts = workouts,
                            todayWorkout = todayWorkout,
                            nextWorkout = nextWorkout,
                            hasActiveSession = session != null,
                        )
                    }
                    else -> PlanUiState.Setup
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanUiState.Loading)

    fun generatePlan(goal: PlanningGoal, selectedDays: Set<Int>) {
        if (selectedDays.isEmpty()) return
        val generated = planGenerator.generate(goal, selectedDays)
        clearAiReview()
        _generatedPlan.value = generated
    }

    fun enterAiReview(
        validatedPlan: ValidatedPlanDraft,
        configurationRevision: Long? = null
    ): Boolean {
        if (
            configurationRevision != null &&
                configurationRevision != remotePlanningExperiment?.state?.value?.revision
        )
            return false
        if (!matchesRemoteConfiguration(validatedPlan)) return false
        val current = _aiReviewState.value
        if (current?.sourceToken === validatedPlan) return true
        if (pendingAiReview?.validatedPlan === validatedPlan) return true
        if (acceptInProgress) {
            pendingAiReview =
                PendingAiReview(
                    validatedPlan,
                    configurationRevision ?: remotePlanningExperiment?.state?.value?.revision,
                )
            return true
        }

        showAiReview(validatedPlan)
        return true
    }

    private fun matchesRemoteConfiguration(validatedPlan: ValidatedPlanDraft): Boolean {
        val revision =
            validatedPlan.draft.providerMetadata.remoteConfigurationRevision ?: return true
        return revision == remotePlanningExperiment?.state?.value?.revision
    }

    private fun showAiReview(validatedPlan: ValidatedPlanDraft) {
        if (!matchesRemoteConfiguration(validatedPlan)) return
        reviewConfigurationRevision = remotePlanningExperiment?.state?.value?.revision
        val review = aiPlanReviewEditor.start(validatedPlan)
        _generatedPlan.value = null
        mappedAiPlan = null
        _aiReviewState.value =
            AiPlanReviewUiState(
                sourceToken = validatedPlan,
                review = review,
                eligibleExercises = aiPlanReviewEditor.eligibleEntries(review),
            )
    }

    fun addAiExercise(
        workoutDay: Int,
        exercise: ExerciseDraft,
    ) = editAiReview { aiPlanReviewEditor.addExercise(it, workoutDay, exercise) }

    fun replaceAiExercise(
        workoutDay: Int,
        originalId: ExerciseCatalogId,
        replacement: ExerciseDraft,
    ) = editAiReview { aiPlanReviewEditor.replaceExercise(it, workoutDay, originalId, replacement) }

    fun deleteWorkoutFromReview(workoutId: String) {
        val current = _generatedPlan.value ?: return
        val updatedWorkouts = current.workouts.filter { it.id != workoutId }
        val updatedExercises = current.exercises.filter { it.plannedWorkoutId != workoutId }
        _generatedPlan.value =
            current.copy(workouts = updatedWorkouts, exercises = updatedExercises)
    }

    fun backToSetup() {
        aiAcceptJob?.cancel()
        _generatedPlan.value = null
        clearAiReview()
    }

    fun acceptPlan(onAccepted: () -> Unit) {
        if (acceptInProgress) return
        val expectedProfileGeneration = profileGenerationToken?.current()
        if (profileGenerationToken != null && expectedProfileGeneration == null) return
        val aiReview = _aiReviewState.value
        if (aiReview != null) {
            acceptAiPlan(aiReview, onAccepted)
            return
        }
        val generated = _generatedPlan.value ?: return
        acceptInProgress = true
        viewModelScope.launch {
            var saved = false
            try {
                planRepository.createPlan(
                    plan = generated.plan,
                    workouts = generated.workouts,
                    exercises = generated.exercises,
                    expectedProfileGeneration = expectedProfileGeneration,
                )
                saved = true
                pendingAiReview = null
                _generatedPlan.value = null
                onAccepted()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Keep the review available so the user can retry accepting the plan.
            } finally {
                acceptInProgress = false
                if (!saved) showPendingAiReview()
            }
        }
    }

    private fun acceptAiPlan(
        reviewState: AiPlanReviewUiState,
        onAccepted: () -> Unit,
    ) {
        if (!reviewState.canAccept) return
        if (reviewConfigurationRevision != remotePlanningExperiment?.state?.value?.revision) {
            clearAiReview()
            return
        }
        val expectedProfileGeneration = profileGenerationToken?.current()
        if (profileGenerationToken != null && expectedProfileGeneration == null) return
        val revalidatedReview = aiPlanReviewEditor.revalidate(reviewState.review)
        val refreshedState = reviewState.copy(review = revalidatedReview, saveError = null)
        _aiReviewState.value = refreshedState
        val validatedPlan = (revalidatedReview as? AiPlanDraftReviewState.Valid)?.validatedPlan
        if (validatedPlan == null) {
            mappedAiPlan = null
            return
        }
        val generated =
            mappedAiPlan
                ?.takeIf {
                    it.validatedPlan.draft == validatedPlan.draft &&
                        it.validatedPlan.context == validatedPlan.context
                }
                ?.generated
                ?: validatedPlanDraftMapper.map(validatedPlan).also {
                    mappedAiPlan = MappedAiPlan(validatedPlan, it)
                }
        acceptInProgress = true
        _aiReviewState.value = refreshedState.copy(isAccepting = true)
        val expectedConfigurationRevision = reviewConfigurationRevision
        aiAcceptJob =
            viewModelScope.launch {
                try {
                    currentCoroutineContext().ensureActive()
                    if (
                        expectedConfigurationRevision !=
                            remotePlanningExperiment?.state?.value?.revision
                    ) {
                        clearAiReview()
                        return@launch
                    }
                    planRepository.createPlan(
                        plan = generated.plan,
                        workouts = generated.workouts,
                        exercises = generated.exercises,
                        expectedProfileGeneration = expectedProfileGeneration,
                    )
                    currentCoroutineContext().ensureActive()
                    if (
                        expectedConfigurationRevision !=
                            remotePlanningExperiment?.state?.value?.revision
                    )
                        return@launch
                    pendingAiReview = null
                    clearAiReview()
                    onAccepted()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    _aiReviewState.value =
                        _aiReviewState.value?.copy(
                            isAccepting = false,
                            saveError = AI_SAVE_ERROR,
                        )
                } finally {
                    acceptInProgress = false
                    showPendingAiReview()
                    _aiReviewState.value = _aiReviewState.value?.copy(isAccepting = false)
                }
            }
    }

    private fun editAiReview(transform: (AiPlanDraftReviewState) -> AiPlanDraftReviewState) {
        val current = _aiReviewState.value ?: return
        if (current.isAccepting) return
        val edited = transform(current.review)
        if (edited === current.review) return

        mappedAiPlan = null
        _aiReviewState.value =
            current.copy(
                review = edited,
                eligibleExercises = aiPlanReviewEditor.eligibleEntries(edited),
                saveError = null,
            )
    }

    private fun clearAiReview() {
        reviewConfigurationRevision = null
        _aiReviewState.value = null
        mappedAiPlan = null
        pendingAiReview = null
    }

    private fun showPendingAiReview() {
        val pending = pendingAiReview ?: return
        pendingAiReview = null
        if (pending.configurationRevision != remotePlanningExperiment?.state?.value?.revision)
            return
        showAiReview(pending.validatedPlan)
    }

    private data class PendingAiReview(
        val validatedPlan: ValidatedPlanDraft,
        val configurationRevision: Long?,
    )

    private data class MappedAiPlan(
        val validatedPlan: ValidatedPlanDraft,
        val generated: GeneratedPlan,
    )

    private companion object {
        const val AI_SAVE_ERROR = "Could not save this plan. Try again."
    }
}

data class AiPlanReviewUiState(
    internal val sourceToken: ValidatedPlanDraft,
    val review: AiPlanDraftReviewState,
    val eligibleExercises: List<ExerciseCatalogEntry>,
    val isAccepting: Boolean = false,
    val saveError: String? = null,
) {
    val canAccept: Boolean
        get() = review.canAccept && !isAccepting
}

sealed interface PlanUiState {
    data object Loading : PlanUiState

    data object Setup : PlanUiState

    data class Review(val generated: GeneratedPlan) : PlanUiState

    data class AiReview(val review: AiPlanReviewUiState) : PlanUiState

    data class Accepted(
        val planned: Int,
        val completed: Int,
        val workouts: List<PlannedWorkout>,
        val todayWorkout: PlannedWorkout?,
        val nextWorkout: PlannedWorkout?,
        val hasActiveSession: Boolean,
    ) : PlanUiState
}
