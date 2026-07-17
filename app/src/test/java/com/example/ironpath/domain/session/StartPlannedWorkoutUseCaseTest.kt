package com.example.ironpath.domain.session

import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.time.TimeProvider
import com.example.ironpath.testutil.FakeIdProvider
import com.example.ironpath.testutil.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class StartPlannedWorkoutUseCaseTest {

    private lateinit var planRepository: PlanRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var startPlannedWorkout: StartPlannedWorkoutUseCase

    private val workout =
        PlannedWorkout(
            id = "workout1",
            weeklyPlanId = "plan1",
            dayOfWeek = 1,
            scheduledDate = "2026-04-14",
            title = "Push A",
        )

    private fun plannedExercise(
        id: String,
        name: String,
        sets: Int,
        orderIndex: Int,
    ) =
        PlannedExercise(
            id = id,
            plannedWorkoutId = "workout1",
            name = name,
            sets = sets,
            reps = 10,
            weightKg = 60.0,
            orderIndex = orderIndex,
        )

    @Before
    fun setUp() {
        planRepository = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        startPlannedWorkout =
            StartPlannedWorkoutUseCase(
                planRepository,
                sessionRepository,
                FakeTimeProvider(),
                FakeIdProvider(),
            )
    }

    @Test
    fun `invoke uses injected time and ids for the active graph`() = runTest {
        val timeProvider = FakeTimeProvider()
        val deterministicUseCase =
            StartPlannedWorkoutUseCase(
                planRepository,
                sessionRepository,
                timeProvider,
                FakeIdProvider(),
            )
        val capturedExercises = slot<List<SessionExercise>>()
        coEvery { planRepository.getExercisesForWorkout("workout1") } returns
            listOf(plannedExercise("planned-1", "Bench Press", sets = 1, orderIndex = 0))
        coEvery { sessionRepository.startSession(any(), capture(capturedExercises)) } returns Unit
        coEvery { sessionRepository.getExercisesForSession(any()) } returns emptyList()

        deterministicUseCase(workout)

        coVerify {
            sessionRepository.startSession(
                match {
                    it.id == "test-id-1" &&
                        it.startedAt == timeProvider.epochMillis() &&
                        it.lastUpdatedAt == timeProvider.epochMillis()
                },
                any(),
            )
        }
        assertEquals("test-id-2", capturedExercises.captured.single().id)
    }

    @Test
    fun `session start and update timestamps come from one time snapshot`() = runTest {
        val tickingTimeProvider =
            object : TimeProvider {
                private var instant = Instant.parse("2026-07-16T19:00:00Z")
                override val zoneId: ZoneId = ZoneId.of("America/Vancouver")

                override fun now(): Instant = instant.also { instant = instant.plusMillis(1) }
            }
        val capturedSession = slot<com.example.ironpath.data.local.entity.ActiveSession>()
        coEvery { planRepository.getExercisesForWorkout("workout1") } returns emptyList()
        coEvery { sessionRepository.startSession(capture(capturedSession), emptyList()) } returns
            Unit
        coEvery { sessionRepository.getExercisesForSession(any()) } returns emptyList()

        StartPlannedWorkoutUseCase(
            planRepository,
            sessionRepository,
            tickingTimeProvider,
            FakeIdProvider(),
        )(workout)

        assertEquals(capturedSession.captured.startedAt, capturedSession.captured.lastUpdatedAt)
    }

    @Test
    fun `invoke starts an active session with exercises copied from the planned workout`() =
        runTest {
            val capturedExercises = slot<List<SessionExercise>>()
            coEvery { planRepository.getExercisesForWorkout("workout1") } returns
                listOf(plannedExercise("ex1", "Bench Press", sets = 3, orderIndex = 0))
            coEvery { sessionRepository.startSession(any(), capture(capturedExercises)) } returns
                Unit
            coEvery { sessionRepository.getExercisesForSession(any()) } returns emptyList()

            startPlannedWorkout(workout)

            coVerify {
                sessionRepository.startSession(
                    match {
                        it.sourcePlannedWorkoutId == "workout1" && it.workoutTitle == "Push A"
                    },
                    any(),
                )
            }
            assertEquals(1, capturedExercises.captured.size)
            assertEquals("Bench Press", capturedExercises.captured[0].name)
            assertEquals(3, capturedExercises.captured[0].plannedSets)
            assertEquals(10, capturedExercises.captured[0].plannedReps)
            assertEquals(60.0, capturedExercises.captured[0].plannedWeightKg, 0.0)
        }

    @Test
    fun `invoke pre-populates one pending set per planned set after session exercises are created`() =
        runTest {
            val insertedSets = mutableListOf<SessionSet>()
            coEvery { planRepository.getExercisesForWorkout("workout1") } returns
                listOf(plannedExercise("ex1", "Bench Press", sets = 2, orderIndex = 0))
            coEvery { sessionRepository.getExercisesForSession(any()) } returns
                listOf(
                    SessionExercise(
                        id = "session-ex1",
                        activeSessionId = "session1",
                        name = "Bench Press",
                        plannedSets = 2,
                        plannedReps = 10,
                        plannedWeightKg = 60.0,
                        orderIndex = 0,
                    ),
                    SessionExercise(
                        id = "session-ex2",
                        activeSessionId = "session1",
                        name = "Overhead Press",
                        plannedSets = 1,
                        plannedReps = 8,
                        plannedWeightKg = 40.0,
                        orderIndex = 1,
                    ),
                )
            coEvery { sessionRepository.insertSet(capture(insertedSets)) } returns Unit

            startPlannedWorkout(workout)

            assertEquals(3, insertedSets.size)
            assertEquals(listOf(1, 2, 1), insertedSets.map { it.setNumber })
            assertEquals(
                listOf("session-ex1", "session-ex1", "session-ex2"),
                insertedSets.map { it.sessionExerciseId }
            )
            assertEquals(listOf(60.0, 60.0, 40.0), insertedSets.map { it.weightKg })
        }
}
