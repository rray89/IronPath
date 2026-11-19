package com.example.ironpath.data.backup

import com.example.ironpath.data.account.AccountSessionOperationGate
import com.example.ironpath.data.local.entity.AccountBackupMetadata
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.domain.account.*
import com.example.ironpath.domain.backup.*
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.mockk
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Run only through firestoreTransportTest inside demo-ironpath's Firestore emulator. */
class FirestoreTransportEmulatorTest {
    @Test
    fun syncPreviewCancelAndConfirmedOneSidedMergeWorkWithBeginDenied() = runBlocking {
        assertExplicitSyncWithBeginDenied(null)
    }

    @Test
    fun syncKeepsLocalConflictVersionsOnlyAfterExplicitChoiceWithBeginDenied() = runBlocking {
        assertExplicitSyncWithBeginDenied(SyncConflictResolution.KeepLocal)
    }

    @Test
    fun syncKeepsCloudConflictVersionsOnlyAfterExplicitChoiceWithBeginDenied() = runBlocking {
        assertExplicitSyncWithBeginDenied(SyncConflictResolution.KeepCloud)
    }

    @Test
    fun deniedSyncCommitPreservesLocalDataAndAllowsFreshExplicitRetry() = runBlocking {
        assertExplicitSyncWithBeginDenied(
            SyncConflictResolution.KeepLocal,
            BackupFailureReason.PermissionDenied
        )
    }

    @Test
    fun lostSyncReceiptPreservesLocalDataAndRecoversThroughFreshReview() = runBlocking {
        assertExplicitSyncWithBeginDenied(
            SyncConflictResolution.KeepLocal,
            BackupFailureReason.Offline
        )
    }

