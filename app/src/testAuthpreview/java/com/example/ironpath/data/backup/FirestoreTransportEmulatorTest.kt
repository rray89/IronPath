package com.example.ironpath.data.backup

import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.backup.BackupFailureReason
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Run only through firestoreTransportTest inside demo-ironpath's Firestore emulator. */
class FirestoreTransportEmulatorTest {
    @Test
    fun actualPayloadDownloadThreeWayMergeAndPublicationRemainGenerationAndOwnerScoped() =
        runBlocking {
            val owner = "sync-${UUID.randomUUID()}"
            val account = AccountId(owner)
            val first = store(owner)
            val codec = BackupSnapshotCodec()
            val base = codec.decode(snapshot())
            val original = base.personalRecords.single()
            first.publish(account, 0, "installation-a", codec.encode(base))
                as RemoteBackupPublish.Completed
            val local =
                base.copy(
                    localChangeRevision = 2,
                    personalRecords =
                        listOf(
                            original.copy(weightKg = 60.0),
                            original.copy(
                                id = "local",
                                exerciseName = "Press",
                                normalizedExerciseName = "press"
                            )
                        )
                )
            val cloud =
                base.copy(
                    localChangeRevision = 3,
                    personalRecords =
                        listOf(
                            original.copy(weightKg = 70.0),
                            original.copy(
                                id = "cloud",
                                exerciseName = "Row",
                                normalizedExerciseName = "row"
                            )
                        )
                )
            store(owner).publish(account, 1, "installation-b", codec.encode(cloud))
                as RemoteBackupPublish.Completed
            val downloaded = (first.latest(account) as RemoteBackupRead.Complete).backup
            val analysis = ManualSyncMerger.analyze(base, local, downloaded.validatedBundle())
            assertEquals(1, analysis.conflicts["PersonalRecord"])
            for (candidate in listOf(analysis.localResult!!, analysis.cloudResult!!)) {
                assertEquals(
                    setOf("record", "local", "cloud"),
                    candidate.personalRecords.map { it.id }.toSet()
                )
            }
            val chosen = codec.encode(analysis.localResult!!)
            val completed =
                first.publish(account, downloaded.generation, "installation-a", chosen)
                    as RemoteBackupPublish.Completed
            assertEquals(3L, completed.backup.generation)
            assertEquals(RemoteBackupRead.Complete(completed.backup), store(owner).latest(account))
            assertEquals(
                RemoteBackupRead.Failed(BackupFailureReason.PermissionDenied),
                store("other").latest(account)
            )
            assertEquals(
                RemoteBackupRead.Failed(BackupFailureReason.PermissionDenied),
                store(null).latest(account)
            )
            assertEquals(
                RemoteBackupPublish.Failed(BackupFailureReason.ConcurrentRemoteChange),
                store(owner).publish(account, 2, "installation-b", codec.encode(cloud))
            )
            assertEquals(RemoteBackupRead.Complete(completed.backup), first.latest(account))
        }

    @Test
    fun actualKotlinRestPublicationIsOwnerScopedAndMetadataReadSurvivesNewClient() = runBlocking {
        val owner = "owner-${UUID.randomUUID()}"
        val account = AccountId(owner)
        val store = store(owner)
        assertEquals(RemoteBackupInspection.Absent(), store.inspect(account))
        val snapshot = snapshot()
        val completed =
            store.publish(account, 0, "installation-a", snapshot) as RemoteBackupPublish.Completed
        assertEquals(1L, completed.backup.generation)
        assertEquals(snapshot, completed.backup.snapshot)
        val fresh = store(owner)
        val inspected = fresh.inspect(account) as RemoteBackupInspection.Complete
        assertEquals(completed.backup.summary, inspected.backup.summary)
        assertEquals(
            RemoteBackupInspection.Failed(BackupFailureReason.PermissionDenied),
            store("other").inspect(account)
        )
        assertEquals(
            RemoteBackupInspection.Failed(BackupFailureReason.PermissionDenied),
            store(null).inspect(account)
        )
        assertEquals(
            RemoteBackupPublish.Failed(BackupFailureReason.PermissionDenied),
            store("other").publish(account, 1, "intruder", snapshot)
        )
        assertEquals(
            RemoteBackupPublish.Failed(BackupFailureReason.ConcurrentRemoteChange),
            fresh.publish(account, 0, "installation-b", snapshot)
        )
        assertEquals(
            RemoteBackupRetention.Failed(BackupFailureReason.PermissionDenied),
            store("other").retryRetention(account, 1, completed.backup.summary.backupId)
        )
        assertEquals(
            RemoteBackupRetention.Failed(BackupFailureReason.PermissionDenied),
            store(null).retryRetention(account, 1, completed.backup.summary.backupId)
        )
        assertEquals(inspected, fresh.inspect(account))
    }

