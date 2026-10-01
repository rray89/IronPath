package com.example.ironpath.data.backup

import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.backup.BackupFailureReason
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FirestoreManualBackupStoreTest {
    @Test
    fun inspectionNeverWritesOrFetchesChunks() = runTest {
        val f = Fixture()
        assertEquals(RemoteBackupInspection.Absent(), f.store.inspect(f.owner))
        assertTrue(f.client.documents.isEmpty())
        assertTrue(f.client.reads.none { "/chunks/" in it })
        assertEquals(0, f.client.writes)
    }

    @Test
    fun publicationCompletesVerifiesBytesAndRecreatedClientReadsMetadataOnly() = runTest {
        val f = Fixture()
        val completed = f.publish() as RemoteBackupPublish.Completed
        assertEquals(1L, completed.backup.generation)
        assertEquals(f.snapshot, completed.backup.snapshot)
        assertEquals(1, f.client.documents.keys.count { "/chunks/" in it })
        f.client.reads.clear()
        val inspected = f.store.inspect(f.owner) as RemoteBackupInspection.Complete
        assertEquals(completed.backup.summary, inspected.backup.summary)
        assertTrue(f.client.reads.none { "/chunks/" in it })
        assertEquals(JsonNull, f.client.documents.getValue("users/owner")["activeUploadBackupId"])
    }

    @Test
    fun staleGenerationAndConcurrentClaimNeverForceWrite() = runTest {
        val f = Fixture()
        assertTrue(f.publish() is RemoteBackupPublish.Completed)
        val before = f.client.documents.toMap()
        assertEquals(
            RemoteBackupPublish.Failed(BackupFailureReason.ConcurrentRemoteChange),
            f.publish()
        )
        assertEquals(before, f.client.documents)
    }

    @Test
    fun interruptedChunkResumesSameImmutableSnapshotAndPreservesPreviousComplete() = runTest {
        val f = Fixture()
        f.client.failAfterChunk = true
        assertEquals(RemoteBackupPublish.Failed(BackupFailureReason.Offline), f.publish())
        assertEquals(RemoteBackupInspection.Absent(), f.store.inspect(f.owner))
        assertEquals(1, f.client.documents.keys.count { "/chunks/" in it })
        f.client.failAfterChunk = false
        assertTrue(f.publish() is RemoteBackupPublish.Completed)
        assertEquals(1, f.client.documents.keys.count { "/chunks/" in it })
    }

    @Test
    fun lostCompletionReceiptIsFoundByExplicitInspectionAndNotBlindlyRetried() = runTest {
        val f = Fixture()
        f.client.failAfterComplete = true
        assertEquals(RemoteBackupPublish.Failed(BackupFailureReason.Offline), f.publish())
        assertTrue(f.store.inspect(f.owner) is RemoteBackupInspection.Complete)
        val before = f.client.documents.toMap()
        assertEquals(
            RemoteBackupPublish.Failed(BackupFailureReason.ConcurrentRemoteChange),
            f.publish()
        )
        assertEquals(before, f.client.documents)
    }

    @Test
    fun otherSnapshotCannotReclaimAYoungUpload() = runTest {
        val f = Fixture()
        f.client.failAfterChunk = true
        f.publish()
        f.client.failAfterChunk = false
        val changed = f.snapshot.copy(localChangeRevision = 2)
        // The codec rejects inconsistent chunk revision before the upload is touched.
        assertEquals(
            RemoteBackupPublish.Failed(BackupFailureReason.InvalidSnapshot),
            f.publish(snapshot = changed)
        )
        val different = BackupSnapshotCodec().encode(f.bundle.copy(personalRecords = emptyList()))
        assertEquals(
            RemoteBackupPublish.Failed(BackupFailureReason.ConcurrentRemoteChange),
            f.publish(snapshot = different)
        )
    }

    @Test
    fun expiredUploadCleanupIsChunksFirstAndPreservesCompleteHistory() = runTest {
        val f = Fixture()
        f.client.failAfterChunk = true
        f.publish()
        f.client.failAfterChunk = false
        f.time.instant = f.time.instant.plusSeconds(24 * 60 * 60 + 1)
        val different = BackupSnapshotCodec().encode(f.bundle.copy(personalRecords = emptyList()))
        assertTrue(f.publish(snapshot = different) is RemoteBackupPublish.Completed)
        assertFalse(f.client.documents.keys.any { "/backup-1/" in it })
        assertFalse("users/owner/backups/backup-1" in f.client.documents)
    }

    @Test
    fun retentionKeepsTwoCompleteSnapshotsAndCleanupFailureStaysRegistered() = runTest {
        val f = Fixture()
        f.publish()
        f.publish(1)
        f.client.failCleanup = true
        assertTrue(f.publish(2) is RemoteBackupPublish.Completed)
        assertEquals(
            3,
            f.client.documents.getValue("users/owner").getValue("backupIds").jsonArray.size
        )
        f.client.failCleanup = false
        val latest = (f.store.inspect(f.owner) as RemoteBackupInspection.Complete).backup
        val writes = f.client.writes
        assertEquals(
            RemoteBackupRetention.Failed(BackupFailureReason.ConcurrentRemoteChange),
            f.store.retryRetention(f.owner, 2, latest.summary.backupId)
        )
        assertEquals(writes, f.client.writes)
        assertEquals(
            RemoteBackupRetention.Completed,
            f.store.retryRetention(f.owner, 3, latest.summary.backupId)
        )
        assertEquals(latest, (f.store.inspect(f.owner) as RemoteBackupInspection.Complete).backup)
        assertEquals(
            2,
            f.client.documents.getValue("users/owner").getValue("backupIds").jsonArray.size
        )
        assertEquals(2, f.client.documents.keys.count { "/backups/" in it && "/chunks/" !in it })
    }

    @Test
    fun malformedManifestAndUnsupportedFormatAreDistinctAndNeverReadPayload() = runTest {
        val f = Fixture()
        f.publish()
        val path = "users/owner/backups/backup-1"
        val original = f.client.documents.getValue(path)
        for ((key, value) in
            listOf(
                "contentDigest" to JsonPrimitive("bad"),
                "chunkCount" to JsonPrimitive(7),
                "state" to JsonPrimitive("UPLOADING"),
                "observedRemoteGeneration" to JsonPrimitive(99),
                "sourceInstallationId" to JsonPrimitive("other")
            )) {
            f.client.documents[path] = JsonObject(original + (key to value))
            assertEquals(
                RemoteBackupInspection.Failed(BackupFailureReason.InvalidSnapshot),
                f.store.inspect(f.owner)
            )
        }
        f.client.documents[path] = JsonObject(original + ("formatVersion" to JsonPrimitive(2)))
        assertEquals(
            RemoteBackupInspection.Failed(BackupFailureReason.UnsupportedVersion),
            f.store.inspect(f.owner)
        )
    }

    @Test
    fun corruptedImmutableChunkCannotComplete() = runTest {
        val f = Fixture()
        f.client.failAfterChunk = true
        f.publish()
        f.client.failAfterChunk = false
        val path = f.client.documents.keys.single { "/chunks/" in it }
        f.client.documents[path] =
            JsonObject(f.client.documents.getValue(path) + ("payload" to JsonPrimitive("tampered")))
        assertEquals(RemoteBackupPublish.Failed(BackupFailureReason.InvalidSnapshot), f.publish())
        assertEquals(RemoteBackupInspection.Absent(), f.store.inspect(f.owner))
    }

    @Test
    fun invalidAppVersionIsRejectedBeforeAnyCloudTouch() = runTest {
        val f = Fixture()
        var clients = 0
        for (version in listOf("", "x".repeat(65))) {
            val invalid =
                FirestoreManualBackupStore(
                    FirestoreBackupClientFactory {
                        clients++
                        f.client
                    },
                    IdProvider { "unused" },
                    f.time,
                    version
                )
            assertEquals(
                RemoteBackupPublish.Failed(BackupFailureReason.InvalidSnapshot),
                invalid.publish(f.owner, 0, "installation", f.snapshot)
            )
        }
        assertEquals(0, clients)
        assertEquals(0, f.client.writes)
    }

    private class Fixture {
        val owner = AccountId("owner")
        val client = Client()
        val time = FixedTime()
        var next = 0
        val store =
            FirestoreManualBackupStore(
                FirestoreBackupClientFactory { client },
                IdProvider { "backup-${++next}" },
                time,
                "test"
            )
        val bundle =
            BackupBundle(
                1,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                listOf(
                    PersonalRecord("record", "Squat", "squat", 50.0, "2026-09-20", createdAt = 1)
                )
            )
        val snapshot = BackupSnapshotCodec().encode(bundle)

        suspend fun publish(generation: Long = 0, snapshot: EncodedBackupSnapshot = this.snapshot) =
            store.publish(owner, generation, "installation", snapshot)
    }

    private class FixedTime : TimeProvider {
        var instant = Instant.parse("2026-09-30T00:00:00Z")
        override val zoneId = ZoneId.of("UTC")

        override fun now() = instant
    }

    private class Client : FirestoreBackupClient {
        val documents = mutableMapOf<String, JsonObject>()
        val reads = mutableListOf<String>()
        var writes = 0
        var failAfterChunk = false
        var failAfterComplete = false
        var failCleanup = false

        override suspend fun get(path: String): JsonObject? {
            reads += path
            return documents[path]
        }

        override suspend fun transaction(block: suspend FirestoreBackupTransaction.() -> Unit) {
            val staged = documents.toMutableMap()
            var chunk = false
            var complete = false
            var count = 0
            val transaction =
                object : FirestoreBackupTransaction {
                    override suspend fun get(path: String) = this@Client.get(path)

                    override fun put(path: String, fields: JsonObject, serverTimes: Set<String>) {
                        staged[path] =
                            JsonObject(
                                fields +
                                    serverTimes.associateWith {
                                        JsonPrimitive("2026-09-30T00:00:00Z")
                                    }
                            )
                        chunk = chunk || "/chunks/" in path
                        complete = complete || fields["state"] == JsonPrimitive("COMPLETE")
                        count++
                    }

                    override fun delete(path: String) {
                        if (failCleanup)
                            throw CloudBackupFailure(BackupFailureReason.PermissionDenied)
                        staged.remove(path)
                        count++
                    }
                }
            transaction.block()
            documents.clear()
            documents.putAll(staged)
            writes += count
            if (chunk && failAfterChunk || complete && failAfterComplete)
                throw CloudBackupFailure(BackupFailureReason.Offline)
        }
    }
}
