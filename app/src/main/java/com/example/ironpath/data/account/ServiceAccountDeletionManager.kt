package com.example.ironpath.data.account

import com.example.ironpath.data.backup.InstallationGuard
import com.example.ironpath.data.backup.InstallationValidationResult
import com.example.ironpath.domain.account.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Local data stays locked until an authoritative durable job verifies remote + Auth removal. */
@Singleton
class ServiceAccountDeletionManager
@Inject
constructor(
    private val store: AccountDeletionStore,
    private val service: AccountDeletionService,
    private val identity: AccountDeletionIdentity,
    private val gate: AccountSessionOperationGate,
    private val installation: InstallationGuard,
) : AccountDeletionManager {
    private val mutex = Mutex()

    override suspend fun pending() =
        store.journal()?.takeIf { it.stage != AccountDeletionStage.COMPLETE }

    override suspend fun delete(request: AccountDeletionRequest): AccountDeletionResult =
        mutex.withLock {
            pending()?.let {
                return@withLock AccountDeletionResult.RetryRequired(it)
            }
            if (
                (request.expectedLocalOwnerUid != null &&
                    request.expectedLocalOwnerUid != request.accountId.opaqueValue) ||
                    identity.currentAccount() != request.accountId ||
                    installation.validate() !in
                        setOf(
                            InstallationValidationResult.Validated,
                            InstallationValidationResult.Initialized
                        )
            ) {
                return@withLock AccountDeletionResult.Unavailable
            }
            if (!service.available()) return@withLock AccountDeletionResult.Unavailable
            val authentication = identity.reauthenticate(request.accountId)
            when (authentication) {
                DeletionReauthentication.Cancelled ->
                    return@withLock AccountDeletionResult.Cancelled
                is DeletionReauthentication.Failed ->
                    return@withLock AccountDeletionResult.Failed(authentication.reason)
                is DeletionReauthentication.Authenticated -> Unit
            }
            if (identity.currentAccount() != request.accountId)
                return@withLock AccountDeletionResult.Cancelled
            // A cancelled chooser never reaches this first durable write. From this point, no
            // cancel.
            withContext(NonCancellable) {
                val progress =
                    store.prepare(request.copy(serviceBinding = service.binding))
                        ?: return@withContext AccountDeletionResult.Unavailable
                gate.closeAdmission()
                resume(progress, authentication.token, allowReauthentication = false)
            }
        }

    override suspend fun recoverAtStartup(): AccountDeletionResult = recover(false)

    override suspend fun retry(): AccountDeletionResult = recover(true)

    private suspend fun recover(allowReauthentication: Boolean): AccountDeletionResult =
        mutex.withLock {
            withContext(NonCancellable) {
                val progress = pending() ?: return@withContext AccountDeletionResult.Idle
                gate.closeAdmission()
                val result = resume(progress, null, allowReauthentication)
                if (result == AccountDeletionResult.Completed) gate.reopenAdmission()
                result
            }
        }

    private suspend fun resume(
        initial: AccountDeletionProgress,
        initialToken: String?,
        allowReauthentication: Boolean,
    ): AccountDeletionResult {
        var progress = initial
        fun retry() = AccountDeletionResult.RetryRequired(progress)
        return try {
            if (progress.serviceBinding != service.binding) return retry()
            while (progress.stage != AccountDeletionStage.COMPLETE) {
                val current = identity.currentAccount()
                if (current != null && current != progress.accountId) return retry()
                if (
                    progress.stage != AccountDeletionStage.LOCAL_CLEARED &&
                        !store.matchesProfile(progress)
                )
                    return retry()
                when (progress.stage) {
                    AccountDeletionStage.PREPARED -> {
                        var remote =
                            if (initialToken != null) {
                                service.start(progress.operationId, initialToken)
                            } else service.resume(progress.operationId)
                        if (remote == DeletionServiceResult.Missing && allowReauthentication) {
                            if (current != progress.accountId || !service.available())
                                return retry()
                            val auth = identity.reauthenticate(progress.accountId)
                            if (
                                auth !is DeletionReauthentication.Authenticated ||
                                    identity.currentAccount() != progress.accountId ||
                                    !store.matchesProfile(progress)
                            )
                                return retry()
                            remote = service.start(progress.operationId, auth.token)
                        }
                        if (remote != DeletionServiceResult.Complete) return retry()
                        if (!store.advance(progress, AccountDeletionStage.BACKUPS_PURGED))
                            return retry()
                        progress = progress.copy(stage = AccountDeletionStage.BACKUPS_PURGED)
                    }
                    AccountDeletionStage.BACKUPS_PURGED -> {
                        // COMPLETE is issued only after purge verification AND Firebase Auth
                        // deletion.
                        if (!store.advance(progress, AccountDeletionStage.ACCOUNT_TOMBSTONED))
                            return retry()
                        progress = progress.copy(stage = AccountDeletionStage.ACCOUNT_TOMBSTONED)
                    }
                    AccountDeletionStage.ACCOUNT_TOMBSTONED -> {
                        if (!store.clearLocalProfile(progress)) return retryWithCurrent(progress)
                        progress = progress.copy(stage = AccountDeletionStage.LOCAL_CLEARED)
                    }
                    AccountDeletionStage.LOCAL_CLEARED -> {
                        if (
                            !store.ensureInstallationMarker(progress) ||
                                !identity.clearDeletedSession(progress.accountId) ||
                                identity.currentAccount() != null ||
                                !store.markComplete(progress)
                        )
                            return retryWithCurrent(progress)
                        progress = progress.copy(stage = AccountDeletionStage.COMPLETE)
                    }
                    AccountDeletionStage.COMPLETE -> Unit
                }
            }
            AccountDeletionResult.Completed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            retryWithCurrent(progress)
        }
    }

    private suspend fun retryWithCurrent(
        fallback: AccountDeletionProgress
    ): AccountDeletionResult.RetryRequired =
        AccountDeletionResult.RetryRequired(
            store.journal()?.takeIf { it.operationId == fallback.operationId } ?: fallback
        )
}
