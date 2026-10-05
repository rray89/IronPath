package com.example.ironpath.ui.screens.history

import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.SavedStateHandle
import com.example.ironpath.data.local.entity.*
import com.example.ironpath.data.repository.*
import com.example.ironpath.domain.account.ProfileGenerationToken
import com.example.ironpath.testutil.FakeIdProvider
import com.example.ironpath.testutil.FakeTimeProvider
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.util.MainDispatcherRule
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkoutLogRecordViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val history = mockk<HistoryRepository>()
    private val records = mockk<RecordRepository>(relaxed = true)
    private val time = FakeTimeProvider()
    private val detail =
        WorkoutLogDetail(
            WorkoutLog(
                "log",
                title = "Push",
                startedAt = 1L,
                completedAt = time.epochMillis(),
                durationMinutes = 1,
                exerciseCount = 1
            ),
            listOf(
                LoggedExerciseDetail(
                    LoggedExercise("ex", "log", "Bench Press", 3, 8, 60.0, 0),
                    listOf(
                        LoggedSet("one", "ex", 1, reps = 8, weightKg = 62.5),
                        LoggedSet("two", "ex", 2, reps = 5, weightKg = 62.5),
                        LoggedSet("blank", "ex", 3),
                    )
                )
            ),
        )
    private val record =
        PersonalRecord(
            "record",
            "Bench Press",
            "bench press",
            62.5,
            "2026-07-16",
            sourceType = RecordSource.Logged,
            sourceWorkoutLogId = "log",
            createdAt = time.epochMillis()
        )

    private fun vm(
        readOnly: Boolean = false,
        token: ProfileGenerationToken? = null
    ): WorkoutLogDetailViewModel {
        coEvery { history.getLogDetail("log") } returns detail
        return WorkoutLogDetailViewModel(
            SavedStateHandle(
                mapOf(Route.WORKOUT_LOG_ID_ARG to "log", Route.RECORD_SOURCE_ARG to readOnly)
            ),
            history,
            time,
            records,
            FakeIdProvider(),
            token
        )
    }

    private fun WorkoutLogDetailViewModel.ready() = uiState.value as WorkoutLogDetailUiState.Ready

    @Test
    fun `save serializes taps marks every matching set and survives new view model`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery {
            records.saveLoggedSetAsRecord(any(), any(), any(), any(), any(), any())
        } coAnswers
            {
                release.await()
                record
            }
        val vm = vm()
        vm.saveSetAsRecord("one")
        vm.saveSetAsRecord("two")
        assertTrue(vm.ready().saving)
        release.complete(Unit)
        assertEquals(setOf("one", "two"), vm.ready().savedSetIds)
        assertFalse(vm.ready().saving)
        coVerify(exactly = 1) {
            records.saveLoggedSetAsRecord(
                "log",
                "one",
                "test-id-1",
                time.epochMillis(),
                time.zoneId,
                null
            )
        }
        coEvery { records.getLoggedRecordsForWorkoutLog("log") } returns listOf(record)
        val restored = vm()
        assertEquals(setOf("one", "two"), restored.ready().savedSetIds)
        restored.saveSetAsRecord("two")
        coVerify(exactly = 1) {
            records.saveLoggedSetAsRecord(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `unknown incomplete and source-only actions never write`() = runTest {
        val vm = vm()
        vm.saveSetAsRecord("unknown")
        vm.saveSetAsRecord("blank")
        vm(readOnly = true).saveSetAsRecord("one")
        coVerify(exactly = 0) {
            records.saveLoggedSetAsRecord(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `manual duplicate stays unsaved while same source race refreshes saved state`() = runTest {
        coEvery { records.saveLoggedSetAsRecord(any(), any(), any(), any(), any(), any()) } throws
            SQLiteConstraintException("unique")
        val vm = vm()
        vm.saveSetAsRecord("one")
        assertTrue(vm.ready().savedSetIds.isEmpty())
        assertEquals(
            "A record with this exercise, date, and weight already exists.",
            vm.ready().recordMessage
        )
        coEvery { records.getLoggedRecordsForWorkoutLog("log") } returns listOf(record)
        vm.saveSetAsRecord("one")
        assertEquals(setOf("one", "two"), vm.ready().savedSetIds)
        assertEquals("Record already saved from this workout.", vm.ready().recordMessage)
    }

    @Test
    fun `failure and cancellation release guard and allow retry`() = runTest {
        coEvery { records.saveLoggedSetAsRecord(any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("database")
        val vm = vm()
        vm.saveSetAsRecord("one")
        assertFalse(vm.ready().saving)
        assertEquals("Unable to save record. Please try again.", vm.ready().recordMessage)
        coEvery { records.saveLoggedSetAsRecord(any(), any(), any(), any(), any(), any()) } throws
            CancellationException()
        vm.saveSetAsRecord("one")
        assertFalse(vm.ready().saving)
        coEvery { records.saveLoggedSetAsRecord(any(), any(), any(), any(), any(), any()) } returns
            record
        vm.saveSetAsRecord("one")
        assertEquals(setOf("one", "two"), vm.ready().savedSetIds)
    }

    @Test
    fun `profile generation captured before source is exposed travels with write`() = runTest {
        val token = mockk<ProfileGenerationToken>()
        coEvery { token.initialize() } returns 17L
        every { token.current() } returns 17L
        coEvery { records.saveLoggedSetAsRecord(any(), any(), any(), any(), any(), 17L) } returns
            record
        val vm = vm(token = token)
        vm.saveSetAsRecord("one")
        coVerify(exactly = 1) {
            records.saveLoggedSetAsRecord("log", "one", any(), any(), time.zoneId, 17L)
        }
    }

    @Test
    fun `failed load retries only once and cannot replace an already ready screen`() = runTest {
        coEvery { history.getLogDetail("log") } throws IllegalStateException("failed load")
        val vm =
            WorkoutLogDetailViewModel(
                SavedStateHandle(mapOf(Route.WORKOUT_LOG_ID_ARG to "log")),
                history,
                time,
                records,
                FakeIdProvider()
            )
        assertEquals(WorkoutLogDetailUiState.Failed, vm.uiState.value)
        val release = CompletableDeferred<Unit>()
        coEvery { history.getLogDetail("log") } coAnswers
            {
                release.await()
                detail
            }
        vm.loadDetail()
        vm.loadDetail()
        release.complete(Unit)
        assertEquals(detail, vm.ready().detail)
        vm.loadDetail()
        coVerify(exactly = 2) { history.getLogDetail("log") }
    }
}