    private suspend fun assertExplicitSyncWithBeginDenied(
        choice: SyncConflictResolution?,
        failure: BackupFailureReason? = null
    ) {
        val owner = "sync-${UUID.randomUUID()}"
        val account = AccountId(owner)
        val codec = BackupSnapshotCodec()
        DeniedBeginProxy().use { proxy ->
            val transport = client(owner, proxy.endpoint)
            val local = LocalFixture(snapshot())
            var subject = coordinator(owner, local, transport)
            val backup = (subject.previewBackup() as BackupPreviewResult.Ready).preview
            assertEquals(BackupActionResult.Completed, subject.confirmBackup(backup.id))
            val base = local.value.bundle
            val original = base.personalRecords.single()
            val localBundle =
                base.copy(
                    localChangeRevision = 2,
                    personalRecords =
                        listOf(
                            original.copy(weightKg = 60.0),
                            original.copy(
                                id = "local-only",
                                exerciseName = "Press",
                                normalizedExerciseName = "press"
                            )
                        )
                )
            local.value =
                local.value.copy(
                    metadata = local.value.metadata.copy(localChangeRevision = 2),
                    bundle = localBundle
                )
            val cloud =
                base.copy(
                    localChangeRevision = 2,
                    personalRecords =
                        listOf(
                            original.copy(weightKg = if (choice == null) 50.0 else 70.0),
                            original.copy(
                                id = "cloud-only",
                                exerciseName = "Row",
                                normalizedExerciseName = "row"
                            )
                        )
                )
            val remote = store(owner, transport)
            val cloudBefore =
                (remote.publish(account, 1, "installation-b", codec.encode(cloud))
                        as RemoteBackupPublish.Completed)
                    .backup
            val before = local.value
            proxy.commits.set(0)
            // Plain backup must retain main's cross-device guard; only sync can merge this data.
            assertEquals(
                BackupPreviewResult.Failed(BackupFailureReason.ConcurrentRemoteChange),
                subject.previewBackup()
            )
            val cancelled = (subject.previewSync() as SyncPreviewResult.Ready).preview
            assertEquals(if (choice == null) 0 else 1, cancelled.conflicts["PersonalRecord"] ?: 0)
            assertEquals(before, local.value)
            assertEquals(0, proxy.commits.get())
            subject.discardPreview(cancelled.id)
            assertEquals(
                BackupActionResult.Failed(BackupFailureReason.StalePreview),
                subject.confirmSync(cancelled.id, choice)
            )
            assertEquals(before, local.value)
            assertEquals(RemoteBackupRead.Complete(cloudBefore), remote.latest(account))
            assertEquals(0, proxy.commits.get())
            var preview = (subject.previewSync() as SyncPreviewResult.Ready).preview
            if (choice != null) {
                assertEquals(
                    BackupActionResult.Failed(BackupFailureReason.ConflictChoiceRequired),
                    subject.confirmSync(preview.id)
                )
                assertEquals(before, local.value)
                assertEquals(0, proxy.commits.get())
            }
            if (failure != null) {
                proxy.denyCommits = failure == BackupFailureReason.PermissionDenied
                proxy.loseCompletionReceipt = failure == BackupFailureReason.Offline
                assertEquals(
                    BackupActionResult.Failed(failure),
                    subject.confirmSync(preview.id, choice)
                )
                assertEquals(before, local.value)
                assertFalse(subject.status.value is BackupStatus.UpToDate)
                if (failure == BackupFailureReason.PermissionDenied)
                    assertEquals(1, proxy.commits.get())
                proxy.denyCommits = false
                val afterFailure = (remote.latest(account) as RemoteBackupRead.Complete).backup
                assertEquals(
                    if (failure == BackupFailureReason.Offline) 3L else 2L,
                    afterFailure.generation
                )
                subject = coordinator(owner, local, transport)
                assertEquals(
                    BackupPreviewResult.Failed(BackupFailureReason.ConcurrentRemoteChange),
                    subject.previewBackup()
                )
                preview = (subject.previewSync() as SyncPreviewResult.Ready).preview
            }
            assertEquals(BackupActionResult.Completed, subject.confirmSync(preview.id, choice))
            val merged = (remote.latest(account) as RemoteBackupRead.Complete).backup
            assertEquals(3L, merged.generation)
            assertEquals(
                setOf("record", "local-only", "cloud-only"),
                local.value.bundle.personalRecords.map { it.id }.toSet()
            )
            assertEquals(
                if (choice == SyncConflictResolution.KeepCloud) 70.0 else 60.0,
                local.value.bundle.personalRecords.single { it.id == "record" }.weightKg,
                0.0
            )
            assertEquals(
                local.value.bundle.personalRecords,
                merged.validatedBundle().personalRecords
            )
            assertEquals(merged, local.value.baseline)
            assertTrue(subject.status.value is BackupStatus.UpToDate)
            assertTrue(proxy.commits.get() > 0)
            assertTrue(proxy.paths.none { ":beginTransaction" in it || ":rollback" in it })
        }
    }

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
    fun firstConfirmedBackupCreatesMetadataThroughTheCoordinatorWithBeginDenied() = runBlocking {
        val owner = "owner-${UUID.randomUUID()}"
        DeniedBeginProxy().use { proxy ->
            val transport = client(owner, proxy.endpoint)
            val local = LocalFixture(snapshot())
            val subject = coordinator(owner, local, transport)
            assertEquals(BackupLookupResult.Absent, subject.latestCompleteBackup())
            val preview = (subject.previewBackup() as BackupPreviewResult.Ready).preview
            assertNull(transport.get("users/$owner"))
            assertEquals(0, proxy.commits.get())
            assertNull(local.value.metadata.ownerUid)
            assertEquals(BackupActionResult.Completed, subject.confirmBackup(preview.id))
            assertEquals(4, proxy.commits.get())
            assertTrue(subject.status.value is BackupStatus.UpToDate)
            assertEquals(owner, local.value.metadata.ownerUid)
            assertEquals(1L, local.value.baseline!!.generation)
        }
    }

    @Test
    fun deletedMutationTargetFailsClosedWithoutRecreationOrRetry() = runBlocking {
        val owner = "owner-${UUID.randomUUID()}"
        seedMetadata(owner)
        val transport = client(owner)
        var callbacks = 0
        val failure =
            runCatching {
                    transport.transaction {
                        callbacks++
                        val original = requireNotNull(get("users/$owner"))
                        client(owner).transaction {
                            get("users/$owner")
                            delete("users/$owner")
                        }
                        put("users/$owner", original)
                    }
                }
                .exceptionOrNull() as CloudBackupFailure
        // These strict rules can reject the now-missing update target before its version check.
        // Preserve that permission result; never reinterpret arbitrary 403s as retryable conflicts.
        assertEquals(BackupFailureReason.PermissionDenied, failure.reason)
        assertEquals(1, callbacks)
        assertNull(transport.get("users/$owner"))
    }

