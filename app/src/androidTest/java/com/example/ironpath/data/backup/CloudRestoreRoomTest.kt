package com.example.ironpath.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.account.AccountSessionOperationGate
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.account.AccountProfile
import com.example.ironpath.domain.backup.*
import com.example.ironpath.testutil.FakeAccountSessionAdapter
import com.example.ironpath.testutil.FileBackedRoomTestDatabaseRule
import com.example.ironpath.testutil.SequenceIdProvider
import com.example.ironpath.testutil.TestData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudRestoreRoomTest {
    @get:Rule val databases = FileBackedRoomTestDatabaseRule()

    @Test
    fun cloudCoordinatorReplacesEntireGraphAndColdUndoRestoresMatchingBaselineOnce() = runBlocking {
        val database = databases.open()
        val local = RoomBackupStore(database, SequenceIdProvider("local-installation"))
        val account = AccountId("cloud-owner")
        database.recordDao().insertRecord(TestData.record(id = "old-record", weightKg = 50.0))
        local.markIncludedDataChanged()
        val base = artifact(local.capture().bundle, 1)
        assertTrue(local.recordBackup(local.capture(), account, base))
        database.backupDao().deletePersonalRecords()
        database.recordDao().insertRecord(TestData.record(id = "old-record", weightKg = 60.0))
        local.markIncludedDataChanged()
        val before = local.capture()
        val target =
            artifact(
                before.bundle.copy(
                    weeklyPlans = listOf(TestData.plan()),
                    plannedWorkouts = listOf(TestData.workout()),
                    plannedExercises = listOf(TestData.plannedExercise()),
                    workoutLogs = listOf(TestData.log()),
                    loggedExercises = listOf(TestData.loggedExercise()),
                    loggedSets = listOf(TestData.loggedSet()),
                    personalRecords = listOf(TestData.record(id = "cloud-record")),
                ),
                2
            )
        val remote = ReadOnlyRemote(target)
        val first = coordinator(local, remote, account)
        val preview = (first.previewRestore() as RestorePreviewResult.Ready).preview
        assertEquals(BackupCategoryImpact(1, 0, 1), preview.impact["PersonalRecord"])
        assertEquals(before, local.capture())
        assertEquals(BackupActionResult.Completed, first.confirmRestore(preview.id))
        val after = local.capture()
        assertEquals(
            target.snapshot.contentDigest,
            BackupSnapshotCodec().encode(after.bundle).contentDigest
        )
        assertEquals(target, after.baseline)
        assertEquals(before.metadata.localChangeRevision + 1, after.metadata.localChangeRevision)
        assertTrue(first.undoAvailable.value)
        database.close()

        val reopened = databases.open()
        val reopenedLocal = RoomBackupStore(reopened, SequenceIdProvider("unused"))
        val restarted = coordinator(reopenedLocal, remote, account)
        restarted.refreshStatus()
        assertTrue(restarted.undoAvailable.value)
        val reads = remote.reads
        val undo = (restarted.previewUndo() as UndoPreviewResult.Ready).preview
        assertEquals(BackupActionResult.Completed, restarted.confirmUndo(undo.id))
        val undone = reopenedLocal.capture()
        assertEquals(
            before.bundle.copy(localChangeRevision = undone.metadata.localChangeRevision),
            undone.bundle
        )
        assertEquals(before.baseline, undone.baseline)
        assertEquals(before.metadata.installationId, undone.metadata.installationId)
        assertEquals(before.metadata.profileGeneration, undone.metadata.profileGeneration)
        assertEquals(BackupStatus.LocalChanges, restarted.status.value)
        assertEquals(reads, remote.reads)
        assertFalse(restarted.undoAvailable.value)
        assertEquals(UndoPreviewResult.Unavailable, restarted.previewUndo())
        assertEquals(target, remote.artifact)
        val freshRestore = (restarted.previewRestore() as RestorePreviewResult.Ready).preview
        assertEquals(BackupActionResult.Completed, restarted.confirmRestore(freshRestore.id))
        assertTrue(restarted.undoAvailable.value)
        reopenedLocal.resetLocalProfile()
        restarted.refreshStatus()
        assertFalse(restarted.undoAvailable.value)
        assertEquals(UndoPreviewResult.Unavailable, restarted.previewUndo())
    }

    private fun coordinator(local: RoomBackupStore, remote: ReadOnlyRemote, account: AccountId) =
        CloudManualBackupCoordinator(
            local,
            remote,
            FakeAccountSessionAdapter(remote).apply {
                session = AccountProfile(account, "Cloud test", "")
            },
            object : InstallationGuard {
                override suspend fun validate() = InstallationValidationResult.Validated
            },
            SequenceIdProvider("preview"),
            AccountSessionOperationGate()
        )

    private fun artifact(bundle: BackupBundle, generation: Long): RemoteBackupArtifact {
        val snapshot = BackupSnapshotCodec().encode(bundle)
        return RemoteBackupArtifact(
            RemoteBackupSummary(
                "cloud-$generation",
                TestData.BASE_TIME,
                "other-installation",
                snapshot.entityCounts
            ),
            generation,
            snapshot
        )
    }

    private class ReadOnlyRemote(val artifact: RemoteBackupArtifact) : RemoteBackupStore {
        var reads = 0

        override suspend fun latest(accountId: AccountId): RemoteBackupRead {
            reads++
            return RemoteBackupRead.Complete(artifact)
        }

        override suspend fun publish(
            accountId: AccountId,
            expectedGeneration: Long,
            sourceInstallationId: String,
            snapshot: EncodedBackupSnapshot
        ): RemoteBackupPublish = error("Restore and undo must never publish")

        override suspend fun retryRetention(
            accountId: AccountId,
            expectedGeneration: Long,
            latestBackupId: String
        ): RemoteBackupRetention = error("Restore and undo must never clean up cloud data")
    }
}
