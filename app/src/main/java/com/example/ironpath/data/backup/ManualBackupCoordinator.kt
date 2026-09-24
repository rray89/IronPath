package com.example.ironpath.data.backup

import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.account.AccountSessionAdapter
import com.example.ironpath.domain.backup.*
import com.example.ironpath.domain.identity.IdProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

@Singleton
class ManualBackupCoordinator
internal constructor(
    private val localStore: ManualBackupLocalStore,
    private val remote: RemoteBackupStore,
    private val sessions: AccountSessionAdapter,
    private val installationGuard: InstallationGuard,
    private val idProvider: IdProvider,
    private val dispatcher: CoroutineDispatcher,
) : BackupCoordinator {
    @Inject
    constructor(
        localStore: ManualBackupLocalStore,
        remote: RemoteBackupStore,
        sessions: AccountSessionAdapter,
        installationGuard: InstallationGuard,
        idProvider: IdProvider,
    ) : this(localStore, remote, sessions, installationGuard, idProvider, Dispatchers.Default)

    override val status = MutableStateFlow<BackupStatus>(BackupStatus.LocalOnly)
    override val latestSummary = MutableStateFlow<RemoteBackupSummary?>(null)
    override val undoAvailable = MutableStateFlow(false)
    private val mutex = Mutex()
    private val codec = BackupSnapshotCodec()
    private val localCodec = BackupSnapshotCodec(preserveDanglingProvenance = true)
    private var pending: Pending? = null
    private var lastRemoteObservation: Pair<AccountId, RemoteBackupRead>? = null

    private data class Pending(
        val id: String,
        val accountId: AccountId,
        val captured: ManualBackupCapture,
        val remote: RemoteBackupRead,
        val backup: BackupPreview? = null,
        val sync: SyncPreview? = null,
        val merge: SyncMergeAnalysis? = null,
        val restore: RestorePreview? = null,
        val restoreArtifact: ValidatedRestoreArtifact? = null,
        val undo: UndoPreview? = null,
        val undoCapture: ManualBackupUndoCapture? = null,
    )

    override suspend fun refreshStatus() =
        locked(Unit, { Unit }, waitForTurn = true) {
            validateInstallation()
            val profile = sessions.readSession()
            if (profile == null) {
                latestSummary.value = null
                lastRemoteObservation = null
                pending = null
                undoAvailable.value = false
                status.value = BackupStatus.LocalOnly
            } else {
                val captured = localStore.capture()
                requireOwner(captured, profile.id)
                undoAvailable.value =
                    localStore.captureUndo(profile.id, captured.metadata.installationId) != null
                // Product mutations only refresh persisted local state. Reading the remote pointer
                // belongs to an explicit account-screen lookup or a manual operation preview.
                val persisted =
                    captured.baseline?.let { RemoteBackupRead.Complete(it) }
                        ?: RemoteBackupRead.Absent()
                val observed =
                    lastRemoteObservation
                        ?.takeIf {
                            it.first == profile.id && generation(it.second) >= generation(persisted)
                        }
                        ?.second ?: persisted
                latestSummary.value = (observed as? RemoteBackupRead.Complete)?.backup?.summary
                updateStatus(captured, observed)
            }
        }

    override suspend fun discardPreview(previewId: String) {
        locked(Unit, { Unit }, waitForTurn = true) { if (pending?.id == previewId) pending = null }
    }

    override suspend fun previewBackup(): BackupPreviewResult =
        locked(BackupPreviewResult.Unavailable, { BackupPreviewResult.Failed(it) }) {
            pending = null
            status.value = BackupStatus.Preparing
            val (account, captured) = authorizedCapture()
            val observed = readRemote(account)
            val complete = (observed as? RemoteBackupRead.Complete)?.backup
            if (
                complete != null &&
                    (captured.metadata.ownerUid == null || unobservedRemote(captured, complete))
            )
                fail(BackupFailureReason.ConcurrentRemoteChange)
            val snapshot = codec.encode(captured.bundle)
            val total = snapshot.entityCounts.values.sum()
            val previous = complete?.snapshot?.entityCounts?.values?.sum() ?: 0
            val preview =
                BackupPreview(
                    idProvider.newId(),
                    captured.metadata.localChangeRevision,
                    generation(observed),
                    snapshot.entityCounts.toMap(),
                    previous,
                    previous > 0 && total.toLong() * 2 < previous.toLong(),
                    total == 0 && complete == null
                )
            pending = Pending(preview.id, account, captured, observed, backup = preview)
            status.value = BackupStatus.ReviewRequired
            BackupPreviewResult.Ready(preview)
        }

    override suspend fun confirmBackup(
        previewId: String,
        destructiveConfirmed: Boolean
    ): BackupActionResult =
        locked(BackupActionResult.Unavailable, { BackupActionResult.Failed(it) }) {
            val request =
                pending?.takeIf { it.id == previewId && it.backup != null }
                    ?: fail(BackupFailureReason.StalePreview)
            if (request.backup!!.requiresDestructiveConfirmation && !destructiveConfirmed)
                fail(BackupFailureReason.DestructiveLocalChange)
            pending = null
            val (current, observed) = revalidate(request)
            status.value = BackupStatus.BackingUp
            if (request.backup.associationOnly) {
                requireSession(request.accountId)
                if (!localStore.associateEmpty(current, request.accountId))
                    fail(BackupFailureReason.StalePreview)
                status.value = BackupStatus.SignedInNoBackup
            } else {
                val snapshot = codec.encode(request.captured.bundle)
                val completed = publishOrReuse(request, observed, snapshot)
                requireSession(request.accountId)
                if (!localStore.recordBackup(request.captured, request.accountId, completed))
                    fail(BackupFailureReason.StalePreview)
                updateStatus(localStore.capture(), RemoteBackupRead.Complete(completed))
            }
            BackupActionResult.Completed
        }

    override suspend fun previewSync(): SyncPreviewResult =
        locked(SyncPreviewResult.Unavailable, { SyncPreviewResult.Failed(it) }) {
            pending = null
            status.value = BackupStatus.Preparing
            val (account, captured) = authorizedCapture()
            if (captured.activeSessionId != null) fail(BackupFailureReason.ActiveSessionPresent)
            val observed = readRemote(account)
            val complete =
                (observed as? RemoteBackupRead.Complete)?.backup
                    ?: run {
                        updateStatus(captured, observed)
                        return@locked SyncPreviewResult.Unavailable
                    }
            val base = captured.baseline?.validatedBundle()
            val local = BackupBundleValidator.validate(captured.bundle).bundle
            val merge = ManualSyncMerger.analyze(base, local, complete.validatedBundle())
            if (merge.localResult == null && merge.cloudResult == null)
                fail(BackupFailureReason.InvalidSnapshot)
            val preview =
                SyncPreview(
                    idProvider.newId(),
                    captured.metadata.localChangeRevision,
                    complete.generation,
                    merge.localChanges.toMap(),
                    merge.cloudChanges.toMap(),
                    merge.conflicts.toMap(),
                    merge.localResult != null,
                    merge.cloudResult != null
                )
            pending =
                Pending(preview.id, account, captured, observed, sync = preview, merge = merge)
            status.value = BackupStatus.ReviewRequired
            SyncPreviewResult.Ready(preview)
        }

    override suspend fun confirmSync(
        previewId: String,
        resolution: SyncConflictResolution?
    ): BackupActionResult =
        locked(BackupActionResult.Unavailable, { BackupActionResult.Failed(it) }) {
            val request =
                pending?.takeIf { it.id == previewId && it.sync != null }
                    ?: fail(BackupFailureReason.StalePreview)
            if (request.merge!!.conflicts.values.any { it > 0 } && resolution == null)
                fail(BackupFailureReason.ConflictChoiceRequired)
            val merged =
                if (resolution == SyncConflictResolution.KeepCloud) request.merge!!.cloudResult
                else request.merge!!.localResult
            if (merged == null) fail(BackupFailureReason.InvalidSnapshot)
            pending = null
            val (current, observed) = revalidate(request)
            if (current.activeSessionId != null) fail(BackupFailureReason.ActiveSessionPresent)
            // Detect revision overflow before a remote snapshot can become complete.
            Math.addExact(current.metadata.localChangeRevision, 1)
            status.value = BackupStatus.BackingUp
            val completed = publishOrReuse(request, observed, codec.encode(merged))
            requireSession(request.accountId)
            // Remote publication may succeed while a local product transaction wins. Retain the
            // old shared base in that case; a fresh three-way preview recovers without losing work.
            if (!localStore.applySync(request.captured, request.accountId, completed))
                fail(BackupFailureReason.StalePreview)
            updateStatus(localStore.capture(), RemoteBackupRead.Complete(completed))
            BackupActionResult.Completed
        }

    override suspend fun previewRestore(): RestorePreviewResult =
        locked(RestorePreviewResult.Unavailable, { RestorePreviewResult.Failed(it) }) {
            pending = null
            status.value = BackupStatus.Preparing
            val (account, captured) = authorizedCapture()
            val observed = readRemote(account)
            val remoteArtifact =
                (observed as? RemoteBackupRead.Complete)?.backup
                    ?: run {
                        updateStatus(captured, observed)
                        return@locked RestorePreviewResult.Unavailable
                    }
            val validated = remoteArtifact.toValidatedRestore(account)
            val preview =
                RestorePreview(
                    id = idProvider.newId(),
                    latest = remoteArtifact.summary,
                    sourceDescription =
                        if (
                            remoteArtifact.summary.sourceInstallationId ==
                                captured.metadata.installationId
                        )
                            "This device"
                        else "Another device",
                    impact = RestoreImpactAnalyzer.analyze(captured.bundle, validated.bundle),
                    activeWorkoutDiscardRequired = captured.activeSessionId != null,
                    activeWorkoutTitle = captured.activeSessionTitle,
                    nulledProvenanceFields = validated.nulledProvenanceFields,
                )
            pending =
                Pending(
                    preview.id,
                    account,
                    captured,
                    observed,
                    restore = preview,
                    restoreArtifact = validated,
                )
            status.value = BackupStatus.ReviewRequired
            RestorePreviewResult.Ready(preview)
        }

    override suspend fun confirmRestore(
        previewId: String,
        activeWorkoutDiscardConfirmed: Boolean,
    ): BackupActionResult =
        locked(BackupActionResult.Unavailable, { BackupActionResult.Failed(it) }) {
            val request =
                pending?.takeIf { it.id == previewId && it.restore != null }
                    ?: fail(BackupFailureReason.StalePreview)
            val preview = checkNotNull(request.restore)
            if (preview.activeWorkoutDiscardRequired && !activeWorkoutDiscardConfirmed)
                return@locked BackupActionResult.ActiveSessionRequiresConfirmation(
                    checkNotNull(request.captured.activeSessionId)
                )
            pending = null
            val (current, observed) = revalidate(request)
            val artifact = checkNotNull(request.restoreArtifact)
            val discardId =
                if (activeWorkoutDiscardConfirmed) request.captured.activeSessionId else null
            status.value = BackupStatus.BackingUp
            requireSession(request.accountId)
            if (!localStore.restore(current, request.accountId, artifact, discardId))
                fail(BackupFailureReason.StalePreview)
            val restoredCapture = localStore.capture()
            val complete =
                (observed as? RemoteBackupRead.Complete)?.backup
                    ?: fail(BackupFailureReason.InvalidSnapshot)
            latestSummary.value = complete.summary
            lastRemoteObservation = request.accountId to RemoteBackupRead.Complete(complete)
            undoAvailable.value =
                localStore.captureUndo(
                    request.accountId,
                    restoredCapture.metadata.installationId
                ) != null
            updateStatus(restoredCapture, RemoteBackupRead.Complete(complete))
            BackupActionResult.Completed
        }

    override suspend fun previewUndo(): UndoPreviewResult =
        locked(UndoPreviewResult.Unavailable, { UndoPreviewResult.Failed(it) }) {
            pending = null
            status.value = BackupStatus.Preparing
            val (account, captured) = authorizedCapture()
            val undo =
                localStore.captureUndo(account, captured.metadata.installationId)
                    ?: run {
                        undoAvailable.value = false
                        updateStatus(captured, lastObservationFor(account, captured))
                        return@locked UndoPreviewResult.Unavailable
                    }
            val preview =
                UndoPreview(
                    id = idProvider.newId(),
                    impact = RestoreImpactAnalyzer.analyze(captured.bundle, undo.bundle),
                    activeWorkoutPresent = captured.activeSessionId != null,
                )
            pending =
                Pending(
                    preview.id,
                    account,
                    captured,
                    lastObservationFor(account, captured),
                    undo = preview,
                    undoCapture = undo,
                )
            undoAvailable.value = true
            status.value = BackupStatus.ReviewRequired
            UndoPreviewResult.Ready(preview)
        }

    override suspend fun confirmUndo(previewId: String): BackupActionResult =
        locked(BackupActionResult.Unavailable, { BackupActionResult.Failed(it) }) {
            val request =
                pending?.takeIf { it.id == previewId && it.undo != null }
                    ?: fail(BackupFailureReason.StalePreview)
            val preview = checkNotNull(request.undo)
            if (preview.activeWorkoutPresent)
                return@locked BackupActionResult.ActiveSessionRequiresConfirmation(
                    checkNotNull(request.captured.activeSessionId)
                )
            pending = null
            val current = revalidateUndoCapture(request)
            val undo = checkNotNull(request.undoCapture)
            val previousOwnerMatches =
                undo.previousMetadata.ownerUid == request.accountId.opaqueValue
            status.value = BackupStatus.BackingUp
            requireSession(request.accountId)
            if (!localStore.undo(current, request.accountId, undo))
                fail(BackupFailureReason.StalePreview)
            val restored = localStore.capture()
            val observation =
                if (previousOwnerMatches) {
                    lastObservationFor(request.accountId, restored)
                } else {
                    null
                }
            if (observation == null) {
                lastRemoteObservation = null
                latestSummary.value = null
            } else {
                lastRemoteObservation = request.accountId to observation
                latestSummary.value = (observation as? RemoteBackupRead.Complete)?.backup?.summary
            }
            undoAvailable.value = false
            updateStatus(restored, observation ?: RemoteBackupRead.Absent())
            BackupActionResult.Completed
        }

    private suspend fun publishOrReuse(
        request: Pending,
        observed: RemoteBackupRead,
        snapshot: EncodedBackupSnapshot
    ): RemoteBackupArtifact {
        val previous = (observed as? RemoteBackupRead.Complete)?.backup
        if (previous?.snapshot?.contentDigest == snapshot.contentDigest) return previous
        requireSession(request.accountId)
        val completedGeneration = Math.addExact(generation(observed), 1)
        val result =
            remote.publish(
                request.accountId,
                generation(observed),
                request.captured.metadata.installationId,
                snapshot
            )
        val completed =
            when (result) {
                is RemoteBackupPublish.Completed -> result.backup
                is RemoteBackupPublish.Failed -> fail(result.reason)
            }
        completed.validatedBundle()
        if (
            completed.generation != completedGeneration ||
                completed.snapshot.contentDigest != snapshot.contentDigest ||
                completed.summary.sourceInstallationId != request.captured.metadata.installationId
        )
            fail(BackupFailureReason.InvalidSnapshot)
        latestSummary.value = completed.summary
        lastRemoteObservation = request.accountId to RemoteBackupRead.Complete(completed)
        return completed
    }

    private suspend fun revalidate(request: Pending): Pair<ManualBackupCapture, RemoteBackupRead> {
        val (account, current) = authorizedCapture()
        if (
            account != request.accountId ||
                current.metadata.ownerUid != request.captured.metadata.ownerUid ||
                current.metadata.installationId != request.captured.metadata.installationId ||
                current.metadata.localChangeRevision !=
                    request.captured.metadata.localChangeRevision ||
                current.metadata.lastObservedRemoteGeneration !=
                    request.captured.metadata.lastObservedRemoteGeneration ||
                current.activeSessionId != request.captured.activeSessionId ||
                localCodec.encode(current.bundle).contentDigest !=
                    localCodec.encode(request.captured.bundle).contentDigest
        )
            fail(BackupFailureReason.StalePreview)
        val observed = readRemote(account)
        if (generation(observed) != generation(request.remote))
            fail(BackupFailureReason.StalePreview)
        if (
            (observed as? RemoteBackupRead.Complete)?.backup?.snapshot?.contentDigest !=
                (request.remote as? RemoteBackupRead.Complete)?.backup?.snapshot?.contentDigest
        )
            fail(BackupFailureReason.StalePreview)
        val observedArtifact = (observed as? RemoteBackupRead.Complete)?.backup
        val requestedArtifact = (request.remote as? RemoteBackupRead.Complete)?.backup
        if (
            observedArtifact?.summary?.backupId != requestedArtifact?.summary?.backupId ||
                observedArtifact?.summary?.sourceInstallationId !=
                    requestedArtifact?.summary?.sourceInstallationId ||
                observedArtifact?.summary?.completedAtEpochMillis !=
                    requestedArtifact?.summary?.completedAtEpochMillis
        )
            fail(BackupFailureReason.StalePreview)
        return current to observed
    }

    private suspend fun revalidateUndoCapture(request: Pending): ManualBackupCapture {
        val (account, current) = authorizedCapture()
        if (
            account != request.accountId ||
                current.metadata != request.captured.metadata ||
                current.activeSessionId != request.captured.activeSessionId ||
                localCodec.encode(current.bundle).contentDigest !=
                    localCodec.encode(request.captured.bundle).contentDigest ||
                current.baseline != request.captured.baseline
        )
            fail(BackupFailureReason.StalePreview)
        return current
    }

    private fun RemoteBackupArtifact.toValidatedRestore(
        account: AccountId
    ): ValidatedRestoreArtifact {
        val snapshot = snapshot
        return codec.decodeForRestore(
            snapshot,
            RestoreLineage(
                ownerUid = account.opaqueValue,
                remoteBackupId = summary.backupId,
                remoteGeneration = generation,
                remoteDigest = snapshot.contentDigest,
                sourceInstallationId = summary.sourceInstallationId,
                completedAt = summary.completedAtEpochMillis,
            ),
        )
    }

    private fun lastObservationFor(
        account: AccountId,
        captured: ManualBackupCapture,
    ): RemoteBackupRead =
        lastRemoteObservation
            ?.takeIf {
                it.first == account && generation(it.second) >= generationOf(captured.baseline)
            }
            ?.second
            ?: captured.baseline?.let { RemoteBackupRead.Complete(it) }
            ?: RemoteBackupRead.Absent()

    private fun generationOf(baseline: RemoteBackupArtifact?): Long = baseline?.generation ?: 0

    private suspend fun authorizedCapture(): Pair<AccountId, ManualBackupCapture> {
        validateInstallation()
        val account =
            sessions.readSession()?.id ?: fail(BackupFailureReason.ReauthenticationRequired)
        val captured = localStore.capture()
        requireOwner(captured, account)
        return account to captured
    }

    private suspend fun validateInstallation() {
        if (installationGuard.validate() == InstallationValidationResult.Failed)
            fail(BackupFailureReason.ServiceUnavailable)
    }

    private suspend fun requireSession(accountId: AccountId) {
        if (sessions.readSession()?.id != accountId)
            fail(BackupFailureReason.ReauthenticationRequired)
    }

    private fun requireOwner(captured: ManualBackupCapture, accountId: AccountId) {
        if (
            captured.metadata.ownerUid != null &&
                captured.metadata.ownerUid != accountId.opaqueValue
        )
            fail(BackupFailureReason.OwnershipMismatch)
    }

    private suspend fun readRemote(account: AccountId): RemoteBackupRead {
        val observed = remote.latest(account)
        // The account may change while the remote read is suspended. Do not publish an
        // observation into another account's screen or reuse it as the active lineage.
        requireSession(account)
        when (observed) {
            is RemoteBackupRead.Failed -> fail(observed.reason)
            is RemoteBackupRead.Complete -> {
                observed.backup.validatedBundle()
                latestSummary.value = observed.backup.summary
            }
            is RemoteBackupRead.Absent -> {
                require(observed.generation >= 0)
                latestSummary.value = null
            }
        }
        lastRemoteObservation = account to observed
        return observed
    }

    private fun generation(observed: RemoteBackupRead): Long =
        when (observed) {
            is RemoteBackupRead.Absent -> observed.generation
            is RemoteBackupRead.Complete -> observed.backup.generation
            is RemoteBackupRead.Failed -> fail(observed.reason)
        }

    private fun unobservedRemote(
        captured: ManualBackupCapture,
        complete: RemoteBackupArtifact
    ): Boolean =
        // A publication from this installation may have completed before its local sync CAS
        // failed. Only persisted acknowledgment proves that Room contains the cloud changes.
        complete.generation > captured.metadata.lastObservedRemoteGeneration

    private fun updateStatus(captured: ManualBackupCapture, observed: RemoteBackupRead) {
        val complete = (observed as? RemoteBackupRead.Complete)?.backup
        status.value =
            when {
                complete == null && captured.metadata.requiresLineageReviewAfterUndo ->
                    BackupStatus.LocalChanges
                complete == null -> BackupStatus.SignedInNoBackup
                captured.metadata.ownerUid == null || unobservedRemote(captured, complete) ->
                    BackupStatus.ReviewRequired
                captured.metadata.localChangeRevision ==
                    captured.metadata.lastCompleteLocalRevision &&
                    !captured.metadata.requiresLineageReviewAfterUndo &&
                    codec.encode(captured.bundle).contentDigest ==
                        complete.snapshot.contentDigest ->
                    BackupStatus.UpToDate(complete.summary.completedAtEpochMillis)
                captured.metadata.requiresLineageReviewAfterUndo -> BackupStatus.LocalChanges
                else -> BackupStatus.LocalChanges
            }
    }

    private class Failure(val reason: BackupFailureReason) : Exception()

    private fun fail(reason: BackupFailureReason): Nothing = throw Failure(reason)

    private suspend fun <T> locked(
        unavailable: T,
        failed: (BackupFailureReason) -> T,
        waitForTurn: Boolean = false,
        block: suspend () -> T
    ): T {
        // Preserve observed-state refreshes and preview revocation while another operation owns
        // the lock. Duplicate preview/confirmation commands still return without queuing.
        if (waitForTurn) mutex.lock() else if (!mutex.tryLock()) return unavailable
        return try {
            withContext(dispatcher) { block() }
        } catch (cancelled: CancellationException) {
            pending = null
            status.value = BackupStatus.NeedsAttention(BackupFailureReason.ServiceUnavailable)
            throw cancelled
        } catch (failure: Failure) {
            failureStatus(failure.reason)
            failed(failure.reason)
        } catch (_: IllegalArgumentException) {
            failureStatus(BackupFailureReason.InvalidSnapshot)
            failed(BackupFailureReason.InvalidSnapshot)
        } catch (_: Exception) {
            failureStatus(BackupFailureReason.ServiceUnavailable)
            failed(BackupFailureReason.ServiceUnavailable)
        } finally {
            mutex.unlock()
        }
    }

    private fun failureStatus(reason: BackupFailureReason) {
        if (
            reason == BackupFailureReason.OwnershipMismatch ||
                reason == BackupFailureReason.ReauthenticationRequired
        ) {
            latestSummary.value = null
            lastRemoteObservation = null
            pending = null
        }
        status.value =
            when (reason) {
                BackupFailureReason.StalePreview,
                BackupFailureReason.ConcurrentRemoteChange,
                BackupFailureReason.ConflictChoiceRequired,
                BackupFailureReason.DestructiveLocalChange -> BackupStatus.ReviewRequired
                BackupFailureReason.Offline -> BackupStatus.OfflinePending
                BackupFailureReason.QuotaOrRateLimited -> BackupStatus.QuotaPaused
                BackupFailureReason.ReauthenticationRequired -> BackupStatus.NeedsSignIn
                else -> BackupStatus.NeedsAttention(reason)
            }
    }

    override suspend fun backUpNow(): BackupActionResult = BackupActionResult.Unavailable

    override suspend fun latestCompleteBackup(): BackupLookupResult =
        locked(BackupLookupResult.Unavailable, { BackupLookupResult.Failed(it) }) {
            val (account, captured) = authorizedCapture()
            val observed = readRemote(account)
            updateStatus(captured, observed)
            when (observed) {
                is RemoteBackupRead.Complete -> BackupLookupResult.Complete(observed.backup.summary)
                is RemoteBackupRead.Absent -> BackupLookupResult.Absent
                is RemoteBackupRead.Failed -> BackupLookupResult.Failed(observed.reason)
            }
        }

    override suspend fun deleteAllRemoteData(): BackupActionResult = BackupActionResult.Unavailable
}
