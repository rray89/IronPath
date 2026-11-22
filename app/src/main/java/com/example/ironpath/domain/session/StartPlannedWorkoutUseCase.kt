package com.example.ironpath.domain.session

import com.example.ironpath.data.repository.SessionRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StartPlannedWorkoutUseCase
@Inject
constructor(
    private val sessionRepository: SessionRepository,
) {
    suspend operator fun invoke(
        workoutId: String,
        expectedProfileGeneration: Long? = null,
    ): StartWorkoutResult =
        sessionRepository.startPlannedWorkout(workoutId, expectedProfileGeneration)
}

sealed interface StartWorkoutResult {
    data class Started(val sessionId: String) : StartWorkoutResult

    data class ExistingSession(val sessionId: String) : StartWorkoutResult

    data object NotStartable : StartWorkoutResult
}
