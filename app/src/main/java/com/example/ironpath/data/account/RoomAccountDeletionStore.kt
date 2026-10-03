package com.example.ironpath.data.account

import androidx.room.withTransaction
import com.example.ironpath.data.backup.InstallationSentinel
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.entity.AccountBackupMetadata
import com.example.ironpath.data.local.entity.AccountDeletionDraftEntity
import com.example.ironpath.data.local.entity.AccountDeletionJournal
import com.example.ironpath.domain.account.AccountDeletionProgress
import com.example.ironpath.domain.account.AccountDeletionRemoteState
import com.example.ironpath.domain.account.AccountDeletionRequest
import com.example.ironpath.domain.account.AccountDeletionStage
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
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
        // Real deletion must first persist a server-acknowledged v2 reservation.
        if (request.serviceBinding != null) return null
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
            if (existing != null && !existing.isTerminal()) {
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

    override suspend fun createDraft(request: AccountDeletionRequest): AccountDeletionDraft? =
        database.withTransaction {
            if (!request.hasValidScope() || request.serviceBinding.isNullOrBlank())
                return@withTransaction null
            val dao = database.accountDeletionDao()
            if (dao.getJournal()?.isTerminal() == false) return@withTransaction null
            val metadata = database.backupDao().getMetadata() ?: return@withTransaction null
            if (!metadata.matchesRequest(request)) return@withTransaction null
            val existing = dao.getDraft()
            if (
                existing != null &&
                    existing.toDraft().request == request &&
                    existing.installationId == metadata.installationId
            )
                return@withTransaction existing.toDraft()
            val createdAt = timeProvider.epochMillis()
            if (createdAt < 0) return@withTransaction null
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val draft =
                AccountDeletionDraftEntity(
                    operationId = UUID.randomUUID().toString(),
                    receiptSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret),
                    accountId = request.accountId.opaqueValue,
                    sessionEpoch = request.sessionEpoch,
                    profileGeneration = request.profileGeneration,
                    expectedLocalOwnerUid = request.expectedLocalOwnerUid,
                    serviceBinding = request.serviceBinding,
                    installationId = metadata.installationId,
                    createdAtEpochMillis = createdAt,
                )
            dao.saveDraft(draft)
            draft.toDraft()
        }

    override suspend fun discardDraft(draft: AccountDeletionDraft): Boolean =
        database.withTransaction {
            val dao = database.accountDeletionDao()
            if (dao.getJournal()?.isTerminal() == false) return@withTransaction false
            val persisted = dao.getDraft() ?: return@withTransaction false
            if (persisted.toDraft() != draft) return@withTransaction false
            // Only this non-blocking capability is retired. No local graph or ownership changes.
            dao.deleteDraft(draft.operationId)
            true
        }

    override suspend fun prepareReservation(
        draft: AccountDeletionDraft,
        receipt: DeletionServiceReceipt,
    ): AccountDeletionProgress? =
        database.withTransaction {
            if (
                !draft.request.hasValidScope() ||
                    draft.request.serviceBinding.isNullOrBlank() ||
                    !RECEIPT_SECRET.matches(draft.receiptSecret) ||
                    !receipt.validFor(draft.operationId) ||
                    receipt.state == AccountDeletionRemoteState.CANCELLED_NO_DELETE
            )
                return@withTransaction null
            val dao = database.accountDeletionDao()
            val metadata = database.backupDao().getMetadata() ?: return@withTransaction null
            if (
                !metadata.matchesRequest(draft.request) ||
                    metadata.installationId != draft.installationId
            )
                return@withTransaction null
            val existing = dao.getJournal()
            if (existing != null && !existing.isTerminal()) {
                return@withTransaction existing
                    .takeIf {
                        it.toDraft() == draft &&
                            it.subjectBinding == receipt.subjectBinding &&
                            it.receiptVersion == receipt.version &&
                            it.remoteState == receipt.state.name
                    }
                    ?.toProgress()
            }
            val persisted = dao.getDraft() ?: return@withTransaction null
            if (persisted.toDraft() != draft) return@withTransaction null
            val prepared =
                AccountDeletionJournal(
                    operationId = draft.operationId,
                    accountId = draft.request.accountId.opaqueValue,
                    sessionEpoch = draft.request.sessionEpoch,
                    profileGeneration = draft.request.profileGeneration,
                    expectedLocalOwnerUid = draft.request.expectedLocalOwnerUid,
                    serviceBinding = draft.request.serviceBinding,
                    stage = AccountDeletionStage.PREPARED.name,
                    createdAtEpochMillis = persisted.createdAtEpochMillis,
                    receiptSecret = draft.receiptSecret,
                    subjectBinding = receipt.subjectBinding,
                    receiptVersion = receipt.version,
                    remoteState = receipt.state.name,
                    installationId = draft.installationId,
                )
            dao.save(prepared)
            dao.deleteDraft(draft.operationId)
            prepared.toProgress()
        }

    override suspend fun recordReceipt(
        expected: AccountDeletionProgress,
        receipt: DeletionServiceReceipt,
    ): AccountDeletionProgress? =
        database.withTransaction {
            val dao = database.accountDeletionDao()
            val current = dao.getJournal() ?: return@withTransaction null
            val metadata = database.backupDao().getMetadata() ?: return@withTransaction null
            if (
                current.toProgress() != expected ||
                    !current.accepts(receipt) ||
                    !metadata.matchesProfile(expected)
            )
                return@withTransaction null
            val updated =
                current.copy(receiptVersion = receipt.version, remoteState = receipt.state.name)
            dao.update(updated)
            updated.toProgress()
        }

    override suspend fun cancelReservation(
        expected: AccountDeletionProgress,
        receipt: DeletionServiceReceipt,
    ): Boolean =
        database.withTransaction {
            val dao = database.accountDeletionDao()
            val current = dao.getJournal() ?: return@withTransaction false
            val metadata = database.backupDao().getMetadata() ?: return@withTransaction false
            if (
                current.toProgress() != expected ||
                    !current.accepts(receipt) ||
                    receipt.state != AccountDeletionRemoteState.CANCELLED_NO_DELETE ||
                    current.stage != AccountDeletionStage.PREPARED.name ||
                    !metadata.matchesProfile(expected)
            )
                return@withTransaction false
            // Retire the barrier and persist terminal cancellation together; touch no profile rows.
            dao.update(
                current.copy(
                    stage = AccountDeletionStage.CANCELLED.name,
                    receiptVersion = receipt.version,
                    remoteState = receipt.state.name,
                )
            )
            true
        }

    override suspend fun matchesProfile(expected: AccountDeletionProgress): Boolean =
        database.withTransaction {
            val journal = database.accountDeletionDao().getJournal() ?: return@withTransaction false
            val metadata = database.backupDao().getMetadata() ?: return@withTransaction false
            journal.matches(expected) &&
                metadata.matchesProfile(expected) &&
                (journal.serviceBinding == null || journal.hasReservation()) &&
                !journal.isTerminal()
        }

    override suspend fun advance(
        expected: AccountDeletionProgress,
        next: AccountDeletionStage,
    ): Boolean =
        database.withTransaction {
            val dao = database.accountDeletionDao()
            val current = dao.getJournal() ?: return@withTransaction false
            if (
                !current.matches(expected) ||
                    current.isTerminal() ||
                    next !in
                        setOf(
                            AccountDeletionStage.BACKUPS_PURGED,
                            AccountDeletionStage.ACCOUNT_TOMBSTONED
                        ) ||
                    !current.cleanupAuthorized()
            )
                return@withTransaction false
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
                        !metadata.matchesProfile(expected) ||
                        !currentJournal.cleanupAuthorized()
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
            serviceBinding == progress.serviceBinding &&
            receiptSecret == progress.receiptSecret &&
            subjectBinding == progress.subjectBinding &&
            receiptVersion == progress.receiptVersion &&
            remoteState == progress.remoteState?.name &&
            installationId == progress.installationId

    private fun AccountBackupMetadata.matchesProfile(progress: AccountDeletionProgress) =
        (progress.expectedLocalOwnerUid == null ||
            progress.expectedLocalOwnerUid == progress.accountId.opaqueValue) &&
            ownerUid == progress.expectedLocalOwnerUid &&
            profileGeneration == progress.profileGeneration &&
            (progress.installationId == null || installationId == progress.installationId) &&
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
            receiptSecret = receiptSecret,
            subjectBinding = subjectBinding,
            receiptVersion = receiptVersion,
            remoteState = remoteState?.let { AccountDeletionRemoteState.valueOf(it) },
            installationId = installationId,
        )

    private fun AccountDeletionJournal.isTerminal() =
        stage == AccountDeletionStage.COMPLETE.name || stage == AccountDeletionStage.CANCELLED.name

    private fun AccountDeletionRequest.hasValidScope() =
        profileGeneration >= 0 &&
            sessionEpoch >= 0 &&
            (expectedLocalOwnerUid == null || expectedLocalOwnerUid == accountId.opaqueValue)

    private fun AccountBackupMetadata.matchesRequest(request: AccountDeletionRequest) =
        ownerUid == request.expectedLocalOwnerUid &&
            profileGeneration == request.profileGeneration &&
            pendingSignOutUid == null &&
            installationId.isNotBlank()

    private fun AccountDeletionDraftEntity.toDraft() =
        AccountDeletionDraft(
            operationId,
            receiptSecret,
            AccountDeletionRequest(
                com.example.ironpath.domain.account.AccountId(accountId),
                sessionEpoch,
                profileGeneration,
                expectedLocalOwnerUid,
                serviceBinding
            ),
            installationId,
        )

    private fun AccountDeletionJournal.toDraft(): AccountDeletionDraft? {
        val secret = receiptSecret ?: return null
        val installation = installationId ?: return null
        return AccountDeletionDraft(
            operationId,
            secret,
            AccountDeletionRequest(
                com.example.ironpath.domain.account.AccountId(accountId),
                sessionEpoch,
                profileGeneration,
                expectedLocalOwnerUid,
                serviceBinding
            ),
            installation
        )
    }

    private fun DeletionServiceReceipt.validFor(expectedOperationId: String) =
        operationId == expectedOperationId && version > 0 && SUBJECT_BINDING.matches(subjectBinding)

    private fun AccountDeletionJournal.hasReservation() =
        !serviceBinding.isNullOrBlank() &&
            receiptSecret?.let(RECEIPT_SECRET::matches) == true &&
            subjectBinding?.let(SUBJECT_BINDING::matches) == true &&
            receiptVersion > 0 &&
            !installationId.isNullOrBlank() &&
            remoteState != null

    private fun AccountDeletionJournal.cleanupAuthorized() =
        serviceBinding == null ||
            (hasReservation() && remoteState == AccountDeletionRemoteState.COMPLETE.name)

    private fun AccountDeletionJournal.accepts(receipt: DeletionServiceReceipt): Boolean {
        if (
            !hasReservation() ||
                !receipt.validFor(operationId) ||
                subjectBinding != receipt.subjectBinding ||
                receipt.version < receiptVersion
        )
            return false
        val old =
            AccountDeletionRemoteState.entries.firstOrNull { it.name == remoteState }
                ?: return false
        if (receipt.version == receiptVersion && old != receipt.state) return false
        return when (old) {
            AccountDeletionRemoteState.RESERVED -> true
            AccountDeletionRemoteState.PENDING ->
                receipt.state in
                    setOf(AccountDeletionRemoteState.PENDING, AccountDeletionRemoteState.COMPLETE)
            AccountDeletionRemoteState.COMPLETE ->
                receipt.state == AccountDeletionRemoteState.COMPLETE
            AccountDeletionRemoteState.CANCELLED_NO_DELETE ->
                receipt.state == AccountDeletionRemoteState.CANCELLED_NO_DELETE
        }
    }

    private companion object {
        val RECEIPT_SECRET = Regex("[A-Za-z0-9_-]{43}")
        val SUBJECT_BINDING = Regex("[a-f0-9]{64}")
    }

    private fun String.toDeletionStage(): AccountDeletionStage =
        AccountDeletionStage.entries.firstOrNull { it.name == this }
            ?: error("Unsupported account deletion stage")
}
