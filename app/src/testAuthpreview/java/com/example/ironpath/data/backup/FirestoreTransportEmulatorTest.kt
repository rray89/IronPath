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
        assertEquals(inspected, fresh.inspect(account))
    }

    @Test
    fun actualRulesAllowImmutableResumeAndPreserveOldCompleteOnInterruptedUpload() = runBlocking {
        val owner = "owner-${UUID.randomUUID()}"
        val account = AccountId(owner)
        var interrupt = false
        val underlying = client(owner)
        val wrapped =
            object : FirestoreBackupClient by underlying {
                override suspend fun transaction(
                    block: suspend FirestoreBackupTransaction.() -> Unit
                ) {
                    var chunkWritten = false
                    underlying.transaction {
                        val delegate = this
                        val recording =
                            object : FirestoreBackupTransaction by delegate {
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
        val resumed =
            store(owner).publish(account, 1, "installation-a", changed)
                as RemoteBackupPublish.Completed
        assertEquals(2L, resumed.backup.generation)
        assertEquals(changed, resumed.backup.snapshot)
        val third =
            store(owner).publish(account, 2, "installation-a", snapshot(70.0))
                as RemoteBackupPublish.Completed
        assertEquals(3L, third.backup.generation)
        val user = underlying.get("users/$owner")!!
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
