package com.example.ironpath.ui.screens.history

import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.repository.HistoryRepository
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.domain.validation.ValidatedRecordDraft
import com.example.ironpath.testutil.FakeIdProvider
import com.example.ironpath.testutil.FakeTimeProvider
import com.example.ironpath.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class HistoryViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private lateinit var historyRepository: HistoryRepository
    private lateinit var recordRepository: RecordRepository
    private lateinit var planRepository: PlanRepository
    private lateinit var viewModel: HistoryViewModel
    private val timeProvider = FakeTimeProvider()
    private val idProvider = FakeIdProvider()

    private val record =
        PersonalRecord(
            id = "rec1",
            exerciseName = "Bench Press",
            normalizedExerciseName = "bench press",
            weightKg = 100.0,
            achievedOn = "2026-04-12",
            createdAt = timeProvider.epochMillis(),
        )
    private val draft =
        ValidatedRecordDraft(
            exerciseName = "Deadlift",
            normalizedExerciseName = "deadlift",
            weightKg = 180.5,
            achievedOn = "2026-07-16",
            note = "Felt strong",
        )

    @Before
    fun setUp() {
        historyRepository = mockk(relaxed = true)
        recordRepository = mockk(relaxed = true)
        planRepository = mockk(relaxed = true)

        every { historyRepository.observeAllLogs() } returns flowOf(emptyList())
        every { recordRepository.observeAllRecords() } returns flowOf(emptyList())
        coEvery { recordRepository.getAllRecordExerciseNames() } returns emptyList()
        coEvery { planRepository.getAllExerciseNames() } returns emptyList()

        viewModel =
            HistoryViewModel(
                historyRepository,
                recordRepository,
                planRepository,
                timeProvider,
                idProvider,
            )
    }

    @Test
    fun `show and hide add record update form state`() {
        assertFalse(viewModel.addRecordShown.value)

        viewModel.showAddRecord()

        assertTrue(viewModel.addRecordShown.value)

        viewModel.hideAddRecord()

        assertFalse(viewModel.addRecordShown.value)
    }

    @Test
    fun `showAddRecord merges deduplicates and sorts exercise suggestions`() = runTest {
        coEvery { planRepository.getAllExerciseNames() } returns listOf("Squat", "Bench Press")
        coEvery { recordRepository.getAllRecordExerciseNames() } returns
            listOf("Deadlift", "Bench Press")

        viewModel.showAddRecord()

        assertEquals(
            listOf("Bench Press", "Deadlift", "Squat"),
            viewModel.exerciseSuggestions.value,
        )
    }

    @Test
    fun `saveRecord maps validated draft with injected id and time`() = runTest {
        val capturedRecord = slot<PersonalRecord>()
        coEvery { recordRepository.insertRecord(capture(capturedRecord)) } returns Unit
        var callbackInvoked = false

        viewModel.showAddRecord()
        viewModel.saveRecord(draft) { callbackInvoked = true }

        assertEquals("test-id-1", capturedRecord.captured.id)
        assertEquals(timeProvider.epochMillis(), capturedRecord.captured.createdAt)
        assertEquals("Deadlift", capturedRecord.captured.exerciseName)
        assertEquals("deadlift", capturedRecord.captured.normalizedExerciseName)
        assertEquals(180.5, capturedRecord.captured.weightKg, 0.0)
        assertEquals("2026-07-16", capturedRecord.captured.achievedOn)
        assertEquals("Felt strong", capturedRecord.captured.note)
        assertTrue(callbackInvoked)
        assertFalse(viewModel.addRecordShown.value)
    }

    @Test
    fun `duplicate rapid save inserts only once`() = runTest {
        val insertStarted = CompletableDeferred<Unit>()
        val releaseInsert = CompletableDeferred<Unit>()
        coEvery { recordRepository.insertRecord(any()) } coAnswers
            {
                insertStarted.complete(Unit)
                releaseInsert.await()
            }
        var callbackCount = 0
        viewModel.showAddRecord()

        viewModel.saveRecord(draft) { callbackCount++ }
        insertStarted.await()
        viewModel.saveRecord(draft) { callbackCount++ }

        releaseInsert.complete(Unit)

        coVerify(exactly = 1) { recordRepository.insertRecord(any()) }
        assertEquals(1, callbackCount)
        assertFalse(viewModel.addRecordShown.value)
    }

    @Test
    fun `insert failure keeps add form open and exposes error`() = runTest {
        coEvery { recordRepository.insertRecord(any()) } throws
            IllegalStateException("database unavailable")
        var callbackInvoked = false
        viewModel.showAddRecord()

        viewModel.saveRecord(draft) { callbackInvoked = true }

        assertTrue(viewModel.addRecordShown.value)
        assertFalse(callbackInvoked)
        assertEquals(
            "Unable to save record. Please try again.",
            viewModel.addRecordError.value,
        )
    }

    @Test
    fun `clearAddRecordError consumes a displayed add error`() = runTest {
        coEvery { recordRepository.insertRecord(any()) } throws
            IllegalStateException("database unavailable")
        viewModel.showAddRecord()
        viewModel.saveRecord(draft) {}

        assertNotNull(viewModel.addRecordError.value)

        viewModel.clearAddRecordError()

        assertNull(viewModel.addRecordError.value)
        assertTrue(viewModel.addRecordShown.value)
    }

    @Test
    fun `hideAddRecord clears an add error before the next form`() = runTest {
        coEvery { recordRepository.insertRecord(any()) } throws
            IllegalStateException("database unavailable")
        viewModel.showAddRecord()
        viewModel.saveRecord(draft) {}

        viewModel.hideAddRecord()
        viewModel.showAddRecord()

        assertNull(viewModel.addRecordError.value)
        assertTrue(viewModel.addRecordShown.value)
    }

    @Test
    fun `successful retry after insert failure resets save guard`() = runTest {
        var attempt = 0
        coEvery { recordRepository.insertRecord(any()) } coAnswers
            {
                attempt++
                if (attempt == 1) throw IllegalStateException("database unavailable")
            }
        var callbackCount = 0
        viewModel.showAddRecord()

        viewModel.saveRecord(draft) { callbackCount++ }
        viewModel.saveRecord(draft) { callbackCount++ }

        coVerify(exactly = 2) { recordRepository.insertRecord(any()) }
        assertEquals(1, callbackCount)
        assertFalse(viewModel.addRecordShown.value)
        assertNull(viewModel.addRecordError.value)
    }

    // -- updateRecord --

    @Test
    fun `updateRecord sets editRecordError when duplicate found`() = runTest {
        coEvery {
            recordRepository.isDuplicateExcluding(
                normalizedName = record.normalizedExerciseName,
                date = record.achievedOn,
                weight = record.weightKg,
                excludeId = record.id,
            )
        } returns true

        viewModel.updateRecord(record)

        assertNotNull(viewModel.editRecordError.value)
        coVerify(exactly = 0) { recordRepository.updateRecord(any()) }
    }

    @Test
    fun `updateRecord calls repository and invokes callback when no duplicate`() = runTest {
        coEvery { recordRepository.isDuplicateExcluding(any(), any(), any(), any()) } returns false
        coEvery { recordRepository.updateRecord(record) } returns Unit
        viewModel.showEditRecord(record)

        var callbackInvoked = false
        viewModel.updateRecord(record, onUpdated = { callbackInvoked = true })

        assertTrue(callbackInvoked)
        assertNull(viewModel.editRecordError.value)
        assertNull(viewModel.editingRecord.value)
        coVerify(exactly = 1) { recordRepository.updateRecord(record) }
    }

    @Test
    fun `updateRecord does not clear error on success if different error was set`() = runTest {
        coEvery { recordRepository.isDuplicateExcluding(any(), any(), any(), any()) } returns false
        coEvery { recordRepository.updateRecord(record) } returns Unit

        viewModel.updateRecord(record)

        assertNull(viewModel.editRecordError.value) // no pre-existing error, stays null
    }

    // -- deleteRecord --

    @Test
    fun `deleteRecord calls repository and invokes callback`() = runTest {
        coEvery { recordRepository.deleteRecord("rec1") } returns Unit

        var callbackInvoked = false
        viewModel.deleteRecord("rec1", onDeleted = { callbackInvoked = true })

        assertTrue(callbackInvoked)
        coVerify(exactly = 1) { recordRepository.deleteRecord("rec1") }
    }

    @Test
    fun `deleteRecord clears editingRecord`() = runTest {
        coEvery { recordRepository.deleteRecord(any()) } returns Unit
        viewModel.showEditRecord(record)

        viewModel.deleteRecord(record.id)

        assertNull(viewModel.editingRecord.value)
    }

    // -- showEditRecord / hideEditRecord --

    @Test
    fun `showEditRecord sets editingRecord`() {
        viewModel.showEditRecord(record)
        assertEquals(record, viewModel.editingRecord.value)
    }

    @Test
    fun `hideEditRecord clears editingRecord and error`() = runTest {
        viewModel.showEditRecord(record)
        coEvery { recordRepository.isDuplicateExcluding(any(), any(), any(), any()) } returns true
        viewModel.updateRecord(record) // trigger error state

        viewModel.hideEditRecord()

        assertNull(viewModel.editingRecord.value)
        assertNull(viewModel.editRecordError.value)
    }

    // -- selectTab --

    @Test
    fun `selectTab updates selectedTab state`() {
        viewModel.selectTab(HistoryTab.Records)
        assertEquals(HistoryTab.Records, viewModel.selectedTab.value)
    }
}