    @Test
    fun actualRulesAllowImmutableResumeAndPreserveOldCompleteOnInterruptedUpload() = runBlocking {
        val owner = "owner-${UUID.randomUUID()}"
        val account = AccountId(owner)
        var interrupt = false
        var interruptRetention = false
        val underlying = client(owner)
        val wrapped =
            object : FirestoreBackupClient by underlying {
                override suspend fun transaction(
                    block: suspend FirestoreBackupTransaction.() -> Unit
                ) {
                    var chunkWritten = false
                    var cleanupWritten = false
                    underlying.transaction {
                        val delegate = this
                        val recording =
                            object : FirestoreBackupTransaction by delegate {
                                override fun delete(path: String) {
                                    cleanupWritten = cleanupWritten || "/backups/" in path
                                    delegate.delete(path)
                                }

                                override fun put(
                                    path: String,
                                    fields: JsonObject,
                                    serverTimes: Set<String>
                                ) {
                                    chunkWritten = chunkWritten || "/chunks/" in path
                                    delegate.put(path, fields, serverTimes)
                                }
                            }
                        recording.block()
                    }
                    if (cleanupWritten && interruptRetention) {
                        interruptRetention = false
                        throw CloudBackupFailure(BackupFailureReason.Offline)
                    }
                    if (chunkWritten && interrupt) {
                        interrupt = false
                        throw CloudBackupFailure(BackupFailureReason.Offline)
                    }
                }
            }
        val store = store(owner, wrapped)
        val previous =
            store.publish(account, 0, "installation-a", snapshot()) as RemoteBackupPublish.Completed
        val changed = snapshot(60.0)
        interrupt = true
        assertEquals(
            RemoteBackupPublish.Failed(BackupFailureReason.Offline),
            store.publish(account, 1, "installation-a", changed)
        )
        assertEquals(
            previous.backup.summary,
            (store(owner).inspect(account) as RemoteBackupInspection.Complete).backup.summary
        )
        assertEquals(RemoteBackupRead.Complete(previous.backup), store(owner).latest(account))
        val resumed =
            store(owner).publish(account, 1, "installation-a", changed)
                as RemoteBackupPublish.Completed
        assertEquals(2L, resumed.backup.generation)
        assertEquals(changed, resumed.backup.snapshot)
        interruptRetention = true
        val third =
            store.publish(account, 2, "installation-a", snapshot(70.0))
                as RemoteBackupPublish.Completed
        assertEquals(3L, third.backup.generation)
        assertEquals(3, underlying.get("users/$owner")!!.getValue("backupIds").jsonArray.size)
        assertEquals(
            RemoteBackupRetention.Completed,
            store(owner).retryRetention(account, 3, third.backup.summary.backupId)
        )
        val user = underlying.get("users/$owner")!!
        assertEquals(3L, user.getValue("generation").jsonPrimitive.long)
        assertEquals(2, user.getValue("backupIds").jsonArray.size)
        assertNull(underlying.get("users/$owner/backups/${previous.backup.summary.backupId}"))
    }

    @Test
    fun lostCompletionReceiptRemainsDiscoverableWithoutAnotherUpload() = runBlocking {
        val owner = "owner-${UUID.randomUUID()}"
        val account = AccountId(owner)
        val underlying = client(owner)
        val wrapped =
            object : FirestoreBackupClient by underlying {
                override suspend fun transaction(
                    block: suspend FirestoreBackupTransaction.() -> Unit
                ) {
                    var completed = false
                    underlying.transaction {
                        val delegate = this
                        val recording =
                            object : FirestoreBackupTransaction by delegate {
                                override fun put(
                                    path: String,
                                    fields: JsonObject,
                                    serverTimes: Set<String>
                                ) {
                                    completed =
                                        completed || fields["state"] == JsonPrimitive("COMPLETE")
                                    delegate.put(path, fields, serverTimes)
                                }
                            }
                        recording.block()
                    }
                    if (completed) throw CloudBackupFailure(BackupFailureReason.Offline)
                }
            }
        assertEquals(
            RemoteBackupPublish.Failed(BackupFailureReason.Offline),
            store(owner, wrapped).publish(account, 0, "installation-a", snapshot())
        )
        val fresh = store(owner)
        val found = fresh.inspect(account) as RemoteBackupInspection.Complete
        assertEquals(1L, found.backup.generation)
        val recovered = (fresh.latest(account) as RemoteBackupRead.Complete).backup
        assertEquals(snapshot(), recovered.snapshot)
        assertEquals(
            RemoteBackupPublish.Failed(BackupFailureReason.ConcurrentRemoteChange),
            fresh.publish(account, 0, "installation-a", snapshot())
        )
        assertEquals(found, fresh.inspect(account))
    }

    private fun snapshot(weight: Double = 50.0) =
        BackupSnapshotCodec()
            .encode(
                BackupBundle(
                    1,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    listOf(
                        PersonalRecord(
                            "record",
                            "Squat",
                            "squat",
                            weight,
                            "2026-09-20",
                            createdAt = 1
                        )
                    )
                )
            )

    private fun client(uid: String?) =
        FirestoreBackupRestClient(
            "demo-ironpath",
            { uid?.let(::emulatorToken).orEmpty() },
            { true },
            "http://127.0.0.1:8080/v1"
        )

    private fun store(uid: String?, client: FirestoreBackupClient = client(uid)) =
        FirestoreManualBackupStore(
            FirestoreBackupClientFactory { client },
            IdProvider { UUID.randomUUID().toString() },
            object : TimeProvider {
                override val zoneId = ZoneId.of("UTC")

                override fun now() = Instant.now()
            },
            "transport-test"
        )

    private fun emulatorToken(uid: String): String {
        val now = Instant.now().epochSecond
        val payload = buildJsonObject {
            put("iss", "https://securetoken.google.com/demo-ironpath")
            put("aud", "demo-ironpath")
            put("sub", uid)
            put("user_id", uid)
            put("iat", now)
            put("exp", now + 3600)
            put(
                "firebase",
                buildJsonObject {
                    put("sign_in_provider", "custom")
                    put("identities", buildJsonObject {})
                }
            )
        }
        fun encode(text: String) =
            Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        return encode("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + encode(payload.toString()) + "."
    }
}
