package com.example.ironpath.data.backup

import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.backup.BackupFailureReason
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeterministicRemoteBackupStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val account = AccountId("demo-owner")
    private val ids = AtomicInteger()
    private val idProvider = IdProvider { "backup-${ids.incrementAndGet()}" }
    private var now = 1_800_000_000_000L
    private val timeProvider =
        object : TimeProvider {
            override val zoneId = ZoneId.of("UTC")

            override fun now() = Instant.ofEpochMilli(now)
        }

    @Test
    fun publishSurvivesFreshStoreAndKeepsOnlyTwoCompleteSnapshots() = runTest {
        val directory = temporary.newFolder()
        val first = store(directory)
        assertEquals(RemoteBackupRead.Absent(), first.latest(account))
        repeat(4) { index ->
            val result =
                first.publish(account, index.toLong(), "installation-a", snapshot(index + 1))
                    as RemoteBackupPublish.Completed
            assertEquals(index + 1L, result.backup.generation)
            assertEquals(snapshot(index + 1), result.backup.snapshot)
        }

        val recreated = store(directory)
        val latest = (recreated.latest(account) as RemoteBackupRead.Complete).backup
        assertEquals(4L, latest.generation)
        assertEquals("backup-4", latest.summary.backupId)
        assertEquals(snapshot(4), latest.snapshot)
        val stored = stateFile(directory).readText()
        assertFalse(stored.contains("backup-1"))
        assertFalse(stored.contains("backup-2"))
        assertTrue(stored.contains("backup-3"))
        assertTrue(stored.contains("backup-4"))
    }

    @Test
    fun competingStoreInstancesCannotCompleteTheSameGeneration() = runTest {
        val directory = temporary.newFolder()
        val results =
            listOf("installation-a", "installation-b")
                .map { source ->
                    async { store(directory).publish(account, 0, source, snapshot(1)) }
                }
                .awaitAll()

        assertEquals(1, results.filterIsInstance<RemoteBackupPublish.Completed>().size)
        assertEquals(
            listOf(RemoteBackupPublish.Failed(BackupFailureReason.ConcurrentRemoteChange)),
            results.filterIsInstance<RemoteBackupPublish.Failed>(),
        )
        assertEquals(
            1L,
            (store(directory).latest(account) as RemoteBackupRead.Complete).backup.generation
        )
    }

    @Test
    fun interruptedUploadDoesNotReplaceCompletePointerAndExplicitRetryResumesIt() = runTest {
        val directory = temporary.newFolder()
        val original =
            store(directory).publish(account, 0, "installation-a", snapshot(1))
                as RemoteBackupPublish.Completed
        val broken =
            store(directory) { phase ->
                if (phase == DebugRemoteWritePhase.ChunkWritten)
                    throw IOException("test interruption")
            }

        assertEquals(
            RemoteBackupPublish.Failed(BackupFailureReason.ServiceUnavailable),
            broken.publish(account, 1, "installation-a", snapshot(2)),
        )
        assertEquals(RemoteBackupRead.Complete(original.backup), store(directory).latest(account))
        assertEquals(
            RemoteBackupPublish.Failed(BackupFailureReason.ConcurrentRemoteChange),
            store(directory).publish(account, 1, "installation-b", snapshot(3)),
        )
        val retried =
            store(directory).publish(account, 1, "installation-a", snapshot(2))
                as RemoteBackupPublish.Completed
        assertEquals("backup-2", retried.backup.summary.backupId)
        assertEquals(2L, retried.backup.generation)
    }

    @Test
    fun expiredUploadIsReclaimedUsingRegisteredChunksBeforeAnotherClaim() = runTest {
        val directory = temporary.newFolder()
        val broken =
            store(directory) { phase ->
                if (phase == DebugRemoteWritePhase.ChunkWritten)
                    throw IOException("test interruption")
            }
        broken.publish(account, 0, "installation-a", snapshot(1))
        assertEquals(RemoteBackupRead.Absent(), store(directory).latest(account))
        now += 24 * 60 * 60 * 1_000L

        val completed =
            store(directory).publish(account, 0, "installation-b", snapshot(2))
                as RemoteBackupPublish.Completed
        assertEquals(1L, completed.backup.generation)
        assertFalse(stateFile(directory).readText().contains("backup-1"))
    }

    @Test
    fun failedCleanupRetainsDiscoverableRegistryAndNextExplicitPublishRetriesIt() = runTest {
        val directory = temporary.newFolder()
        store(directory).publish(account, 0, "installation-a", snapshot(1))
        store(directory).publish(account, 1, "installation-a", snapshot(2))
        val broken =
            store(directory) { phase ->
                if (phase == DebugRemoteWritePhase.RetentionChunksDeleted)
                    throw IOException("cleanup")
            }

        val completed = broken.publish(account, 2, "installation-a", snapshot(3))
        assertTrue(completed is RemoteBackupPublish.Completed)
        assertTrue(stateFile(directory).readText().contains("backup-1"))
        assertEquals(
            3L,
            (store(directory).latest(account) as RemoteBackupRead.Complete).backup.generation
        )
        assertTrue(
            store(directory).publish(account, 3, "installation-a", snapshot(4))
                is RemoteBackupPublish.Completed
        )
        assertFalse(stateFile(directory).readText().contains("backup-1"))
        assertFalse(stateFile(directory).readText().contains("backup-2"))
    }

    @Test
    fun malformedSnapshotAndFutureFormatDoNotCreateAnyState() = runTest {
        val directory = temporary.newFolder()
        val valid = snapshot(1)
        val malformed = valid.copy(contentDigest = "0".repeat(64))

        assertEquals(
            RemoteBackupPublish.Failed(BackupFailureReason.InvalidSnapshot),
            store(directory).publish(account, 0, "installation-a", malformed),
        )
        assertEquals(
            RemoteBackupPublish.Failed(BackupFailureReason.UnsupportedVersion),
            store(directory).publish(account, 0, "installation-a", valid.copy(formatVersion = 2)),
        )
        assertEquals(RemoteBackupRead.Absent(), store(directory).latest(account))
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "json" })
    }

    @Test
    fun corruptPersistentStateFailsClosedAndNeverOverwritesIt() = runTest {
        val directory = temporary.newFolder()
        store(directory).publish(account, 0, "installation-a", snapshot(1))
        val file = stateFile(directory)
        file.writeText("broken-state")

        assertEquals(
            RemoteBackupRead.Failed(BackupFailureReason.InvalidSnapshot),
            store(directory).latest(account),
        )
        assertEquals(
            RemoteBackupPublish.Failed(BackupFailureReason.InvalidSnapshot),
            store(directory).publish(account, 0, "installation-a", snapshot(2)),
        )
        assertEquals("broken-state", file.readText())
    }

    @Test
    fun accountIdentifiersCannotEscapeDirectoryAndAccountsRemainSeparate() = runTest {
        val directory = temporary.newFolder()
        val oddAccount = AccountId("../../other-account")
        assertTrue(
            store(directory).publish(oddAccount, 0, "installation-a", snapshot(1))
                is RemoteBackupPublish.Completed
        )
        assertEquals(RemoteBackupRead.Absent(), store(directory).latest(account))
        assertTrue(directory.listFiles().orEmpty().all { it.parentFile == directory })
        assertTrue(store(directory).latest(oddAccount) is RemoteBackupRead.Complete)
    }

    @Test
    fun returnedMutableCollectionsCannotChangePersistedData() = runTest {
        val directory = temporary.newFolder()
        val result =
            store(directory).publish(account, 0, "installation-a", snapshot(1))
                as RemoteBackupPublish.Completed
        runCatching { (result.backup.snapshot.chunks as MutableList<BackupChunk>).clear() }
        runCatching {
            (result.backup.snapshot.entityCounts as MutableMap<String, Int>)["PersonalRecord"] = 900
        }

        assertEquals(
            snapshot(1),
            (store(directory).latest(account) as RemoteBackupRead.Complete).backup.snapshot
        )
    }

    private fun store(directory: File, hook: (DebugRemoteWritePhase) -> Unit = {}) =
        DeterministicRemoteBackupStore(directory, idProvider, timeProvider, hook)

    private fun stateFile(directory: File) =
        directory.listFiles()!!.single { it.extension == "json" }

    private fun snapshot(revision: Int): EncodedBackupSnapshot =
        BackupSnapshotCodec()
            .encode(
                BackupBundle(
                    revision.toLong(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    listOf(
                        PersonalRecord(
                            "record-a",
                            "Squat",
                            "squat",
                            revision * 10.0,
                            "2026-09-18",
                            createdAt = 1
                        )
                    ),
                )
            )
}
