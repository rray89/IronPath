package com.example.ironpath.domain.session

import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository

class StartPlannedWorkoutUseCase(
    private val planRepository: PlanRepository,
    private val sessionRepository: SessionRepository,
) {

    suspend operator fun invoke(workout: PlannedWorkout) {
        val session =
            ActiveSession(
                sourcePlannedWorkoutId = workout.id,
                workoutTitle = workout.title,
            )
        val plannedExercises = planRepository.getExercisesForWorkout(workout.id)
        val sessionExercises =
            plannedExercises.map { exercise ->
                SessionExercise(
                    activeSessionId = session.id,
                    name = exercise.name,
                    plannedSets = exercise.sets,
                    plannedReps = exercise.reps,
                    plannedWeightKg = exercise.weightKg,
                    orderIndex = exercise.orderIndex,
                )
            }

        sessionRepository.startSession(session, sessionExercises)

        val createdExercises = sessionRepository.getExercisesForSession(session.id)
        createdExercises.forEach { exercise ->
            repeat(exercise.plannedSets) { index ->
                sessionRepository.insertSet(
                    SessionSet(
                        sessionExerciseId = exercise.id,
                        setNumber = index + 1,
                        weightKg = exercise.plannedWeightKg,
                    ),
                )
            }
        }
    }
}
