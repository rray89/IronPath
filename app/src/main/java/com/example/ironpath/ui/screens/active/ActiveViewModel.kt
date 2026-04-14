package com.example.ironpath.ui.screens.active

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveViewModel(
    private val sessionRepository: SessionRepository,
    private val planRepository: PlanRepository,
) : ViewModel() {

    private val activeSession = sessionRepository.observeActiveSession()

    private val exercises =
        activeSession.flatMapLatest { session ->
            if (session != null) {
                sessionRepository.observeExercisesForSession(session.id)
            } else {
                flowOf(emptyList())
            }
        }

    private val sets =
        exercises.flatMapLatest { exs ->
            val ids = exs.map { it.id }
            if (ids.isNotEmpty()) {
                sessionRepository.observeSetsForExercises(ids)
            } else {
                flowOf(emptyList())
            }
        }

    // Elapsed timer (seconds since session started)
    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    init {
        // Timer coroutine
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val session = sessionRepository.getActiveSession()
                if (session != null) {
                    _elapsedSeconds.value = (System.currentTimeMillis() - session.startedAt) / 1_000
                }
            }
        }
    }

    // Determine the tab state: no plan, rest day, or session-related
    private val activePlan = planRepository.observeActivePlan()

    private val planWorkouts =
        activePlan.flatMapLatest { plan ->
            if (plan != null) planRepository.observeWorkoutsForPlan(plan.id)
            else flowOf(emptyList())
        }

    private val todayWorkout =
        planWorkouts.map { workouts ->
            val todayDow = LocalDate.now().dayOfWeek.value
            workouts.firstOrNull { it.dayOfWeek == todayDow && it.status == WorkoutStatus.Upcoming }
        }

    private val nextWorkout =
        planWorkouts.map { workouts ->
            val todayDow = LocalDate.now().dayOfWeek.value
            val upcoming =
                workouts.filter { it.status == WorkoutStatus.Upcoming }.sortedBy { it.dayOfWeek }
            upcoming.firstOrNull { it.dayOfWeek > todayDow } ?: upcoming.firstOrNull()
        }

    private val sessionState =
        combine(activeSession, exercises, sets) { session, exs, allSets ->
            Triple(session, exs, allSets)
        }

    private val planState =
        combine(activePlan, todayWorkout, nextWorkout) { plan, today, next ->
            Triple(plan, today, next)
        }

    val uiState =
        combine(sessionState, planState) { (session, exs, allSets), (plan, today, next) ->
                when {
                    session != null ->
                        ActiveUiState.InSession(
                            session = session,
                            exercises = exs,
                            sets = allSets,
                        )
                    plan == null -> ActiveUiState.NoPlan
                    today != null -> ActiveUiState.ReadyToStart(today)
                    else ->
                        ActiveUiState.RestDay(
                            nextWorkoutDay = next?.let { dayOfWeekFull(it.dayOfWeek) },
                        )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActiveUiState.Loading)

    fun startSession(workout: PlannedWorkout) {
        viewModelScope.launch {
            val session =
                ActiveSession(
                    sourcePlannedWorkoutId = workout.id,
                    workoutTitle = workout.title,
                )
            val exercises = planRepository.getExercisesForWorkout(workout.id)
            val sessionExercises =
                exercises.map { ex ->
                    SessionExercise(
                        activeSessionId = session.id,
                        name = ex.name,
                        plannedSets = ex.sets,
                        plannedReps = ex.reps,
                        plannedWeightKg = ex.weightKg,
                        orderIndex = ex.orderIndex,
                    )
                }
            sessionRepository.startSession(session, sessionExercises)

            // Pre-populate planned sets for each exercise
            val createdExercises = sessionRepository.getExercisesForSession(session.id)
            for (ex in createdExercises) {
                for (setNum in 1..ex.plannedSets) {
                    sessionRepository.insertSet(
                        SessionSet(
                            sessionExerciseId = ex.id,
                            setNumber = setNum,
                            weightKg = ex.plannedWeightKg,
                        ),
                    )
                }
            }
        }
    }

    fun updateSet(set: SessionSet) {
        viewModelScope.launch { sessionRepository.updateSet(set) }
    }

    fun addExtraSet(exerciseId: String, currentSetCount: Int) {
        viewModelScope.launch {
            sessionRepository.insertSet(
                SessionSet(
                    sessionExerciseId = exerciseId,
                    setNumber = currentSetCount + 1,
                    isExtra = true,
                ),
            )
        }
    }

    fun finishWorkout(onComplete: () -> Unit) {
        viewModelScope.launch {
            val session = sessionRepository.getActiveSession() ?: return@launch
            val exs = sessionRepository.getExercisesForSession(session.id)
            val exerciseIds = exs.map { it.id }
            val completedSets = sessionRepository.countCompletedSets(exerciseIds)

            val now = System.currentTimeMillis()
            val durationMinutes = ((now - session.startedAt) / 60_000).toInt()

            val log =
                WorkoutLog(
                    title = session.workoutTitle,
                    sourcePlannedWorkoutId = session.sourcePlannedWorkoutId,
                    startedAt = session.startedAt,
                    completedAt = now,
                    durationMinutes = durationMinutes,
                    exerciseCount = exs.size,
                )

            sessionRepository.completeSession(session.id, log)

            // Mark planned workout as completed if at least one set was logged
            if (completedSets > 0) {
                val workout = planRepository.getWorkoutById(session.sourcePlannedWorkoutId)
                if (workout != null) {
                    planRepository.updateWorkout(workout.copy(status = WorkoutStatus.Completed))
                }
            }

            _elapsedSeconds.value = 0
            onComplete()
        }
    }
}

sealed interface ActiveUiState {
    data object Loading : ActiveUiState

    data object NoPlan : ActiveUiState

    data class RestDay(val nextWorkoutDay: String?) : ActiveUiState

    data class ReadyToStart(val workout: PlannedWorkout) : ActiveUiState

    data class InSession(
        val session: ActiveSession,
        val exercises: List<SessionExercise>,
        val sets: List<SessionSet>,
    ) : ActiveUiState
}

private fun dayOfWeekFull(dow: Int): String =
    when (dow) {
        1 -> "Monday"
        2 -> "Tuesday"
        3 -> "Wednesday"
        4 -> "Thursday"
        5 -> "Friday"
        6 -> "Saturday"
        7 -> "Sunday"
        else -> "?"
    }
