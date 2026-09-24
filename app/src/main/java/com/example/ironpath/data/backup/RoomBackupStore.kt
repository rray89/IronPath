package com.example.ironpath.data.backup

import androidx.room.withTransaction
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.entity.AccountBackupMetadata
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.identity.IdProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class RoomBackupStore
@Inject
constructor(
    private val database: IronPathDatabase,
    private val idProvider: IdProvider,
    private val sentinel: InstallationSentinel = NonPersistentInstallationSentinel,
) : BackupChangeTracker, ManualBackupLocalStore {
    private val installationValidationMutex = Mutex()

    override suspend fun capture(): ManualBackupCapture =
        database.withTransaction {
            val bundle = export()
            val metadata = checkNotNull(database.backupDao().getMetadata())
            ManualBackupCapture(
                metadata,
                bundle,
                BackupBaselineCodec.decode(database.backupDao().getBaselineChunks(), metadata),
                database.sessionDao().getActiveSession()?.id
            )
        }

    override suspend fun associateEmpty(
        captured: ManualBackupCapture,
        accountId: AccountId
    ): Boolean =
        database.withTransaction {
            val current = checkNotNull(database.backupDao().getMetadata())
            if (
                !sameAuthority(current, captured, accountId) ||
                    !sameContent(current, captured) ||
                    database.backupDao().hasIncludedData()
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
            BackupSnapshotCodec().encode(export()).contentDigest ==
                BackupSnapshotCodec().encode(captured.bundle).contentDigest

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
                lastObservedRemoteCompletedAt = backup.summary.completedAtEpochMillis
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

    suspend fun restore(
        artifact: ValidatedRestoreArtifact,
        activeSessionDisposition: ActiveSessionRestoreDisposition,
    ): RestoreResult {
        val bundle = artifact.bundle
        val lineage = artifact.lineage
        val validated =
            try {
                BackupBundleValidator.validate(bundle)
            } catch (failure: IllegalArgumentException) {
                return RestoreResult.InvalidSnapshot(
                    failure.message ?: "Snapshot validation failed",
                )
            }
        return database.withTransaction {
            val backupDao = database.backupDao()
            val activeSession = database.sessionDao().getActiveSession()
            if (activeSession != null) {
                val confirmedSessionId =
                    (activeSessionDisposition as? ActiveSessionRestoreDisposition.Discard)
                        ?.confirmedSessionId
                if (confirmedSessionId != activeSession.id) {
                    return@withTransaction RestoreResult.ActiveSessionRequiresConfirmation(
                        activeSession.id
                    )
                }
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

            ensureMetadata()
            val metadata = checkNotNull(backupDao.getMetadata())
            val restoredRevision = Math.addExact(metadata.localChangeRevision, 1)
            backupDao.updateMetadata(
                metadata.copy(
                    ownerUid = lineage.ownerUid,
                    localChangeRevision = restoredRevision,
                    lastCompleteLocalRevision = restoredRevision - 1,
                    lastObservedRemoteBackupId = lineage.remoteBackupId,
                    lastObservedRemoteGeneration = lineage.remoteGeneration,
                    lastObservedRemoteDigest = lineage.remoteDigest,
                    lastObservedSourceInstallationId = lineage.sourceInstallationId,
                    lastObservedRemoteCompletedAt = lineage.completedAt,
                )
            )
            RestoreResult.Success(
                artifact.nulledProvenanceFields + validated.nulledProvenanceFields
            )
        }
    }

    suspend fun resetLocalProfile() {
        installationValidationMutex.withLock {
            val resetMetadata = AccountBackupMetadata(installationId = idProvider.newId())
            check(writeSentinel(sentinel, resetMetadata.installationId)) {
                "Installation sentinel could not be updated before local reset"
            }
            database.withTransaction {
                val backupDao = database.backupDao()
                backupDao.deleteActiveSessions()
                backupDao.deletePersonalRecords()
                backupDao.deleteWorkoutLogs()
                backupDao.deleteWeeklyPlans()
                backupDao.deleteBaselineChunks()
                backupDao.insertMetadataIfAbsent(resetMetadata)
                backupDao.updateMetadata(resetMetadata)
            }
        }
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

    private object NonPersistentInstallationSentinel : InstallationSentinel {
        override suspend fun readInstallationId(): String? = null

        override suspend fun writeInstallationId(installationId: String): Boolean = true
    }
}
