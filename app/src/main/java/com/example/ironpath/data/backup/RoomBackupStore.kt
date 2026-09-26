package com.example.ironpath.data.backup

import androidx.room.withTransaction
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.entity.AccountBackupMetadata
import com.example.ironpath.data.local.entity.RestoreUndoChunk
import com.example.ironpath.data.local.entity.RestoreUndoMetadata
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.identity.IdProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class RoomBackupStore
@Inject
constructor(
    private val database: IronPathDatabase,
    private val idProvider: IdProvider,
    private val sentinel: InstallationSentinel = NonPersistentInstallationSentinel,
) : BackupChangeTracker, ManualBackupLocalStore, LocalProfileResetter {
    private val installationValidationMutex = Mutex()
    private val codec = BackupSnapshotCodec()
    private val undoCodec = BackupSnapshotCodec(preserveDanglingProvenance = true)

    override suspend fun capture(): ManualBackupCapture =
        database.withTransaction {
            val bundle = export()
            val metadata = checkNotNull(database.backupDao().getMetadata())
            val activeSession = database.sessionDao().getActiveSession()
            ManualBackupCapture(
                metadata,
                bundle,
                BackupBaselineCodec.decode(database.backupDao().getBaselineChunks(), metadata),
                activeSession?.id,
                activeSession?.workoutTitle,
            )
        }

    override suspend fun associateEmpty(
        captured: ManualBackupCapture,
        accountId: AccountId
    ): Boolean =
        database.withTransaction {
            val current = checkNotNull(database.backupDao().getMetadata())
            if (
                captured.activeSessionId != null ||
                    current.pendingSignOutUid != null ||
                    !sameAuthority(current, captured, accountId) ||
                    !sameContent(current, captured) ||
                    database.backupDao().hasIncludedData() ||
                    database.sessionDao().getActiveSession() != null
            )
                return@withTransaction false
            database.backupDao().updateMetadata(current.copy(ownerUid = accountId.opaqueValue))
            true
        }

    override suspend fun recordBackup(
        captured: ManualBackupCapture,
        accountId: AccountId,
        backup: RemoteBackupArtifact
    ): Boolean =
        database.withTransaction {
            val current = checkNotNull(database.backupDao().getMetadata())
            if (
                !sameAuthority(current, captured, accountId) ||
                    current.localChangeRevision < captured.metadata.localChangeRevision ||
                    current.lastObservedRemoteGeneration > backup.generation
            )
                return@withTransaction false
            require(
                backup.snapshot.contentDigest ==
                    BackupSnapshotCodec().encode(captured.bundle).contentDigest
            )
            persistCompleted(current, accountId, backup, captured.metadata.localChangeRevision)
            true
        }

    override suspend fun applySync(
        captured: ManualBackupCapture,
        accountId: AccountId,
        backup: RemoteBackupArtifact
    ): Boolean =
        database.withTransaction {
            val current = checkNotNull(database.backupDao().getMetadata())
            if (
                !sameAuthority(current, captured, accountId) ||
                    !sameContent(current, captured) ||
                    database.sessionDao().getActiveSession() != null ||
                    current.lastObservedRemoteGeneration > backup.generation
            )
                return@withTransaction false
            val merged = backup.validatedBundle()
            val revision = Math.addExact(current.localChangeRevision, 1)
            val dao = database.backupDao()
            dao.deletePersonalRecords()
            dao.deleteWorkoutLogs()
            dao.deleteWeeklyPlans()
            dao.insertWeeklyPlans(merged.weeklyPlans)
            dao.insertPlannedWorkouts(merged.plannedWorkouts)
            dao.insertPlannedExercises(merged.plannedExercises)
            dao.insertWorkoutLogs(merged.workoutLogs)
            dao.insertLoggedExercises(merged.loggedExercises)
            dao.insertLoggedSets(merged.loggedSets)
            dao.insertPersonalRecords(merged.personalRecords)
            persistCompleted(
                current.copy(localChangeRevision = revision),
                accountId,
                backup,
                revision
            )
            true
        }

    private fun sameAuthority(
        current: AccountBackupMetadata,
        captured: ManualBackupCapture,
        accountId: AccountId
    ): Boolean =
        current.installationId == captured.metadata.installationId &&
            current.ownerUid == captured.metadata.ownerUid &&
            (current.ownerUid == null || current.ownerUid == accountId.opaqueValue)

    private suspend fun sameContent(
        current: AccountBackupMetadata,
        captured: ManualBackupCapture
    ): Boolean =
        current.localChangeRevision == captured.metadata.localChangeRevision &&
            undoCodec.encode(export()).contentDigest ==
                undoCodec.encode(captured.bundle).contentDigest

    private suspend fun persistCompleted(
        current: AccountBackupMetadata,
        accountId: AccountId,
        backup: RemoteBackupArtifact,
        completedRevision: Long
    ) {
        val dao = database.backupDao()
        val rows = BackupBaselineCodec.encode(backup, accountId.opaqueValue, current.installationId)
        dao.deleteBaselineChunks()
        dao.insertBaselineChunks(rows)
        dao.updateMetadata(
            current.copy(
                ownerUid = accountId.opaqueValue,
                lastCompleteLocalRevision = completedRevision,
                lastObservedRemoteBackupId = backup.summary.backupId,
                lastObservedRemoteGeneration = backup.generation,
                lastObservedRemoteDigest = backup.snapshot.contentDigest,
                lastObservedSourceInstallationId = backup.summary.sourceInstallationId,
                lastObservedRemoteCompletedAt = backup.summary.completedAtEpochMillis,
                requiresLineageReviewAfterUndo = false,
            )
        )
    }

    override suspend fun markIncludedDataChanged() {
        database.withTransaction {
            ensureMetadata()
            val dao = database.backupDao()
            val metadata = checkNotNull(dao.getMetadata())
            dao.updateMetadata(
                metadata.copy(localChangeRevision = Math.addExact(metadata.localChangeRevision, 1))
            )
        }
    }

    suspend fun export(): BackupBundle =
        database.withTransaction {
            val backupDao = database.backupDao()
            ensureMetadata()
            val metadata = checkNotNull(backupDao.getMetadata())
            BackupBundle(
                localChangeRevision = metadata.localChangeRevision,
                weeklyPlans = backupDao.getWeeklyPlans(),
                plannedWorkouts = backupDao.getPlannedWorkouts(),
                plannedExercises = backupDao.getPlannedExercises(),
                workoutLogs = backupDao.getWorkoutLogs(),
                loggedExercises = backupDao.getLoggedExercises(),
                loggedSets = backupDao.getLoggedSets(),
                personalRecords = backupDao.getPersonalRecords(),
            )
        }

    override suspend fun restore(
        captured: ManualBackupCapture,
        accountId: AccountId,
        artifact: ValidatedRestoreArtifact,
        discardActiveSessionId: String?,
    ): Boolean {
        val bundle = artifact.bundle
        val lineage = artifact.lineage
        val validated = BackupBundleValidator.validate(bundle)
        // Build the complete bounded undo record before opening the mutation transaction. A
        // snapshot that cannot be represented safely must fail without touching Room.
        val localSnapshot = undoCodec.encode(captured.bundle)
        val baseline = captured.baseline?.also { it.validatedBundle() }
        val targetSnapshot = artifact.remoteSnapshot ?: codec.encode(validated.bundle)
        require(targetSnapshot.contentDigest == artifact.contentDigest)
        val oldBaselineChunks = baseline?.snapshot?.chunks.orEmpty()
        require(oldBaselineChunks.size <= BackupSnapshotCodec.MAX_CHUNKS)
        require(
            oldBaselineChunks.all { it.encodedByteCount <= BackupSnapshotCodec.MAX_CHUNK_BYTES }
        )
        return database.withTransaction {
            val backupDao = database.backupDao()
            val currentMetadata = checkNotNull(backupDao.getMetadata())
            if (!captureStillCurrent(currentMetadata, captured, accountId))
                return@withTransaction false
            val activeSession = database.sessionDao().getActiveSession()
            if (activeSession?.id != captured.activeSessionId) return@withTransaction false
            if ((activeSession?.id) != discardActiveSessionId && activeSession != null)
                return@withTransaction false
            if (activeSession == null && discardActiveSessionId != null)
                return@withTransaction false

            val localRows = localSnapshot.toUndoRows(RestoreUndoChunk.LOCAL_SNAPSHOT)
            val baselineRows =
                baseline?.snapshot?.toUndoRows(RestoreUndoChunk.REMOTE_BASELINE).orEmpty()
            val baselineSnapshot = baseline?.snapshot
            val prior = currentMetadata
            val undoMetadata =
                RestoreUndoMetadata(
                    slotIdentity = idProvider.newId(),
                    restoringOwnerUid = accountId.opaqueValue,
                    restoringInstallationId = prior.installationId,
                    previousOwnerUid = prior.ownerUid,
                    previousInstallationId = prior.installationId,
                    previousLocalChangeRevision = prior.localChangeRevision,
                    previousLastCompleteLocalRevision = prior.lastCompleteLocalRevision,
                    previousLastObservedRemoteBackupId = prior.lastObservedRemoteBackupId,
                    previousLastObservedRemoteGeneration = prior.lastObservedRemoteGeneration,
                    previousLastObservedRemoteDigest = prior.lastObservedRemoteDigest,
                    previousLastObservedSourceInstallationId =
                        prior.lastObservedSourceInstallationId,
                    previousLastObservedRemoteCompletedAt = prior.lastObservedRemoteCompletedAt,
                    snapshotFormatVersion = localSnapshot.formatVersion,
                    snapshotRevision = localSnapshot.localChangeRevision,
                    snapshotEntityCountsJson = localSnapshot.entityCounts.toCountsJson(),
                    snapshotByteCount = localSnapshot.encodedByteCount,
                    snapshotDigest = localSnapshot.contentDigest,
                    baselineBackupId = baseline?.summary?.backupId,
                    baselineGeneration = baseline?.generation,
                    baselineCompletedAt = baseline?.summary?.completedAtEpochMillis,
                    baselineSourceInstallationId = baseline?.summary?.sourceInstallationId,
                    baselineFormatVersion = baselineSnapshot?.formatVersion,
                    baselineRevision = baselineSnapshot?.localChangeRevision,
                    baselineEntityCountsJson = baselineSnapshot?.entityCounts?.toCountsJson(),
                    baselineByteCount = baselineSnapshot?.encodedByteCount,
                    baselineDigest = baselineSnapshot?.contentDigest,
                )
            backupDao.deleteRestoreUndoChunks()
            backupDao.deleteRestoreUndoMetadata()
            backupDao.insertRestoreUndoMetadata(undoMetadata)
            backupDao.insertRestoreUndoChunks(localRows + baselineRows)

            if (activeSession != null) {
                database.sessionDao().deleteSession(activeSession.id)
            }
            backupDao.deletePersonalRecords()
            backupDao.deleteWorkoutLogs()
            backupDao.deleteWeeklyPlans()
            backupDao.deleteBaselineChunks()

            val restored = validated.bundle
            if (restored.weeklyPlans.isNotEmpty()) {
                backupDao.insertWeeklyPlans(restored.weeklyPlans)
            }
            if (restored.plannedWorkouts.isNotEmpty()) {
                backupDao.insertPlannedWorkouts(restored.plannedWorkouts)
            }
            if (restored.plannedExercises.isNotEmpty()) {
                backupDao.insertPlannedExercises(restored.plannedExercises)
            }
            if (restored.workoutLogs.isNotEmpty()) {
                backupDao.insertWorkoutLogs(restored.workoutLogs)
            }
            if (restored.loggedExercises.isNotEmpty()) {
                backupDao.insertLoggedExercises(restored.loggedExercises)
            }
            if (restored.loggedSets.isNotEmpty()) {
                backupDao.insertLoggedSets(restored.loggedSets)
            }
            if (restored.personalRecords.isNotEmpty()) {
                backupDao.insertPersonalRecords(restored.personalRecords)
            }

            val restoredRevision = Math.addExact(currentMetadata.localChangeRevision, 1)
            val targetRows =
                BackupBaselineCodec.encode(
                    RemoteBackupArtifact(
                        com.example.ironpath.domain.backup.RemoteBackupSummary(
                            lineage.remoteBackupId,
                            lineage.completedAt,
                            lineage.sourceInstallationId,
                            targetSnapshot.entityCounts,
                        ),
                        lineage.remoteGeneration,
                        targetSnapshot,
                    ),
                    accountId.opaqueValue,
                    currentMetadata.installationId,
                )
            backupDao.deleteBaselineChunks()
            if (targetRows.isNotEmpty()) backupDao.insertBaselineChunks(targetRows)
            backupDao.updateMetadata(
                currentMetadata.copy(
                    ownerUid = accountId.opaqueValue,
                    localChangeRevision = restoredRevision,
                    lastCompleteLocalRevision = restoredRevision,
                    lastObservedRemoteBackupId = lineage.remoteBackupId,
                    lastObservedRemoteGeneration = lineage.remoteGeneration,
                    lastObservedRemoteDigest = lineage.remoteDigest,
                    lastObservedSourceInstallationId = lineage.sourceInstallationId,
                    lastObservedRemoteCompletedAt = lineage.completedAt,
                    requiresLineageReviewAfterUndo = false,
                )
            )
            // Keep the whole slot replacement and restore atomic. The prior slot was only
            // removed inside this transaction, so any insert failure also preserves it.
            true
        }
    }

    override suspend fun captureUndo(
        accountId: AccountId,
        installationId: String,
    ): ManualBackupUndoCapture? =
        database.withTransaction {
            val dao = database.backupDao()
            val metadata = dao.getRestoreUndoMetadata() ?: return@withTransaction null
            if (
                metadata.restoringOwnerUid != accountId.opaqueValue ||
                    metadata.restoringInstallationId != installationId
            )
                return@withTransaction null
            decodeUndo(metadata, dao.getRestoreUndoChunks())
        }

    override suspend fun undo(
        captured: ManualBackupCapture,
        accountId: AccountId,
        undo: ManualBackupUndoCapture,
    ): Boolean =
        database.withTransaction {
            val dao = database.backupDao()
            val currentMetadata = dao.getMetadata() ?: return@withTransaction false
            if (!captureStillCurrent(currentMetadata, captured, accountId))
                return@withTransaction false
            if (
                captured.activeSessionId != null || database.sessionDao().getActiveSession() != null
            )
                return@withTransaction false
            val persisted = dao.getRestoreUndoMetadata() ?: return@withTransaction false
            if (
                persisted.slotIdentity != undo.slotIdentity ||
                    persisted.restoringOwnerUid != accountId.opaqueValue ||
                    persisted.restoringInstallationId != currentMetadata.installationId
            )
                return@withTransaction false
            if (decodeUndo(persisted, dao.getRestoreUndoChunks()) != undo)
                return@withTransaction false

            val priorMetadata = undo.previousMetadata
            val restoredRevision = Math.addExact(currentMetadata.localChangeRevision, 1)
            require(priorMetadata.lastCompleteLocalRevision < restoredRevision) {
                "Undo baseline cannot be current at the new local revision"
            }
            val previousOwnerMatches = priorMetadata.ownerUid == accountId.opaqueValue
            val baselineToRestore = undo.baseline.takeIf { previousOwnerMatches }
            val baselineRows =
                baselineToRestore
                    ?.let {
                        BackupBaselineCodec.encode(
                            it,
                            priorMetadata.ownerUid ?: accountId.opaqueValue,
                            currentMetadata.installationId,
                        )
                    }
                    .orEmpty()

            dao.deletePersonalRecords()
            dao.deleteWorkoutLogs()
            dao.deleteWeeklyPlans()
            val prior = undo.bundle
            if (prior.weeklyPlans.isNotEmpty()) dao.insertWeeklyPlans(prior.weeklyPlans)
            if (prior.plannedWorkouts.isNotEmpty()) dao.insertPlannedWorkouts(prior.plannedWorkouts)
            if (prior.plannedExercises.isNotEmpty())
                dao.insertPlannedExercises(prior.plannedExercises)
            if (prior.workoutLogs.isNotEmpty()) dao.insertWorkoutLogs(prior.workoutLogs)
            if (prior.loggedExercises.isNotEmpty()) dao.insertLoggedExercises(prior.loggedExercises)
            if (prior.loggedSets.isNotEmpty()) dao.insertLoggedSets(prior.loggedSets)
            if (prior.personalRecords.isNotEmpty()) dao.insertPersonalRecords(prior.personalRecords)
            dao.deleteBaselineChunks()
            if (baselineRows.isNotEmpty()) dao.insertBaselineChunks(baselineRows)
            dao.updateMetadata(
                priorMetadata.copy(
                    installationId = currentMetadata.installationId,
                    localChangeRevision = restoredRevision,
                    // Keep the old marker exactly: it is strictly older than this revision and
                    // cannot claim that the restored pre-restore state is current remotely.
                    lastCompleteLocalRevision = priorMetadata.lastCompleteLocalRevision,
                    requiresLineageReviewAfterUndo = true,
                    lastObservedRemoteBackupId =
                        if (previousOwnerMatches) priorMetadata.lastObservedRemoteBackupId
                        else null,
                    lastObservedRemoteGeneration =
                        if (previousOwnerMatches) priorMetadata.lastObservedRemoteGeneration else 0,
                    lastObservedRemoteDigest =
                        if (previousOwnerMatches) priorMetadata.lastObservedRemoteDigest else null,
                    lastObservedSourceInstallationId =
                        if (previousOwnerMatches) priorMetadata.lastObservedSourceInstallationId
                        else null,
                    lastObservedRemoteCompletedAt =
                        if (previousOwnerMatches) priorMetadata.lastObservedRemoteCompletedAt
                        else null,
                )
            )
            dao.deleteRestoreUndoChunks()
            dao.deleteRestoreUndoMetadata()
            true
        }

    override suspend fun resetLocalProfile(
        pendingSignOutUid: String?,
    ): LocalProfileResetResult {
        installationValidationMutex.withLock {
            val resetMetadata =
                AccountBackupMetadata(
                    installationId = idProvider.newId(),
                    pendingSignOutUid = pendingSignOutUid,
                )
            try {
                database.withTransaction {
                    val backupDao = database.backupDao()
                    backupDao.deleteActiveSessions()
                    backupDao.deletePersonalRecords()
                    backupDao.deleteWorkoutLogs()
                    backupDao.deleteWeeklyPlans()
                    backupDao.deleteBaselineChunks()
                    backupDao.deleteRestoreUndoChunks()
                    backupDao.deleteRestoreUndoMetadata()
                    backupDao.insertMetadataIfAbsent(resetMetadata)
                    backupDao.updateMetadata(resetMetadata)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The database transaction rolls back, and the sentinel remains unchanged.
                return LocalProfileResetResult.NotCommitted
            }
            return LocalProfileResetResult.Committed(
                installationMarkerUpdated = writeSentinel(sentinel, resetMetadata.installationId)
            )
        }
    }

    override suspend fun clearPendingSignOut(uid: String): Boolean =
        database.withTransaction {
            val backupDao = database.backupDao()
            val current = backupDao.getMetadata() ?: return@withTransaction false
            if (current.pendingSignOutUid != uid) return@withTransaction false
            backupDao.updateMetadata(current.copy(pendingSignOutUid = null))
            true
        }

    suspend fun validateInstallation(): InstallationValidationResult =
        installationValidationMutex.withLock {
            var created = false
            val metadata =
                database.withTransaction {
                    val backupDao = database.backupDao()
                    if (backupDao.getMetadata() == null) {
                        backupDao.insertMetadataIfAbsent(
                            AccountBackupMetadata(installationId = idProvider.newId())
                        )
                        created = true
                    }
                    checkNotNull(backupDao.getMetadata())
                }
            val observedInstallationId =
                try {
                    sentinel.readInstallationId()
                } catch (_: Exception) {
                    return@withLock InstallationValidationResult.Failed
                }
            if (observedInstallationId == metadata.installationId) {
                return@withLock InstallationValidationResult.Validated
            }
            if (created) {
                return@withLock if (writeSentinel(sentinel, metadata.installationId)) {
                    InstallationValidationResult.Initialized
                } else {
                    InstallationValidationResult.Failed
                }
            }

            val rotated =
                database.withTransaction {
                    val backupDao = database.backupDao()
                    val current = checkNotNull(backupDao.getMetadata())
                    val replacement =
                        current.copy(
                            ownerUid = null,
                            installationId = idProvider.newId(),
                            lastCompleteLocalRevision = 0,
                            lastObservedRemoteBackupId = null,
                            lastObservedRemoteGeneration = 0,
                            lastObservedRemoteDigest = null,
                            lastObservedSourceInstallationId = null,
                            lastObservedRemoteCompletedAt = null,
                        )
                    backupDao.updateMetadata(replacement)
                    backupDao.deleteBaselineChunks()
                    backupDao.deleteRestoreUndoChunks()
                    backupDao.deleteRestoreUndoMetadata()
                    replacement
                }
            if (writeSentinel(sentinel, rotated.installationId)) {
                InstallationValidationResult.Transferred
            } else {
                InstallationValidationResult.Failed
            }
        }

    private suspend fun writeSentinel(
        sentinel: InstallationSentinel,
        installationId: String,
    ): Boolean =
        try {
            sentinel.writeInstallationId(installationId)
        } catch (_: Exception) {
            false
        }

    private suspend fun ensureMetadata() {
        val backupDao = database.backupDao()
        if (backupDao.getMetadata() == null) {
            backupDao.insertMetadataIfAbsent(
                AccountBackupMetadata(installationId = idProvider.newId()),
            )
        }
    }

    private suspend fun captureStillCurrent(
        current: AccountBackupMetadata,
        captured: ManualBackupCapture,
        accountId: AccountId,
    ): Boolean {
        if (
            current != captured.metadata ||
                current.installationId != captured.metadata.installationId ||
                (current.ownerUid != null && current.ownerUid != accountId.opaqueValue)
        )
            return false
        val dao = database.backupDao()
        val currentBundle = readIncludedBundle(current.localChangeRevision)
        if (
            undoCodec.encode(currentBundle).contentDigest !=
                undoCodec.encode(captured.bundle).contentDigest
        )
            return false
        val baseline = BackupBaselineCodec.decode(dao.getBaselineChunks(), current)
        if (baseline != captured.baseline) return false
        return database.sessionDao().getActiveSession()?.id == captured.activeSessionId
    }

    private suspend fun readIncludedBundle(revision: Long): BackupBundle {
        val dao = database.backupDao()
        return BackupBundle(
            revision,
            dao.getWeeklyPlans(),
            dao.getPlannedWorkouts(),
            dao.getPlannedExercises(),
            dao.getWorkoutLogs(),
            dao.getLoggedExercises(),
            dao.getLoggedSets(),
            dao.getPersonalRecords(),
        )
    }

    private fun EncodedBackupSnapshot.toUndoRows(kind: String): List<RestoreUndoChunk> {
        require(chunks.size in 1..BackupSnapshotCodec.MAX_CHUNKS)
        return chunks.map { chunk ->
            require(chunk.encodedByteCount <= BackupSnapshotCodec.MAX_CHUNK_BYTES)
            RestoreUndoChunk(
                kind,
                chunk.index,
                chunk.payload,
                chunk.encodedByteCount,
                chunk.digest,
            )
        }
    }

    private fun Map<String, Int>.toCountsJson(): String =
        JsonObject(mapValues { JsonPrimitive(it.value) }).toString()

    private fun decodeUndo(
        metadata: RestoreUndoMetadata,
        rows: List<RestoreUndoChunk>,
    ): ManualBackupUndoCapture {
        require(metadata.id == RestoreUndoMetadata.SINGLETON_ID)
        val localSnapshot =
            rows.snapshot(
                RestoreUndoChunk.LOCAL_SNAPSHOT,
                metadata.snapshotFormatVersion,
                metadata.snapshotRevision,
                metadata.snapshotEntityCountsJson,
                metadata.snapshotByteCount,
                metadata.snapshotDigest,
            )
        val previousMetadata =
            AccountBackupMetadata(
                ownerUid = metadata.previousOwnerUid,
                installationId = metadata.previousInstallationId,
                localChangeRevision = metadata.previousLocalChangeRevision,
                lastCompleteLocalRevision = metadata.previousLastCompleteLocalRevision,
                lastObservedRemoteBackupId = metadata.previousLastObservedRemoteBackupId,
                lastObservedRemoteGeneration = metadata.previousLastObservedRemoteGeneration,
                lastObservedRemoteDigest = metadata.previousLastObservedRemoteDigest,
                lastObservedSourceInstallationId =
                    metadata.previousLastObservedSourceInstallationId,
                lastObservedRemoteCompletedAt = metadata.previousLastObservedRemoteCompletedAt,
            )
        val baselineFields =
            listOf(
                metadata.baselineBackupId,
                metadata.baselineGeneration,
                metadata.baselineCompletedAt,
                metadata.baselineSourceInstallationId,
                metadata.baselineFormatVersion,
                metadata.baselineRevision,
                metadata.baselineEntityCountsJson,
                metadata.baselineByteCount,
                metadata.baselineDigest,
            )
        val baseline =
            if (baselineFields.all { it == null }) {
                require(rows.none { it.kind == RestoreUndoChunk.REMOTE_BASELINE })
                null
            } else {
                require(baselineFields.all { it != null }) { "Incomplete undo baseline metadata" }
                val snapshot =
                    rows.snapshot(
                        RestoreUndoChunk.REMOTE_BASELINE,
                        checkNotNull(metadata.baselineFormatVersion),
                        checkNotNull(metadata.baselineRevision),
                        checkNotNull(metadata.baselineEntityCountsJson),
                        checkNotNull(metadata.baselineByteCount),
                        checkNotNull(metadata.baselineDigest),
                    )
                RemoteBackupArtifact(
                        com.example.ironpath.domain.backup.RemoteBackupSummary(
                            checkNotNull(metadata.baselineBackupId),
                            checkNotNull(metadata.baselineCompletedAt),
                            checkNotNull(metadata.baselineSourceInstallationId),
                            snapshot.entityCounts,
                        ),
                        checkNotNull(metadata.baselineGeneration),
                        snapshot,
                    )
                    .also { it.validatedBundle() }
            }
        require(localSnapshot.localChangeRevision == previousMetadata.localChangeRevision)
        return ManualBackupUndoCapture(
            metadata.slotIdentity,
            metadata.restoringOwnerUid,
            metadata.restoringInstallationId,
            previousMetadata,
            undoCodec.decode(localSnapshot),
            baseline,
        )
    }

    private fun List<RestoreUndoChunk>.snapshot(
        kind: String,
        formatVersion: Int,
        revision: Long,
        entityCountsJson: String,
        byteCount: Int,
        contentDigest: String,
    ): EncodedBackupSnapshot {
        val selected = filter { it.kind == kind }.sortedBy { it.chunkIndex }
        require(selected.isNotEmpty() && selected.size <= BackupSnapshotCodec.MAX_CHUNKS)
        require(selected.map { it.chunkIndex } == selected.indices.toList())
        val counts =
            Json.parseToJsonElement(entityCountsJson).jsonObject.mapValues {
                it.value.jsonPrimitive.int
            }
        return EncodedBackupSnapshot(
            formatVersion,
            revision,
            selected.map { row ->
                require(row.payloadByteCount <= BackupSnapshotCodec.MAX_CHUNK_BYTES)
                require(row.payload.toByteArray(Charsets.UTF_8).size == row.payloadByteCount)
                BackupChunk(row.chunkIndex, row.payload, row.payloadByteCount, row.payloadDigest)
            },
            counts,
            byteCount,
            contentDigest,
        )
    }

    private object NonPersistentInstallationSentinel : InstallationSentinel {
        override suspend fun readInstallationId(): String? = null

        override suspend fun writeInstallationId(installationId: String): Boolean = true
    }
}
