package com.example.ironpath.ui.screens.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.domain.planner.GeneratedPlan
import com.example.ironpath.domain.planner.PlanGenerator
import com.example.ironpath.domain.planner.TrainingGoal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModel(
    private val planRepository: PlanRepository,
    private val planGenerator: PlanGenerator,
) : ViewModel() {

    // -- Setup state --
    private val _selectedGoal = MutableStateFlow(TrainingGoal.Strength)
    val selectedGoal: StateFlow<TrainingGoal> = _selectedGoal.asStateFlow()

    private val _selectedDays = MutableStateFlow(emptySet<Int>())
    val selectedDays: StateFlow<Set<Int>> = _selectedDays.asStateFlow()

    // -- Review state (in-memory, not yet saved) --
    private val _generatedPlan = MutableStateFlow<GeneratedPlan?>(null)
    val generatedPlan: StateFlow<GeneratedPlan?> = _generatedPlan.asStateFlow()

    // -- Persisted plan observation --
    private val activePlan = planRepository.observeActivePlan()

    private val activeWorkouts = activePlan.flatMapLatest { plan ->
        if (plan != null) {
            planRepository.observeWorkoutsForPlan(plan.id)
        } else {
            flowOf(emptyList())
        }
    }

    val planUiState = combine(activePlan, activeWorkouts, _generatedPlan) { plan, workouts, generated ->
        when {
            generated != null -> PlanUiState.Review(generated)
            plan != null -> {
                val todayDow = java.time.LocalDate.now().dayOfWeek.value
                val upcoming = workouts.filter { it.status.name == "Upcoming" }.sortedBy { it.dayOfWeek }
                val todayWorkout = upcoming.firstOrNull { it.dayOfWeek == todayDow }
                val nextWorkout = todayWorkout
                    ?: upcoming.firstOrNull { it.dayOfWeek > todayDow }
                    ?: upcoming.firstOrNull()
                PlanUiState.Accepted(
                    planned = workouts.size,
                    completed = workouts.count { it.status.name == "Completed" },
                    todayWorkout = todayWorkout,
                    nextWorkout = nextWorkout,
                )
            }
            else -> PlanUiState.Setup
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanUiState.Loading)

    fun setGoal(goal: TrainingGoal) {
        _selectedGoal.value = goal
    }

    fun toggleDay(day: Int) {
        _selectedDays.update { current ->
            if (day in current) current - day else current + day
        }
    }

    fun generatePlan() {
        val days = _selectedDays.value
        if (days.isEmpty()) return
        val generated = planGenerator.generate(_selectedGoal.value, days)
        _generatedPlan.value = generated
    }

    fun deleteWorkoutFromReview(workoutId: String) {
        val current = _generatedPlan.value ?: return
        val updatedWorkouts = current.workouts.filter { it.id != workoutId }
        val updatedExercises = current.exercises.filter { it.plannedWorkoutId != workoutId }
        if (updatedWorkouts.isEmpty()) {
            // All workouts deleted, go back to setup
            _generatedPlan.value = null
        } else {
            _generatedPlan.value = current.copy(workouts = updatedWorkouts, exercises = updatedExercises)
        }
    }

    fun backToSetup() {
        _generatedPlan.value = null
    }

    fun acceptPlan(onAccepted: () -> Unit) {
        val generated = _generatedPlan.value ?: return
        viewModelScope.launch {
            planRepository.createPlan(
                plan = generated.plan,
                workouts = generated.workouts,
                exercises = generated.exercises,
            )
            _generatedPlan.value = null
            _selectedDays.value = emptySet()
            onAccepted()
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
        val todayWorkout: PlannedWorkout?,
        val nextWorkout: PlannedWorkout?,
    ) : PlanUiState
}
