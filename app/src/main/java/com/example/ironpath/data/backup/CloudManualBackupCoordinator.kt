package com.example.ironpath.data.backup

import com.example.ironpath.data.account.AccountSessionOperationGate
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.account.AccountSessionAdapter
import com.example.ironpath.domain.backup.*
import com.example.ironpath.domain.identity.IdProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

/** Explicit live backup and sync. Restore, undo and deletion remain unavailable. */
@Singleton
class CloudManualBackupCoordinator
internal constructor(
    private val local: ManualBackupLocalStore,
    private val remote: RemoteBackupStore,
    private val sessions: AccountSessionAdapter,
    private val installation: InstallationGuard,
    private val ids: IdProvider,
    private val gate: AccountSessionOperationGate,
    private val dispatcher: CoroutineDispatcher,
) : BackupCoordinator {
    @Inject
    constructor(
        local: ManualBackupLocalStore,
        remote: RemoteBackupStore,
        sessions: AccountSessionAdapter,
        installation: InstallationGuard,
        ids: IdProvider,
        gate: AccountSessionOperationGate,
    ) : this(local, remote, sessions, installation, ids, gate, Dispatchers.Default)

    override val status = MutableStateFlow<BackupStatus>(BackupStatus.LocalOnly)
    override val latestSummary = MutableStateFlow<RemoteBackupSummary?>(null)
    private val codec = BackupSnapshotCodec()
    private val mutex = Mutex()
    private var epoch: Long? = null
    private var observation: RemoteBackupInspection? = null
    private var pending: Pending? = null
    private var pendingSync: PendingSync? = null
    private var manualWriteNeedsInspection = false

    private data class Pending(
        val account: AccountId,
        val captured: ManualBackupCapture,
        val observed: RemoteBackupInspection,
        val preview: BackupPreview,
        val snapshot: EncodedBackupSnapshot
    )

    private data class PendingSync(
        val account: AccountId,
        val captured: ManualBackupCapture,
        val remote: RemoteBackupArtifact,
        val preview: SyncPreview,
        val merge: SyncMergeAnalysis,
    )

    override suspend fun refreshStatus() {
        locked(Unit, { Unit }, wait = true) {
            val account = sessions.readSession()?.id
            if (account == null) {
                clear()
            } else {
                val captured = capture(account)
                if (!manualWriteNeedsInspection)
                    update(captured, observation ?: persisted(captured))
            }
        }
    }

    override suspend fun latestCompleteBackup(): BackupLookupResult =
        locked(BackupLookupResult.Unavailable, { BackupLookupResult.Failed(it) }) {
            val account = account()
            val captured = capture(account)
            val inspected = inspect(account)
            update(captured, inspected)
            when (inspected) {
                is RemoteBackupInspection.Complete ->
                    BackupLookupResult.Complete(inspected.backup.summary)
                is RemoteBackupInspection.Absent -> BackupLookupResult.Absent
                is RemoteBackupInspection.Failed -> fail(inspected.reason)
            }
        }

    override suspend fun previewBackup(): BackupPreviewResult =
        locked(BackupPreviewResult.Unavailable, { BackupPreviewResult.Failed(it) }) {
            pending = null
            pendingSync = null
            status.value = BackupStatus.Preparing
            val account = account()
            val captured = capture(account)
            val observed = inspect(account)
            val snapshot = codec.encode(captured.bundle)
            val complete = (observed as? RemoteBackupInspection.Complete)?.backup
            // Durable ownership comes from explicit confirmation before upload. A fresh unclaimed
            // profile must never adopt existing cloud data merely because its digest matches.
            // An owned profile can recover an exact-content receipt. A newer same-installation
            // snapshot may be a merge that never reached Room, so source alone is not consent.
            if (
                complete != null &&
                    (captured.metadata.ownerUid == null ||
                        (complete.generation > captured.metadata.lastObservedRemoteGeneration &&
                            (complete.summary.sourceInstallationId !=
                                captured.metadata.installationId ||
                                complete.contentDigest != snapshot.contentDigest)))
            )
                fail(BackupFailureReason.ConcurrentRemoteChange)
            val total = snapshot.entityCounts.values.sumOf { it.toLong() }
            val previous = complete?.summary?.entityCounts?.values?.sumOf { it.toLong() } ?: 0
            if (total == 0L && complete == null && captured.activeSessionId != null)
                fail(BackupFailureReason.ActiveSessionPresent)
            val preview =
                BackupPreview(
                    ids.newId(),
                    captured.metadata.localChangeRevision,
                    generation(observed),
                    snapshot.entityCounts,
                    previous.toInt(),
                    previous > 0 && total * 2 < previous,
                    total == 0L && complete == null
                )
            pending = Pending(account, captured, observed, preview, snapshot)
            status.value = BackupStatus.ReviewRequired
            BackupPreviewResult.Ready(preview)
        }

    override suspend fun confirmBackup(
        previewId: String,
        destructiveConfirmed: Boolean
    ): BackupActionResult =
        locked(BackupActionResult.Unavailable, { BackupActionResult.Failed(it) }) {
            val request =
                pending?.takeIf { it.preview.id == previewId }
                    ?: fail(BackupFailureReason.StalePreview)
            if (request.preview.requiresDestructiveConfirmation && !destructiveConfirmed)
                fail(BackupFailureReason.DestructiveLocalChange)
            val current = capture(account())
            if (
                sessions.readSession()?.id != request.account ||
                    current.metadata != request.captured.metadata ||
                    current.activeSessionId != request.captured.activeSessionId ||
                    codec.encode(current.bundle) != request.snapshot
            )
                fail(BackupFailureReason.StalePreview)
            val observed = inspect(request.account)
            if (observed != request.observed) fail(BackupFailureReason.StalePreview)
            pending = null
            status.value = BackupStatus.BackingUp
            if (request.preview.associationOnly) {
                if (!local.associateEmpty(current, request.account))
                    fail(BackupFailureReason.StalePreview)
                update(local.capture(), observed)
            } else {
                if (!local.associateForBackup(current, request.account))
                    fail(BackupFailureReason.StalePreview)
                val associated =
                    current.copy(
                        metadata = current.metadata.copy(ownerUid = request.account.opaqueValue)
                    )
                if (sessions.readSession()?.id != request.account)
                    fail(BackupFailureReason.ReauthenticationRequired)
                val previous = (observed as? RemoteBackupInspection.Complete)?.backup
                val artifact =
                    if (previous?.contentDigest == request.snapshot.contentDigest) {
                        manualWriteNeedsInspection = true
                        when (
                            val retained =
                                remote.retryRetention(
                                    request.account,
                                    previous.generation,
                                    previous.summary.backupId
                                )
                        ) {
                            RemoteBackupRetention.Completed -> Unit
                            is RemoteBackupRetention.Failed -> fail(retained.reason)
                        }
                        RemoteBackupArtifact(
                            previous.summary,
                            previous.generation,
                            codec.encode(
                                current.bundle.copy(
                                    localChangeRevision = previous.capturedLocalRevision
                                )
                            )
                        )
                    } else {
                        manualWriteNeedsInspection = true
                        when (
                            val published =
                                remote.publish(
                                    request.account,
                                    generation(observed),
                                    request.captured.metadata.installationId,
                                    request.snapshot
                                )
                        ) {
                            is RemoteBackupPublish.Completed -> published.backup
                            is RemoteBackupPublish.Failed -> fail(published.reason)
                        }
                    }
                if (sessions.readSession()?.id != request.account)
                    fail(BackupFailureReason.ReauthenticationRequired)
                codec.decode(artifact.snapshot)
                if (
                    artifact.snapshot.contentDigest != request.snapshot.contentDigest ||
                        artifact.generation < generation(observed) ||
                        (previous?.contentDigest != request.snapshot.contentDigest &&
                            (artifact.generation != Math.addExact(generation(observed), 1) ||
                                artifact.summary.sourceInstallationId !=
                                    current.metadata.installationId))
                )
                    fail(BackupFailureReason.InvalidSnapshot)
                manualWriteNeedsInspection = false
                observation = RemoteBackupInspection.Complete(RemoteBackupMetadata.from(artifact))
                latestSummary.value = artifact.summary
                if (!local.recordBackup(associated, request.account, artifact))
                    fail(BackupFailureReason.StalePreview)
                update(local.capture(), observation!!)
            }
            BackupActionResult.Completed
        }

    override suspend fun previewSync(): SyncPreviewResult =
        locked(SyncPreviewResult.Unavailable, { SyncPreviewResult.Failed(it) }) {
            pending = null
            pendingSync = null
            status.value = BackupStatus.Preparing
            val account = account()
            val captured = capture(account)
            if (captured.activeSessionId != null) fail(BackupFailureReason.ActiveSessionPresent)
            val read = remote.latest(account)
            requireSession(account)
            val complete =
                when (read) {
                    is RemoteBackupRead.Complete -> read.backup
                    is RemoteBackupRead.Failed -> fail(read.reason)
                    is RemoteBackupRead.Absent -> {
                        val absent = RemoteBackupInspection.Absent(read.generation)
                        observation = absent
                        update(captured, absent)
                        return@locked SyncPreviewResult.Unavailable
                    }
                }
            val merge =
                ManualSyncMerger.analyze(
                    captured.baseline?.validatedBundle(),
                    BackupBundleValidator.validate(captured.bundle).bundle,
                    complete.validatedBundle(),
                )
            if (merge.localResult == null && merge.cloudResult == null)
                fail(BackupFailureReason.InvalidSnapshot)
            // Validate encoded size before offering either outcome as confirmable.
            fun fits(bundle: BackupBundle?) =
                bundle != null && runCatching { codec.encode(bundle) }.isSuccess
            val localFits = fits(merge.localResult)
            val cloudFits = fits(merge.cloudResult)
            if (!localFits && !cloudFits) fail(BackupFailureReason.InvalidSnapshot)
            val preview =
                SyncPreview(
                    ids.newId(),
                    captured.metadata.localChangeRevision,
                    complete.generation,
                    merge.localChanges,
                    merge.cloudChanges,
                    merge.conflicts,
                    localFits,
                    cloudFits,
                )
            pendingSync = PendingSync(account, captured, complete, preview, merge)
            observation = RemoteBackupInspection.Complete(RemoteBackupMetadata.from(complete))
            latestSummary.value = complete.summary
            status.value = BackupStatus.ReviewRequired
            SyncPreviewResult.Ready(preview)
        }

    override suspend fun confirmSync(
        previewId: String,
        resolution: SyncConflictResolution?,
    ): BackupActionResult =
        locked(BackupActionResult.Unavailable, { BackupActionResult.Failed(it) }) {
            val request =
                pendingSync?.takeIf { it.preview.id == previewId }
                    ?: fail(BackupFailureReason.StalePreview)
            if (request.preview.conflicts.values.any { it > 0 } && resolution == null)
                fail(BackupFailureReason.ConflictChoiceRequired)
            val keepCloud = resolution == SyncConflictResolution.KeepCloud
            val merged = if (keepCloud) request.merge.cloudResult else request.merge.localResult
            if (
                merged == null ||
                    !(if (keepCloud) request.preview.canKeepCloud else request.preview.canKeepLocal)
            )
                fail(BackupFailureReason.InvalidSnapshot)
            val snapshot = codec.encode(merged)
            pendingSync = null
            val current = capture(account())
            requireSession(request.account)
            if (current != request.captured) fail(BackupFailureReason.StalePreview)
            if (current.activeSessionId != null) fail(BackupFailureReason.ActiveSessionPresent)
            Math.addExact(current.metadata.localChangeRevision, 1)
            val observed = inspect(request.account)
            if (
                observed !=
                    RemoteBackupInspection.Complete(RemoteBackupMetadata.from(request.remote))
            )
                fail(BackupFailureReason.StalePreview)
            // Remote inspection can suspend while local records change; Room checks again after
            // publication too, but reject known changes before any cloud mutation.
            if (capture(request.account) != current) fail(BackupFailureReason.StalePreview)
            requireSession(request.account)
            status.value = BackupStatus.BackingUp
            manualWriteNeedsInspection = true
            val completed =
                if (snapshot.contentDigest == request.remote.snapshot.contentDigest) {
                    when (
                        val retention =
                            remote.retryRetention(
                                request.account,
                                request.remote.generation,
                                request.remote.summary.backupId
                            )
                    ) {
                        RemoteBackupRetention.Completed -> Unit
                        is RemoteBackupRetention.Failed -> fail(retention.reason)
                    }
                    request.remote
                } else {
                    val expected = Math.addExact(request.remote.generation, 1)
                    val published =
                        when (
                            val result =
                                remote.publish(
                                    request.account,
                                    request.remote.generation,
                                    current.metadata.installationId,
                                    snapshot
                                )
                        ) {
                            is RemoteBackupPublish.Completed -> result.backup
                            is RemoteBackupPublish.Failed -> fail(result.reason)
                        }
                    published.validatedBundle()
                    if (
                        published.generation != expected ||
                            published.snapshot != snapshot ||
                            published.summary.sourceInstallationId !=
                                current.metadata.installationId
                    )
                        fail(BackupFailureReason.InvalidSnapshot)
                    published
                }
            requireSession(request.account)
            // No distributed transaction is possible. The old baseline survives every failed
            // local apply; a fresh three-way review preserves intervening offline edits.
            observation = RemoteBackupInspection.Complete(RemoteBackupMetadata.from(completed))
            latestSummary.value = completed.summary
            if (!local.applySync(current, request.account, completed))
                fail(BackupFailureReason.StalePreview)
            manualWriteNeedsInspection = false
            update(local.capture(), observation!!)
            BackupActionResult.Completed
        }

    private suspend fun requireSession(expected: AccountId) {
        if (sessions.readSession()?.id != expected)
            fail(BackupFailureReason.ReauthenticationRequired)
    }

    override suspend fun discardPreview(previewId: String) {
        locked(Unit, { Unit }, wait = true) {
            if (pending?.preview?.id == previewId) pending = null
            if (pendingSync?.preview?.id == previewId) pendingSync = null
        }
    }

    override suspend fun backUpNow(): BackupActionResult = BackupActionResult.Unavailable

    override suspend fun deleteAllRemoteData(): BackupActionResult = BackupActionResult.Unavailable

    private suspend fun account() =
        sessions.readSession()?.id ?: fail(BackupFailureReason.ReauthenticationRequired)

    private suspend fun capture(account: AccountId): ManualBackupCapture {
        if (installation.validate() == InstallationValidationResult.Failed)
            fail(BackupFailureReason.ServiceUnavailable)
        val captured = local.capture()
        if (captured.metadata.pendingSignOutUid != null)
            fail(BackupFailureReason.ReauthenticationRequired)
        if (captured.metadata.ownerUid != null && captured.metadata.ownerUid != account.opaqueValue)
            fail(BackupFailureReason.OwnershipMismatch)
        return captured
    }

    private suspend fun inspect(account: AccountId): RemoteBackupInspection {
        val inspected = remote.inspect(account)
        if (sessions.readSession()?.id != account)
            fail(BackupFailureReason.ReauthenticationRequired)
        if (inspected is RemoteBackupInspection.Failed) fail(inspected.reason)
        manualWriteNeedsInspection = false
        observation = inspected
        latestSummary.value = (inspected as? RemoteBackupInspection.Complete)?.backup?.summary
        return inspected
    }

    private fun persisted(captured: ManualBackupCapture): RemoteBackupInspection =
        captured.baseline?.let { RemoteBackupInspection.Complete(RemoteBackupMetadata.from(it)) }
            ?: RemoteBackupInspection.Absent()

    private fun generation(read: RemoteBackupInspection): Long =
        when (read) {
            is RemoteBackupInspection.Absent -> read.generation
            is RemoteBackupInspection.Complete -> read.backup.generation
            is RemoteBackupInspection.Failed -> fail(read.reason)
        }

    private fun update(captured: ManualBackupCapture, read: RemoteBackupInspection) {
        val complete = (read as? RemoteBackupInspection.Complete)?.backup
        latestSummary.value = complete?.summary
        status.value =
            when {
                complete == null -> BackupStatus.SignedInNoBackup
                captured.metadata.ownerUid == null ||
                    complete.generation > captured.metadata.lastObservedRemoteGeneration ->
                    BackupStatus.ReviewRequired
                captured.metadata.localChangeRevision ==
                    captured.metadata.lastCompleteLocalRevision &&
                    !captured.metadata.requiresLineageReviewAfterUndo &&
                    codec.encode(captured.bundle).contentDigest == complete.contentDigest ->
                    BackupStatus.UpToDate(complete.summary.completedAtEpochMillis)
                else -> BackupStatus.LocalChanges
            }
    }

    private fun clear() {
        observation = null
        manualWriteNeedsInspection = false
        latestSummary.value = null
        pending = null
        pendingSync = null
        status.value = BackupStatus.LocalOnly
    }

    private fun fail(reason: BackupFailureReason): Nothing = throw CloudBackupFailure(reason)

    private suspend fun <T> locked(
        unavailable: T,
        failure: (BackupFailureReason) -> T,
        wait: Boolean = false,
        block: suspend () -> T
    ): T =
        gate.withManualOperation(wait, unavailable) { currentEpoch ->
            if (wait) mutex.lock() else if (!mutex.tryLock()) return@withManualOperation unavailable
            try {
                if (epoch != currentEpoch) {
                    clear()
                    epoch = currentEpoch
                }
                withContext(dispatcher) { block() }
            } catch (cancelled: CancellationException) {
                pending = null
                pendingSync = null
                status.value = BackupStatus.NeedsAttention(BackupFailureReason.ServiceUnavailable)
                throw cancelled
            } catch (error: Exception) {
                val reason =
                    (error as? CloudBackupFailure)?.reason
                        ?: if (error is IllegalArgumentException)
                            BackupFailureReason.InvalidSnapshot
                        else BackupFailureReason.ServiceUnavailable
                if (
                    reason == BackupFailureReason.OwnershipMismatch ||
                        reason == BackupFailureReason.ReauthenticationRequired
                )
                    clear()
                status.value =
                    when (reason) {
                        BackupFailureReason.StalePreview,
                        BackupFailureReason.ConcurrentRemoteChange,
                        BackupFailureReason.DestructiveLocalChange -> BackupStatus.ReviewRequired
                        BackupFailureReason.Offline -> BackupStatus.OfflinePending
                        BackupFailureReason.QuotaOrRateLimited -> BackupStatus.QuotaPaused
                        BackupFailureReason.ReauthenticationRequired -> BackupStatus.NeedsSignIn
                        else -> BackupStatus.NeedsAttention(reason)
                    }
                failure(reason)
            } finally {
                mutex.unlock()
            }
        }
}
