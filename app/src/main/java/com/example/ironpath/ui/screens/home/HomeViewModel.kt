package com.example.ironpath.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.planner.findNextUpcomingWorkout
import com.example.ironpath.domain.planner.findWorkoutScheduledToday
import com.example.ironpath.domain.time.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel
@Inject
constructor(
    private val planRepository: PlanRepository,
    private val sessionRepository: SessionRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val activePlan = planRepository.observeActivePlan()
    private val activeSession = sessionRepository.observeActiveSession()

    private val workouts =
        activePlan.flatMapLatest { plan ->
            if (plan != null) {
                planRepository.observeWorkoutsForPlan(plan.id)
            } else {
                flowOf(emptyList())
            }
        }

    val uiState =
        combine(activePlan, workouts, activeSession) { plan, workouts, session ->
                val hasActiveSession = session != null
                when {
                    plan == null -> HomeUiState.NoPlan
                    else -> {
                        val today = timeProvider.today()
                        val todayWorkout = workouts.findWorkoutScheduledToday(today)
                        val nextWorkout = todayWorkout ?: workouts.findNextUpcomingWorkout(today)

                        val planned = workouts.size
                        val completed = workouts.count { it.status.name == "Completed" }
                        val allDone = planned > 0 && completed == planned

                        if (allDone) {
                            HomeUiState.WeekComplete(planned = planned, completed = completed)
                        } else {
                            HomeUiState.ActivePlan(
                                plan = plan,
                                workouts = workouts,
                                planned = planned,
                                completed = completed,
                                todayWorkout = todayWorkout,
                                nextWorkout = nextWorkout,
                                hasActiveSession = hasActiveSession,
                            )
                        }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)
}

sealed interface HomeUiState {
    data object Loading : HomeUiState

    data object NoPlan : HomeUiState

    data class ActivePlan(
        val plan: WeeklyPlan,
        val workouts: List<PlannedWorkout>,
        val planned: Int,
        val completed: Int,
        val todayWorkout: PlannedWorkout?,
        val nextWorkout: PlannedWorkout?,
        val hasActiveSession: Boolean,
    ) : HomeUiState

    data class WeekComplete(
        val planned: Int,
        val completed: Int,
    ) : HomeUiState
}

fun dayOfWeekAbbrev(dow: Int): String =
    when (dow) {
        1 -> "MON"
        2 -> "TUE"
        3 -> "WED"
        4 -> "THU"
        5 -> "FRI"
        6 -> "SAT"
        7 -> "SUN"
        else -> "?"
    }
