package com.example.ironpath.data.backup

import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import org.junit.Assert.*
import org.junit.Test

class ManualSyncMergerTest {
    @Test
    fun oneSidedChangesMergeOnBothConflictChoicesWithoutUsingClockOrder() {
        val base = bundle(record("shared", 80.0))
        val local = bundle(record("shared", 85.0), record("local", 30.0))
        val cloud = bundle(record("shared", 80.0), record("cloud", 40.0))
        val result = ManualSyncMerger.analyze(base, local, cloud)
        assertEquals(0, result.conflicts.values.sum())
        assertEquals(
            listOf("cloud", "local", "shared"),
            result.localResult!!.personalRecords.map { it.id }
        )
        assertEquals(85.0, result.localResult.personalRecords.last().weightKg, 0.0)
        assertEquals(result.localResult, result.cloudResult)
    }

    @Test
    fun explicitConflictChoicesRetainAllOneSidedChanges() {
        val result =
            ManualSyncMerger.analyze(
                bundle(record("shared", 80.0)),
                bundle(record("shared", 85.0), record("local", 30.0)),
                bundle(record("shared", 90.0), record("cloud", 40.0)),
            )
        assertEquals(1, result.conflicts["PersonalRecord"])
        assertEquals(3, result.localResult!!.personalRecords.size)
        assertEquals(3, result.cloudResult!!.personalRecords.size)
        assertEquals(85.0, result.localResult.personalRecords.last().weightKg, 0.0)
        assertEquals(90.0, result.cloudResult.personalRecords.last().weightKg, 0.0)
    }

    @Test
    fun deletionAgainstAnUnchangedRecordMergesButDeletionAgainstEditConflicts() {
        val base = bundle(record("shared", 80.0))
        val deleted = ManualSyncMerger.analyze(base, bundle(), base)
        assertTrue(deleted.localResult!!.personalRecords.isEmpty())
        assertEquals(0, deleted.conflicts.values.sum())
        val conflict = ManualSyncMerger.analyze(base, bundle(), bundle(record("shared", 90.0)))
        assertEquals(1, conflict.conflicts["PersonalRecord"])
        assertTrue(conflict.localResult!!.personalRecords.isEmpty())
        assertEquals(90.0, conflict.cloudResult!!.personalRecords.single().weightKg, 0.0)
    }

    @Test
    fun independentGraphsThatWouldCreateTwoActivePlansAreBlocked() {
        val local = bundle().copy(weeklyPlans = listOf(plan("local")))
        val cloud = bundle().copy(weeklyPlans = listOf(plan("cloud")))
        val result = ManualSyncMerger.analyze(bundle(), local, cloud)
        assertNull(result.localResult)
        assertNull(result.cloudResult)
    }

    @Test
    fun deletingAParentWhileOtherSideAddsAChildDoesNotSilentlyDiscardTheChild() {
        val base = bundle().copy(weeklyPlans = listOf(plan("plan")))
        val cloud =
            base.copy(
                plannedWorkouts =
                    listOf(PlannedWorkout("workout", "plan", 1, "2026-09-14", "Workout"))
            )
        val result = ManualSyncMerger.analyze(base, bundle(), cloud)
        assertNull(result.localResult)
        assertNull(result.cloudResult)
    }

    @Test
    fun equalEditsAreNotConflictsAndMissingBaselineDoesNotAssumeLocalOwnership() {
        val changed = bundle(record("shared", 90.0))
        assertEquals(
            0,
            ManualSyncMerger.analyze(bundle(record("shared", 80.0)), changed, changed)
                .conflicts
                .values
                .sum()
        )
        assertEquals(
            1,
            ManualSyncMerger.analyze(null, changed, bundle(record("shared", 80.0)))
                .conflicts
                .values
                .sum()
        )
    }

    private fun bundle(vararg records: PersonalRecord) =
        BackupBundle(
            1,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            records.toList()
        )

    private fun record(id: String, weight: Double) =
        PersonalRecord(id, id, id, weight, "2026-09-18", createdAt = 1)

    private fun plan(id: String) =
        WeeklyPlan(id, startDate = "2026-09-14", endDate = "2026-09-20", createdAt = 1)
}
