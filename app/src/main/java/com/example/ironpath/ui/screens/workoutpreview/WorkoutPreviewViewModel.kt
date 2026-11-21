package com.example.ironpath.ui.screens.workoutpreview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.account.ProfileGenerationToken
import com.example.ironpath.domain.session.StartPlannedWorkoutUseCase
import com.example.ironpath.domain.session.StartWorkoutResult
import com.example.ironpath.domain.time.TimeProvider
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.screens.WorkoutStartUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@HiltViewModel
class WorkoutPreviewViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val planRepository: PlanRepository,
    private val sessionRepository: SessionRepository,
    private val startPlannedWorkout: StartPlannedWorkoutUseCase,
    private val timeProvider: TimeProvider,
    private val profileGenerationToken: ProfileGenerationToken? = null,
) : ViewModel() {

    private val workoutId: String = savedStateHandle.get<String>(Route.WORKOUT_ID_ARG).orEmpty()

    private val _uiState = MutableStateFlow<WorkoutPreviewUiState>(WorkoutPreviewUiState.Loading)
    val uiState: StateFlow<WorkoutPreviewUiState> = _uiState.asStateFlow()
    private val _startState = MutableStateFlow(WorkoutStartUiState())
    val startState = _startState.asStateFlow()

    init {
        profileGenerationToken?.let { token ->
            viewModelScope.launch { runCatching { token.initialize() } }
        }
        loadPreview()
    }

    fun startWorkout(onStarted: () -> Unit) {
        val state = _uiState.value as? WorkoutPreviewUiState.Ready ?: return
        if (_startState.value.isStarting) return
        if (state.hasActiveSession || _startState.value.hasExistingSession) {
            onStarted()
            return
        }
        if (!state.canStart) return
        val expectedProfileGeneration = profileGenerationToken?.current()
        if (profileGenerationToken != null && expectedProfileGeneration == null) {
            _startState.value =
                WorkoutStartUiState(error = "Your profile is still loading. Try again.")
            return
        }
        _startState.value = WorkoutStartUiState(isStarting = true)
        viewModelScope.launch {
            try {
                when (startPlannedWorkout(state.workout.id, expectedProfileGeneration)) {
                    is StartWorkoutResult.Started -> {
                        _startState.value = WorkoutStartUiState()
                        onStarted()
                    }
                    is StartWorkoutResult.ExistingSession -> {
                        _startState.value = WorkoutStartUiState(hasExistingSession = true)
                    }
                    StartWorkoutResult.NotStartable -> {
                        _startState.value =
                            WorkoutStartUiState(
                                error = "This workout is no longer available to start today."
                            )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _startState.value =
                    WorkoutStartUiState(
                        error = "Could not start this workout. Reopen the preview and try again."
                    )
            } finally {
                _startState.value = _startState.value.copy(isStarting = false)
            }
        }
    }

    private fun loadPreview() {
        viewModelScope.launch {
            val workout = planRepository.getWorkoutById(workoutId)
            if (workout == null) {
                _uiState.value = WorkoutPreviewUiState.NotFound
                return@launch
            }

            combine(
                    planRepository.observeExercisesForWorkout(workoutId),
                    sessionRepository.observeActiveSession(),
                ) { exercises, activeSession ->
                    val today = timeProvider.today()
                    WorkoutPreviewUiState.Ready(
                        workout = workout,
                        exercises = exercises.sortedBy { it.orderIndex },
                        canStart = workout.isStartableToday(today) && activeSession == null,
                        hasActiveSession = activeSession != null,
                    )
                }
                .collect { _uiState.value = it }
        }
    }

    private fun PlannedWorkout.isStartableToday(today: LocalDate): Boolean {
        if (status != WorkoutStatus.Upcoming) return false
        val date = runCatching { LocalDate.parse(scheduledDate) }.getOrNull() ?: return false
        return date == today
    }
}

sealed interface WorkoutPreviewUiState {
    data object Loading : WorkoutPreviewUiState

    data object NotFound : WorkoutPreviewUiState

    data class Ready(
        val workout: PlannedWorkout,
        val exercises: List<PlannedExercise>,
        val canStart: Boolean,
        val hasActiveSession: Boolean,
    ) : WorkoutPreviewUiState
}
