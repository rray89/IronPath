package com.example.ironpath.domain.session

import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StartPlannedWorkoutUseCase
@Inject
constructor(
    private val planRepository: PlanRepository,
    private val sessionRepository: SessionRepository,
    private val timeProvider: TimeProvider,
    private val idProvider: IdProvider,
) {

    suspend operator fun invoke(workout: PlannedWorkout) {
        val startedAt = timeProvider.epochMillis()
        val session =
            ActiveSession(
                id = idProvider.newId(),
                sourcePlannedWorkoutId = workout.id,
                workoutTitle = workout.title,
                startedAt = startedAt,
                lastUpdatedAt = startedAt,
            )
        val plannedExercises = planRepository.getExercisesForWorkout(workout.id)
        val sessionExercises =
            plannedExercises.map { exercise ->
                SessionExercise(
                    id = idProvider.newId(),
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
                        id = idProvider.newId(),
                        sessionExerciseId = exercise.id,
                        setNumber = index + 1,
                        weightKg = exercise.plannedWeightKg,
                    ),
                )
            }
        }
    }
}
