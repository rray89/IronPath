package com.example.ironpath.data.account

import androidx.room.withTransaction
import com.example.ironpath.data.backup.InstallationSentinel
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.entity.AccountBackupMetadata
import com.example.ironpath.data.local.entity.AccountDeletionJournal
import com.example.ironpath.domain.account.AccountDeletionProgress
import com.example.ironpath.domain.account.AccountDeletionRequest
import com.example.ironpath.domain.account.AccountDeletionStage
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

/** Durable deletion journal and atomic local cleanup shared by demo and service-backed deletion. */
@Singleton
class RoomAccountDeletionStore
@Inject
constructor(
    private val database: IronPathDatabase,
    private val idProvider: IdProvider,
    private val timeProvider: TimeProvider,
    private val sentinel: InstallationSentinel,
) : AccountDeletionStore {
    override suspend fun journal(): AccountDeletionProgress? =
        database.accountDeletionDao().getJournal()?.toProgress()

    override suspend fun prepare(request: AccountDeletionRequest): AccountDeletionProgress? {
        if (
            request.expectedLocalOwnerUid != null &&
                request.expectedLocalOwnerUid != request.accountId.opaqueValue
        )
            return null
        val operationId = idProvider.newId()
        val createdAt = timeProvider.epochMillis()
        return database.withTransaction {
            val dao = database.accountDeletionDao()
            val existing = dao.getJournal()
            if (existing != null && existing.stage != AccountDeletionStage.COMPLETE.name) {
                return@withTransaction existing
                    .takeIf {
                        it.accountId == request.accountId.opaqueValue &&
                            it.sessionEpoch == request.sessionEpoch &&
                            it.profileGeneration == request.profileGeneration &&
                            it.expectedLocalOwnerUid == request.expectedLocalOwnerUid &&
                            it.serviceBinding == request.serviceBinding
                    }
                    ?.toProgress()
            }
            val metadata = database.backupDao().getMetadata() ?: return@withTransaction null
            if (
                metadata.ownerUid != request.expectedLocalOwnerUid ||
                    metadata.profileGeneration != request.profileGeneration ||
                    metadata.pendingSignOutUid != null ||
                    createdAt < 0
            )
                return@withTransaction null
            val prepared =
                AccountDeletionJournal(
                    operationId = operationId,
                    accountId = request.accountId.opaqueValue,
                    sessionEpoch = request.sessionEpoch,
                    profileGeneration = request.profileGeneration,
                    expectedLocalOwnerUid = request.expectedLocalOwnerUid,
                    serviceBinding = request.serviceBinding,
                    stage = AccountDeletionStage.PREPARED.name,
                    createdAtEpochMillis = createdAt,
                )
            dao.save(prepared)
            prepared.toProgress()
        }
    }

    override suspend fun matchesProfile(expected: AccountDeletionProgress): Boolean =
        database.withTransaction {
            val journal = database.accountDeletionDao().getJournal() ?: return@withTransaction false
            val metadata = database.backupDao().getMetadata() ?: return@withTransaction false
            journal.matches(expected) && metadata.matchesProfile(expected)
        }

    override suspend fun advance(
        expected: AccountDeletionProgress,
        next: AccountDeletionStage,
    ): Boolean =
        database.withTransaction {
            val dao = database.accountDeletionDao()
            val current = dao.getJournal() ?: return@withTransaction false
            if (!current.matches(expected)) return@withTransaction false
            val stage = current.stage.toDeletionStage()
            if (stage.ordinal >= next.ordinal) return@withTransaction true
            if (stage.ordinal + 1 != next.ordinal) return@withTransaction false
            dao.update(current.copy(stage = next.name))
            true
        }

    /** Data reset and the journal stage advance commit in the same Room transaction. */
    override suspend fun clearLocalProfile(expected: AccountDeletionProgress): Boolean {
        val newInstallationId = idProvider.newId()
        val newGeneration =
            database.withTransaction {
                val dao = database.accountDeletionDao()
                val currentJournal = dao.getJournal() ?: return@withTransaction null
                val metadata = database.backupDao().getMetadata() ?: return@withTransaction null
                if (
                    !currentJournal.matches(expected) ||
                        currentJournal.stage != AccountDeletionStage.ACCOUNT_TOMBSTONED.name ||
                        !metadata.matchesProfile(expected)
                )
                    return@withTransaction null

                val nextGeneration = Math.addExact(metadata.profileGeneration, 1)
                val backup = database.backupDao()
                backup.deleteActiveSessions()
                backup.deletePersonalRecords()
                backup.deleteWorkoutLogs()
                backup.deleteWeeklyPlans()
                backup.deleteBaselineChunks()
                backup.deleteRestoreUndoChunks()
                backup.deleteRestoreUndoMetadata()
                backup.updateMetadata(
                    AccountBackupMetadata(
                        installationId = newInstallationId,
                        profileGeneration = nextGeneration,
                    )
                )
                dao.update(currentJournal.copy(stage = AccountDeletionStage.LOCAL_CLEARED.name))
                nextGeneration
            } ?: return false
        return sentinel.writeInstallationId(newInstallationId) &&
            database.backupDao().getMetadata()?.profileGeneration == newGeneration
    }

    override suspend fun ensureInstallationMarker(expected: AccountDeletionProgress): Boolean {
        val journal = database.accountDeletionDao().getJournal() ?: return false
        val metadata = database.backupDao().getMetadata() ?: return false
        if (
            !journal.matches(expected) ||
                journal.stage != AccountDeletionStage.LOCAL_CLEARED.name ||
                metadata.ownerUid != null ||
                metadata.pendingSignOutUid != null ||
                metadata.profileGeneration != expected.profileGeneration + 1
        )
            return false
        return sentinel.writeInstallationId(metadata.installationId)
    }

    override suspend fun markComplete(expected: AccountDeletionProgress): Boolean =
        database.withTransaction {
            val dao = database.accountDeletionDao()
            val journal = dao.getJournal() ?: return@withTransaction false
            val metadata = database.backupDao().getMetadata() ?: return@withTransaction false
            if (
                !journal.matches(expected) ||
                    journal.stage != AccountDeletionStage.LOCAL_CLEARED.name ||
                    metadata.ownerUid != null ||
                    metadata.pendingSignOutUid != null ||
                    metadata.profileGeneration != expected.profileGeneration + 1
            )
                return@withTransaction false
            dao.update(journal.copy(stage = AccountDeletionStage.COMPLETE.name))
            true
        }

    private fun AccountDeletionJournal.matches(progress: AccountDeletionProgress) =
        operationId == progress.operationId &&
            accountId == progress.accountId.opaqueValue &&
            sessionEpoch == progress.sessionEpoch &&
            profileGeneration == progress.profileGeneration &&
            expectedLocalOwnerUid == progress.expectedLocalOwnerUid &&
            serviceBinding == progress.serviceBinding

    private fun AccountBackupMetadata.matchesProfile(progress: AccountDeletionProgress) =
        (progress.expectedLocalOwnerUid == null ||
            progress.expectedLocalOwnerUid == progress.accountId.opaqueValue) &&
            ownerUid == progress.expectedLocalOwnerUid &&
            profileGeneration == progress.profileGeneration &&
            pendingSignOutUid == null

    private fun AccountDeletionJournal.toProgress() =
        AccountDeletionProgress(
            operationId = operationId,
            accountId = com.example.ironpath.domain.account.AccountId(accountId),
            sessionEpoch = sessionEpoch,
            profileGeneration = profileGeneration,
            stage = stage.toDeletionStage(),
            expectedLocalOwnerUid = expectedLocalOwnerUid,
            serviceBinding = serviceBinding,
        )

    private fun String.toDeletionStage(): AccountDeletionStage =
        AccountDeletionStage.entries.firstOrNull { it.name == this }
            ?: error("Unsupported account deletion stage")
}
