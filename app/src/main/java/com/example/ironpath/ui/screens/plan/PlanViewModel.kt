package com.example.ironpath.ui.screens.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.planner.GeneratedPlan
import com.example.ironpath.domain.planner.PlanGenerator
import com.example.ironpath.domain.planner.TrainingGoal
import com.example.ironpath.domain.planner.findNextUpcomingWorkout
import com.example.ironpath.domain.planner.findWorkoutScheduledToday
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
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
@HiltViewModel
class PlanViewModel @Inject constructor(
    private val planRepository: PlanRepository,
    private val planGenerator: PlanGenerator,
    private val sessionRepository: SessionRepository,
    private val recordRepository: RecordRepository,
) : ViewModel() {

    // -- Setup state --
    private val _selectedGoal = MutableStateFlow(TrainingGoal.Strength)
    val selectedGoal: StateFlow<TrainingGoal> = _selectedGoal.asStateFlow()

    private val _selectedDays = MutableStateFlow(emptySet<Int>())
    val selectedDays: StateFlow<Set<Int>> = _selectedDays.asStateFlow()

    // -- Review state (in-memory, not yet saved) --
    private val _generatedPlan = MutableStateFlow<GeneratedPlan?>(null)
    val generatedPlan: StateFlow<GeneratedPlan?> = _generatedPlan.asStateFlow()

    // -- Exercise editing state --
    private val _exerciseSuggestions = MutableStateFlow<List<String>>(emptyList())
    val exerciseSuggestions: StateFlow<List<String>> = _exerciseSuggestions.asStateFlow()

    // Undo state: the removed exercise + optionally its workout if that was also removed
    private val _undoExercise = MutableStateFlow<Pair<PlannedExercise, PlannedWorkout?>?>(null)
    val undoExercise: StateFlow<Pair<PlannedExercise, PlannedWorkout?>?> =
        _undoExercise.asStateFlow()

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
                        val today = LocalDate.now()
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

    fun setGoal(goal: TrainingGoal) {
        _selectedGoal.value = goal
    }

    fun toggleDay(day: Int) {
        _selectedDays.update { current -> if (day in current) current - day else current + day }
    }

    fun generatePlan() {
        val days = _selectedDays.value
        if (days.isEmpty()) return
        val generated = planGenerator.generate(_selectedGoal.value, days)
        _generatedPlan.value = generated
        viewModelScope.launch { loadExerciseSuggestions() }
    }

    fun deleteWorkoutFromReview(workoutId: String) {
        val current = _generatedPlan.value ?: return
        val updatedWorkouts = current.workouts.filter { it.id != workoutId }
        val updatedExercises = current.exercises.filter { it.plannedWorkoutId != workoutId }
        _generatedPlan.value =
            current.copy(workouts = updatedWorkouts, exercises = updatedExercises)
    }

    fun reassignWorkoutDay(workoutId: String, newDayOfWeek: Int) {
        val current = _generatedPlan.value ?: return
        val target = current.workouts.find { it.id == workoutId } ?: return
        val startDate = java.time.LocalDate.parse(current.plan.startDate)
        val newScheduledDate = startDate.plusDays((newDayOfWeek - 1).toLong()).toString()
        val occupant = current.workouts.find { it.dayOfWeek == newDayOfWeek && it.id != workoutId }
        val updatedWorkouts =
            if (occupant != null) {
                    current.workouts.map { w ->
                        when (w.id) {
                            workoutId ->
                                w.copy(dayOfWeek = newDayOfWeek, scheduledDate = newScheduledDate)
                            occupant.id ->
                                w.copy(
                                    dayOfWeek = target.dayOfWeek,
                                    scheduledDate = target.scheduledDate
                                )
                            else -> w
                        }
                    }
                } else {
                    current.workouts.map { w ->
                        if (w.id == workoutId)
                            w.copy(dayOfWeek = newDayOfWeek, scheduledDate = newScheduledDate)
                        else w
                    }
                }
                .sortedBy { it.dayOfWeek }
        _generatedPlan.value = current.copy(workouts = updatedWorkouts)
    }

    // -- Exercise editing (in-memory, pre-accept) --

    fun updateExerciseInReview(updated: PlannedExercise) {
        val current = _generatedPlan.value ?: return
        _generatedPlan.value =
            current.copy(
                exercises = current.exercises.map { if (it.id == updated.id) updated else it }
            )
    }

    fun removeExerciseFromReview(exerciseId: String) {
        val current = _generatedPlan.value ?: return
        val target = current.exercises.find { it.id == exerciseId } ?: return
        val remaining = current.exercises.filter { it.id != exerciseId }
        val workoutStillHasExercises =
            remaining.any { it.plannedWorkoutId == target.plannedWorkoutId }
        val removedWorkout: PlannedWorkout?
        val updatedWorkouts: List<PlannedWorkout>
        if (!workoutStillHasExercises) {
            removedWorkout = current.workouts.find { it.id == target.plannedWorkoutId }
            updatedWorkouts = current.workouts.filter { it.id != target.plannedWorkoutId }
        } else {
            removedWorkout = null
            updatedWorkouts = current.workouts
        }
        _undoExercise.value = target to removedWorkout
        _generatedPlan.value = current.copy(workouts = updatedWorkouts, exercises = remaining)
    }

    fun undoRemoveExercise() {
        val (exercise, workout) = _undoExercise.value ?: return
        val current = _generatedPlan.value ?: return
        val updatedWorkouts =
            if (workout != null) (current.workouts + workout).sortedBy { it.dayOfWeek }
            else current.workouts
        val updatedExercises =
            (current.exercises + exercise).sortedWith(
                compareBy({ it.plannedWorkoutId }, { it.orderIndex })
            )
        _undoExercise.value = null
        _generatedPlan.value =
            current.copy(workouts = updatedWorkouts, exercises = updatedExercises)
    }

    fun clearUndo() {
        _undoExercise.value = null
    }

    fun addExerciseToReview(workoutId: String, exercise: PlannedExercise) {
        val current = _generatedPlan.value ?: return
        val maxOrder =
            current.exercises
                .filter { it.plannedWorkoutId == workoutId }
                .maxOfOrNull { it.orderIndex } ?: -1
        val newExercise =
            exercise.copy(
                id = UUID.randomUUID().toString(),
                plannedWorkoutId = workoutId,
                orderIndex = maxOrder + 1,
            )
        _generatedPlan.value = current.copy(exercises = current.exercises + newExercise)
    }

    fun moveExerciseInReview(exerciseId: String, direction: Int) {
        val current = _generatedPlan.value ?: return
        val target = current.exercises.find { it.id == exerciseId } ?: return
        val sorted =
            current.exercises
                .filter { it.plannedWorkoutId == target.plannedWorkoutId }
                .sortedBy { it.orderIndex }
        val currentIndex = sorted.indexOfFirst { it.id == exerciseId }
        val swapIndex = currentIndex + direction
        if (swapIndex < 0 || swapIndex >= sorted.size) return
        val other = sorted[swapIndex]
        _generatedPlan.value =
            current.copy(
                exercises =
                    current.exercises.map { ex ->
                        when (ex.id) {
                            exerciseId -> ex.copy(orderIndex = other.orderIndex)
                            other.id -> ex.copy(orderIndex = target.orderIndex)
                            else -> ex
                        }
                    }
            )
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

    private suspend fun loadExerciseSuggestions() {
        val planNames = planRepository.getAllExerciseNames()
        val recordNames = recordRepository.getAllRecordExerciseNames()
        _exerciseSuggestions.value = (planNames + recordNames).distinct().sorted()
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