    @Test
    fun explicitRefreshPreviewCancelAndConfirmWorkWithBeginDenied() = runBlocking {
        val owner = "owner-${UUID.randomUUID()}"
        seedMetadata(owner)
        DeniedBeginProxy().use { proxy ->
            val transport = client(owner, proxy.endpoint)
            assertEquals(
                0L,
                transport.get("users/$owner")!!.getValue("generation").jsonPrimitive.long
            )
            val local = LocalFixture(snapshot())
            val subject = coordinator(owner, local, transport)
            assertEquals(BackupLookupResult.Absent, subject.latestCompleteBackup())
            val before = local.value
            val preview = (subject.previewBackup() as BackupPreviewResult.Ready).preview
            assertEquals(1, preview.entityCounts.getValue("PersonalRecord"))
            subject.discardPreview(preview.id)
            assertEquals(before, local.value)
            assertEquals(0, proxy.commits.get())
            assertTrue(proxy.paths.none { "/chunks/" in it })

            val fresh = (subject.previewBackup() as BackupPreviewResult.Ready).preview
            assertEquals(BackupActionResult.Completed, subject.confirmBackup(fresh.id))
            assertTrue(subject.status.value is BackupStatus.UpToDate)
            assertEquals(owner, local.value.metadata.ownerUid)
            assertEquals(1L, local.value.baseline!!.generation)
            assertTrue(subject.latestCompleteBackup() is BackupLookupResult.Complete)
            val commits = proxy.commits.get()
            val unchanged = (subject.previewBackup() as BackupPreviewResult.Ready).preview
            assertEquals(BackupActionResult.Completed, subject.confirmBackup(unchanged.id))
            assertEquals(commits, proxy.commits.get())
            assertTrue(proxy.paths.none { ":beginTransaction" in it || ":rollback" in it })
        }
    }

    @Test
    fun successfulReadsDoNotConcealIndependentWriteDenial() = runBlocking {
        val owner = "owner-${UUID.randomUUID()}"
        seedMetadata(owner)
        DeniedBeginProxy().use { proxy ->
            proxy.denyCommits = true
            val transport = client(owner, proxy.endpoint)
            val local = LocalFixture(snapshot())
            val subject = coordinator(owner, local, transport)
            assertEquals(BackupLookupResult.Absent, subject.latestCompleteBackup())
            val preview = (subject.previewBackup() as BackupPreviewResult.Ready).preview
            assertEquals(
                BackupActionResult.Failed(BackupFailureReason.PermissionDenied),
                subject.confirmBackup(preview.id)
            )
            assertEquals(
                BackupStatus.NeedsAttention(BackupFailureReason.PermissionDenied),
                subject.status.value
            )
            assertEquals(owner, local.value.metadata.ownerUid)
            assertNull(local.value.baseline)
            assertEquals(1, proxy.commits.get())
            assertEquals(RemoteBackupInspection.Absent(), store(owner).inspect(AccountId(owner)))
        }
    }

    @Test
    fun lostHttpCompletionReceiptRecoversAfterCoordinatorRecreationWithoutReupload() = runBlocking {
        val owner = "owner-${UUID.randomUUID()}"
        seedMetadata(owner)
        DeniedBeginProxy().use { proxy ->
            val transport = client(owner, proxy.endpoint)
            val local = LocalFixture(snapshot())
            val subject = coordinator(owner, local, transport)
            val preview = (subject.previewBackup() as BackupPreviewResult.Ready).preview
            proxy.loseCompletionReceipt = true
            assertEquals(
                BackupActionResult.Failed(BackupFailureReason.Offline),
                subject.confirmBackup(preview.id)
            )
            assertEquals(BackupStatus.OfflinePending, subject.status.value)
            assertEquals(owner, local.value.metadata.ownerUid)
            assertNull(local.value.baseline)
            val commits = proxy.commits.get()
            val restarted = coordinator(owner, local, client(owner, proxy.endpoint))
            assertTrue(restarted.latestCompleteBackup() is BackupLookupResult.Complete)
            val retry = (restarted.previewBackup() as BackupPreviewResult.Ready).preview
            assertEquals(BackupActionResult.Completed, restarted.confirmBackup(retry.id))
            assertEquals(1L, local.value.baseline!!.generation)
            assertTrue(restarted.status.value is BackupStatus.UpToDate)
            assertEquals(commits, proxy.commits.get())
        }
    }

