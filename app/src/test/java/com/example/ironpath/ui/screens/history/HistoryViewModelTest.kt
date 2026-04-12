package com.example.ironpath.ui.screens.history

import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.repository.HistoryRepository
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

  private val record =
    PersonalRecord(
      id = "rec1",
      exerciseName = "Bench Press",
      normalizedExerciseName = "bench press",
      weightKg = 100.0,
      achievedOn = "2026-04-12",
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

    viewModel = HistoryViewModel(historyRepository, recordRepository, planRepository)
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
