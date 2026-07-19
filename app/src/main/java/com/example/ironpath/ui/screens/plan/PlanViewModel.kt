package com.example.ironpath.ui.screens.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.planner.GeneratedPlan
import com.example.ironpath.domain.planner.PlanGenerator
import com.example.ironpath.domain.planner.PlanningGoal
import com.example.ironpath.domain.planner.findNextUpcomingWorkout
import com.example.ironpath.domain.planner.findWorkoutScheduledToday
import com.example.ironpath.domain.time.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
) : ViewModel() {

    private var acceptInProgress = false

    // -- Review state (in-memory, not yet saved) --
    private val _generatedPlan = MutableStateFlow<GeneratedPlan?>(null)
    val generatedPlan: StateFlow<GeneratedPlan?> = _generatedPlan.asStateFlow()

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
        combine(activePlan, activeWorkouts, _generatedPlan, activeSession) {
                plan,
                workouts,
                generated,
                session ->
                when {
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
        _generatedPlan.value = generated
    }

    fun deleteWorkoutFromReview(workoutId: String) {
        val current = _generatedPlan.value ?: return
        val updatedWorkouts = current.workouts.filter { it.id != workoutId }
        val updatedExercises = current.exercises.filter { it.plannedWorkoutId != workoutId }
        _generatedPlan.value =
            current.copy(workouts = updatedWorkouts, exercises = updatedExercises)
    }

    fun backToSetup() {
        _generatedPlan.value = null
    }

    fun acceptPlan(onAccepted: () -> Unit) {
        if (acceptInProgress) return
        val generated = _generatedPlan.value ?: return
        acceptInProgress = true
        viewModelScope.launch {
            try {
                planRepository.createPlan(
                    plan = generated.plan,
                    workouts = generated.workouts,
                    exercises = generated.exercises,
                )
                _generatedPlan.value = null
                onAccepted()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Keep the review available so the user can retry accepting the plan.
            } finally {
                acceptInProgress = false
            }
        }
    }
}

sealed interface PlanUiState {
    data object Loading : PlanUiState

    data object Setup : PlanUiState

    data class Review(val generated: GeneratedPlan) : PlanUiState

    data class Accepted(
        val planned: Int,
        val completed: Int,
        val workouts: List<PlannedWorkout>,
        val todayWorkout: PlannedWorkout?,
        val nextWorkout: PlannedWorkout?,
        val hasActiveSession: Boolean,
    ) : PlanUiState
}
