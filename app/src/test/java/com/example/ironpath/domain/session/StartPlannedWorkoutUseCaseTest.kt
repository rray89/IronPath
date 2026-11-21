package com.example.ironpath.domain.session

import com.example.ironpath.data.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartPlannedWorkoutUseCaseTest {
    private val repository = mockk<SessionRepository>()
    private val start = StartPlannedWorkoutUseCase(repository)

    @Test
    fun `forwards identity and generation and preserves all command outcomes`() = runTest {
        for (result in
            listOf(
                StartWorkoutResult.Started("new"),
                StartWorkoutResult.ExistingSession("old"),
                StartWorkoutResult.NotStartable
            )) {
            coEvery { repository.startPlannedWorkout("workout", 7L) } returns result
            assertEquals(result, start("workout", 7L))
        }
        coVerify(exactly = 3) { repository.startPlannedWorkout("workout", 7L) }
    }

    @Test
    fun `cancellation propagates`() = runTest {
        coEvery { repository.startPlannedWorkout(any(), any()) } throws CancellationException()
        assertTrue(runCatching { start("workout") }.exceptionOrNull() is CancellationException)
    }
}
