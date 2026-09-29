package com.example.ironpath.data.backup

import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeterministicRemoteAccountPurgeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun purgeLeavesVerifiedTombstoneAndRejectsEveryLaterPublish() = runTest {
        val directory = temporaryFolder.newFolder("remote")
        val store = newStore(directory)
        val account = AccountId("demo-incarnation-1")
        val snapshot = BackupSnapshotCodec().encode(emptyBundle())

        assertTrue(
            store.publish(account, 0, "installation-a", snapshot) is RemoteBackupPublish.Completed
        )
        assertTrue(store.latest(account) is RemoteBackupRead.Complete)
        assertEquals(RemoteAccountPurge.Completed, store.purgeAccount(account))

        val afterDelete = store.latest(account) as RemoteBackupRead.Absent
        assertEquals(2, afterDelete.generation)
        assertEquals(
            RemoteBackupPublish.Failed(
                com.example.ironpath.domain.backup.BackupFailureReason.PermissionDenied
            ),
            store.publish(account, afterDelete.generation, "installation-a", snapshot),
        )
        val stateFile = directory.listFiles().orEmpty().single { it.extension == "json" }
        val contents = stateFile.readText()
        assertTrue(contents.contains("\"deleted\":true"))
        assertFalse(contents.contains(snapshot.chunks.first().payload))
        assertFalse(File(directory, "${stateFile.name}.tmp").exists())
    }

    @Test
    fun interruptedPurgeCanBeRetriedByRecreatedStore() = runTest {
        val directory = temporaryFolder.newFolder("remote-restart")
        val account = AccountId("demo-incarnation-2")
        val snapshot = BackupSnapshotCodec().encode(emptyBundle())
        val writer = newStore(directory)
        assertTrue(
            writer.publish(account, 0, "installation-b", snapshot) is RemoteBackupPublish.Completed
        )

        var interruptAfterCommit = true
        val interrupted =
            DeterministicRemoteBackupStore(directory, SequenceIds(), FixedTime()) { phase ->
                if (phase == DebugRemoteWritePhase.AccountPurgeCommitted && interruptAfterCommit) {
                    interruptAfterCommit = false
                    error("simulated interruption after durable tombstone")
                }
            }
        assertTrue(interrupted.purgeAccount(account) is RemoteAccountPurge.Failed)
        assertTrue(interrupted.latest(account) is RemoteBackupRead.Absent)

        val recreated = newStore(directory)
        assertEquals(RemoteAccountPurge.Completed, recreated.purgeAccount(account))
        val absent = recreated.latest(account) as RemoteBackupRead.Absent
        assertEquals(2, absent.generation)
        assertTrue(
            recreated.publish(account, absent.generation, "installation-b", snapshot)
                is RemoteBackupPublish.Failed
        )
    }

    private fun newStore(directory: File) =
        DeterministicRemoteBackupStore(directory, SequenceIds(), FixedTime()) {}

    private fun emptyBundle() =
        BackupBundle(
            localChangeRevision = 0,
            weeklyPlans = emptyList(),
            plannedWorkouts = emptyList(),
            plannedExercises = emptyList(),
            workoutLogs = emptyList(),
            loggedExercises = emptyList(),
            loggedSets = emptyList(),
            personalRecords = emptyList(),
        )

    private class SequenceIds : IdProvider {
        private var next = 0

        override fun newId(): String = "backup-${++next}"
    }

    private class FixedTime : TimeProvider {
        override val zoneId = ZoneId.of("UTC")

        override fun now() = Instant.ofEpochMilli(1_000)

        override fun today() = LocalDate.of(2026, 9, 29)
    }
}
