package com.example.ironpath.data.account

import com.example.ironpath.data.backup.RemoteAccountPurge
import com.example.ironpath.data.backup.RemoteBackupStore
import com.example.ironpath.domain.account.AccountDeletionManager
import com.example.ironpath.domain.account.AccountDeletionProgress
import com.example.ironpath.domain.account.AccountDeletionRequest
import com.example.ironpath.domain.account.AccountDeletionResult
import com.example.ironpath.domain.account.AccountDeletionStage
import com.example.ironpath.domain.account.AccountSessionAdapter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Retryable deletion sequence for the deterministic demo account and local file-backed backups. */
@Singleton
class DeterministicAccountDeletionManager
@Inject
constructor(
    private val store: RoomAccountDeletionStore,
    private val remote: RemoteBackupStore,
    private val sessions: AccountSessionAdapter,
    private val operationGate: AccountSessionOperationGate,
) : AccountDeletionManager {
    private val mutex = Mutex()

    override suspend fun pending(): AccountDeletionProgress? =
        store.journal()?.takeIf {
            it.stage != AccountDeletionStage.COMPLETE && it.stage != AccountDeletionStage.CANCELLED
        }

    override suspend fun recoverAtStartup(): AccountDeletionResult =
        mutex.withLock {
            withContext(NonCancellable) {
                val progress = pending()
                if (progress == null) {
                    operationGate.reopenAdmission()
                    return@withContext AccountDeletionResult.Idle
                }
                operationGate.closeAdmission()
                val result = resume(progress)
                if (result == AccountDeletionResult.Completed) operationGate.reopenAdmission()
                result
            }
        }

    override suspend fun delete(request: AccountDeletionRequest): AccountDeletionResult =
        mutex.withLock {
            if (request.serviceBinding != null) return@withLock AccountDeletionResult.Unavailable
            val progress =
                store.prepare(request) ?: return@withLock AccountDeletionResult.Unavailable
            operationGate.closeAdmission()
            withContext(NonCancellable) { resume(progress) }
        }

    override suspend fun retry(): AccountDeletionResult =
        mutex.withLock {
            val progress = pending() ?: return@withLock AccountDeletionResult.Idle
            operationGate.closeAdmission()
            withContext(NonCancellable) { resume(progress) }
        }

    private suspend fun resume(initial: AccountDeletionProgress): AccountDeletionResult {
        var progress = initial
        if (progress.serviceBinding != null) return retryRequired(progress)
        return try {
            while (progress.stage != AccountDeletionStage.COMPLETE) {
                when (progress.stage) {
                    AccountDeletionStage.PREPARED -> {
                        when (remote.purgeAccount(progress.accountId)) {
                            RemoteAccountPurge.Completed -> Unit
                            RemoteAccountPurge.Unavailable,
                            is RemoteAccountPurge.Failed -> return retryRequired(progress)
                        }
                        if (!store.advance(progress, AccountDeletionStage.BACKUPS_PURGED))
                            return retryRequired(current(progress))
                        progress = progress.copy(stage = AccountDeletionStage.BACKUPS_PURGED)
                    }
                    AccountDeletionStage.BACKUPS_PURGED -> {
                        if (!sessions.deleteDemoAccount(progress.accountId))
                            return retryRequired(progress)
                        if (!store.advance(progress, AccountDeletionStage.ACCOUNT_TOMBSTONED))
                            return retryRequired(current(progress))
                        progress = progress.copy(stage = AccountDeletionStage.ACCOUNT_TOMBSTONED)
                    }
                    AccountDeletionStage.ACCOUNT_TOMBSTONED -> {
                        if (!store.clearLocalProfile(progress))
                            return retryRequired(current(progress))
                        progress = progress.copy(stage = AccountDeletionStage.LOCAL_CLEARED)
                    }
                    AccountDeletionStage.LOCAL_CLEARED -> {
                        if (!store.ensureInstallationMarker(progress))
                            return retryRequired(progress)
                        if (!sessions.clearDeletedSession(progress.accountId))
                            return retryRequired(progress)
                        if (sessions.readSession() != null) return retryRequired(progress)
                        if (!store.markComplete(progress)) return retryRequired(current(progress))
                        progress = progress.copy(stage = AccountDeletionStage.COMPLETE)
                    }
                    AccountDeletionStage.COMPLETE -> Unit
                    AccountDeletionStage.CANCELLED -> return AccountDeletionResult.Cancelled
                }
            }
            AccountDeletionResult.Completed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            retryRequired(current(progress))
        }
    }

    private suspend fun current(fallback: AccountDeletionProgress): AccountDeletionProgress =
        store.journal()?.takeIf { it.operationId == fallback.operationId } ?: fallback

    private fun retryRequired(progress: AccountDeletionProgress) =
        AccountDeletionResult.RetryRequired(progress)
}