    @Test
    fun creationPreconditionRejectsSameGenerationCompetingCreation() = runBlocking {
        val owner = "owner-${UUID.randomUUID()}"
        val transport = client(owner)
        val failure =
            runCatching {
                    transport.transaction {
                        assertNull(get("users/$owner"))
                        seedMetadata(owner)
                        put("users/$owner", emptyMetadata())
                    }
                }
                .exceptionOrNull() as CloudBackupFailure
        assertEquals(BackupFailureReason.ConcurrentRemoteChange, failure.reason)
        assertEquals(emptyMetadata(), transport.get("users/$owner"))
    }

    @Test
    fun readSnapshotCannotMixAFirstReadWithAFutureManifest() = runBlocking {
        val owner = "owner-${UUID.randomUUID()}"
        val transport = client(owner)
        var future = ""
        transport.transaction {
            assertNull(get("users/$owner"))
            val published =
                store(owner).publish(AccountId(owner), 0, "installation-a", snapshot())
                    as RemoteBackupPublish.Completed
            future = "users/$owner/backups/${published.backup.summary.backupId}"
            assertNull(get(future))
        }
        assertNotNull(transport.get(future))
    }

    @Test
    fun unwrittenUserVersionGuardsChunkCleanupEvenWhenGenerationIsUnchanged() = runBlocking {
        val owner = "owner-${UUID.randomUUID()}"
        val account = AccountId(owner)
        val first =
            store(owner).publish(account, 0, "installation-a", snapshot())
                as RemoteBackupPublish.Completed
        assertTrue(
            store(owner).publish(account, 1, "installation-a", snapshot(60.0))
                is RemoteBackupPublish.Completed
        )
        val transport = client(owner)
        val path = "users/$owner/backups/${first.backup.summary.backupId}/chunks/000"
        val original = transport.get(path)
        val interloper =
            object : FirestoreBackupClient by transport {
                override suspend fun transaction(
                    block: suspend FirestoreBackupTransaction.() -> Unit
                ) {
                    var claimed = false
                    transport.transaction {
                        val delegate = this
                        val recording =
                            object : FirestoreBackupTransaction by delegate {
                                override fun put(
                                    path: String,
                                    fields: JsonObject,
                                    serverTimes: Set<String>
                                ) {
                                    claimed =
                                        claimed || fields["state"] == JsonPrimitive("UPLOADING")
                                    delegate.put(path, fields, serverTimes)
                                }
                            }
                        recording.block()
                    }
                    if (claimed) throw CloudBackupFailure(BackupFailureReason.Offline)
                }
            }
        val failure =
            runCatching {
                    transport.transaction {
                        assertEquals(
                            2L,
                            get("users/$owner")!!.getValue("generation").jsonPrimitive.long
                        )
                        assertEquals(original, get(path))
                        assertEquals(
                            RemoteBackupPublish.Failed(BackupFailureReason.Offline),
                            store(owner, interloper)
                                .publish(account, 2, "installation-b", snapshot(70.0))
                        )
                        delete(path)
                    }
                }
                .exceptionOrNull() as CloudBackupFailure
        assertEquals(BackupFailureReason.ConcurrentRemoteChange, failure.reason)
        assertEquals(original, transport.get(path))
        assertEquals(2L, transport.get("users/$owner")!!.getValue("generation").jsonPrimitive.long)
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

    private fun client(uid: String?, endpoint: String = "http://127.0.0.1:8080/v1") =
        FirestoreBackupRestClient(
            "demo-ironpath",
            { uid?.let(::emulatorToken).orEmpty() },
            { true },
            endpoint
        )

    private fun emptyMetadata() = buildJsonObject {
        put("generation", 0)
        put("backupIds", JsonArray(emptyList()))
        put("activeUploadBackupId", JsonNull)
        put("latestCompleteBackupId", JsonNull)
        put("latestCompletedAt", JsonNull)
        put("latestSourceInstallationId", JsonNull)
    }

    private suspend fun seedMetadata(owner: String) {
        client(owner).transaction {
            assertNull(get("users/$owner"))
            put("users/$owner", emptyMetadata())
        }
    }

    private fun coordinator(
        owner: String,
        local: LocalFixture,
        transport: FirestoreBackupClient
    ): CloudManualBackupCoordinator {
        val sessions = mockk<AccountSessionAdapter>()
        coEvery { sessions.readSession() } returns
            AccountProfile(AccountId(owner), "Synthetic owner", "")
        val installation = mockk<InstallationGuard>()
        coEvery { installation.validate() } returns InstallationValidationResult.Validated
        return CloudManualBackupCoordinator(
            local,
            store(owner, transport),
            sessions,
            installation,
            IdProvider { UUID.randomUUID().toString() },
            AccountSessionOperationGate(),
            Dispatchers.Unconfined
        )
    }

    private class LocalFixture(snapshot: EncodedBackupSnapshot) :
        ManualBackupLocalStore by mockk<ManualBackupLocalStore>() {
        var value =
            ManualBackupCapture(
                AccountBackupMetadata(
                    installationId = "installation-a",
                    localChangeRevision = snapshot.localChangeRevision
                ),
                BackupSnapshotCodec().decode(snapshot),
                null,
                null
            )

        override suspend fun capture() = value

        override suspend fun associateForBackup(
            captured: ManualBackupCapture,
            accountId: AccountId
        ): Boolean {
            if (captured != value) return false
            value = value.copy(metadata = value.metadata.copy(ownerUid = accountId.opaqueValue))
            return true
        }

        override suspend fun recordBackup(
            captured: ManualBackupCapture,
            accountId: AccountId,
            backup: RemoteBackupArtifact
        ): Boolean {
            if (captured != value) return false
            value =
                value.copy(
                    metadata =
                        value.metadata.copy(
                            lastCompleteLocalRevision = captured.metadata.localChangeRevision,
                            lastObservedRemoteGeneration = backup.generation
                        ),
                    baseline = backup
                )
            return true
        }

        override suspend fun applySync(
            captured: ManualBackupCapture,
            accountId: AccountId,
            backup: RemoteBackupArtifact
        ): Boolean {
            if (captured != value || value.activeSessionId != null) return false
            val revision = Math.addExact(value.metadata.localChangeRevision, 1)
            value =
                value.copy(
                    metadata =
                        value.metadata.copy(
                            ownerUid = accountId.opaqueValue,
                            localChangeRevision = revision,
                            lastCompleteLocalRevision = revision,
                            lastObservedRemoteGeneration = backup.generation
                        ),
                    bundle = backup.validatedBundle().copy(localChangeRevision = revision),
                    baseline = backup
                )
            return true
        }
    }

    /**
     * Only the observed endpoint denial is injected; normal reads/writes use real checked-in rules.
     */
    private class DeniedBeginProxy : AutoCloseable {
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val commits = AtomicInteger()
        @Volatile var denyCommits = false
        @Volatile var loseCompletionReceipt = false
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val path = exchange.requestURI.toString()
                    val payload = exchange.requestBody.readBytes()
                    paths += path + payload.toString(Charsets.UTF_8)
                    val commit = path.endsWith(":commit")
                    if (commit) commits.incrementAndGet()
                    if (path.endsWith(":beginTransaction") || (commit && denyCommits)) {
                        val denied = "{\"error\":{\"status\":\"PERMISSION_DENIED\"}}".toByteArray()
                        exchange.sendResponseHeaders(403, denied.size.toLong())
                        exchange.responseBody.use { it.write(denied) }
                    } else {
                        val remote =
                            URI("http://127.0.0.1:8080$path").toURL().openConnection()
                                as HttpURLConnection
                        try {
                            remote.requestMethod = exchange.requestMethod
                            remote.connectTimeout = 5_000
                            remote.readTimeout = 10_000
                            remote.setRequestProperty(
                                "Authorization",
                                exchange.requestHeaders.getFirst("Authorization")
                            )
                            remote.setRequestProperty("Content-Type", "application/json")
                            if (payload.isNotEmpty()) {
                                remote.doOutput = true
                                remote.setFixedLengthStreamingMode(payload.size)
                                remote.outputStream.use { it.write(payload) }
                            }
                            val code = remote.responseCode
                            val response =
                                (if (code < 400) remote.inputStream else remote.errorStream).use {
                                    it.readBytes()
                                }
                            if (
                                commit &&
                                    code == 200 &&
                                    loseCompletionReceipt &&
                                    "\"COMPLETE\"" in payload.toString(Charsets.UTF_8)
                            ) {
                                loseCompletionReceipt = false
                                exchange.close()
                            } else {
                                exchange.sendResponseHeaders(code, response.size.toLong())
                                exchange.responseBody.use { it.write(response) }
                            }
                        } finally {
                            remote.disconnect()
                        }
                    }
                }
                start()
            }
        val endpoint
            get() = "http://127.0.0.1:${server.address.port}/v1"

        override fun close() = server.stop(0)
    }

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
