package com.example.ironpath.ui.screens.workoutpreview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.session.StartPlannedWorkoutUseCase
import com.example.ironpath.domain.time.TimeProvider
import com.example.ironpath.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
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
) : ViewModel() {

    private val workoutId: String = savedStateHandle.get<String>(Route.WORKOUT_ID_ARG).orEmpty()

    private val _uiState = MutableStateFlow<WorkoutPreviewUiState>(WorkoutPreviewUiState.Loading)
    val uiState: StateFlow<WorkoutPreviewUiState> = _uiState.asStateFlow()
    private var startInProgress = false

    init {
        loadPreview()
    }

    fun startWorkout(onStarted: () -> Unit) {
        val state = _uiState.value as? WorkoutPreviewUiState.Ready ?: return
        if (!state.canStart) return
        if (startInProgress) return
        startInProgress = true

        viewModelScope.launch {
            try {
                startPlannedWorkout(state.workout)
                onStarted()
            } finally {
                startInProgress = false
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
