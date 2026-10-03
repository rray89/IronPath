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

/** A blocking journal always names an acknowledged, durable v2 server identity. */
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
        store.journal()?.takeIf {
            it.stage !in setOf(AccountDeletionStage.COMPLETE, AccountDeletionStage.CANCELLED)
        }

    override suspend fun delete(request: AccountDeletionRequest): AccountDeletionResult =
        mutex.withLock {
            pending()?.let {
                return@withLock AccountDeletionResult.RetryRequired(it)
            }
            val epoch = gate.sessionEpoch
            if (
                (request.expectedLocalOwnerUid != null &&
                    request.expectedLocalOwnerUid != request.accountId.opaqueValue) ||
                    identity.currentAccount() != request.accountId ||
                    installation.validate() !in
                        setOf(
                            InstallationValidationResult.Validated,
                            InstallationValidationResult.Initialized
                        ) ||
                    !service.available() ||
                    service.binding == null
            )
                return@withLock AccountDeletionResult.Unavailable
            val draft =
                store.createDraft(request.copy(serviceBinding = service.binding))
                    ?: return@withLock AccountDeletionResult.Unavailable
            val authentication = identity.reauthenticate(request.accountId)
            when (authentication) {
                DeletionReauthentication.Cancelled -> {
                    store.discardDraft(draft)
                    return@withLock AccountDeletionResult.Cancelled
                }
                is DeletionReauthentication.Failed ->
                    return@withLock AccountDeletionResult.Failed(authentication.reason)
                is DeletionReauthentication.Authenticated -> Unit
            }
            if (identity.currentAccount() != request.accountId || gate.sessionEpoch != epoch)
                return@withLock AccountDeletionResult.Cancelled
            // No fence/job is created by reserve. Lost ACK/crash leaves only a nonblocking draft.
            val receipt =
                (service.reserve(draft, authentication.token) as? DeletionServiceResult.Receipt)
                    ?.value
                    ?: return@withLock AccountDeletionResult.Failed(
                        AccountFailureReason.ServiceUnavailable
                    )
            if (
                receipt.operationId != draft.operationId ||
                    receipt.subjectBinding.isBlank() ||
                    receipt.version <= 0
            )
                return@withLock AccountDeletionResult.Unavailable
            if (receipt.state == AccountDeletionRemoteState.CANCELLED_NO_DELETE) {
                store.discardDraft(draft)
                return@withLock AccountDeletionResult.Cancelled
            }
            if (
                identity.currentAccount() != request.accountId ||
                    gate.sessionEpoch != epoch ||
                    service.binding != draft.request.serviceBinding
            )
                return@withLock AccountDeletionResult.Cancelled
            withContext(NonCancellable) {
                val progress =
                    store.prepareReservation(draft, receipt)
                        ?: return@withContext AccountDeletionResult.Unavailable
                gate.closeAdmission()
                try {
                    if (receipt.state == AccountDeletionRemoteState.RESERVED) {
                        // This is the original explicit action, never a stored proof or automatic
                        // replay.
                        if (
                            identity.currentAccount() != request.accountId ||
                                gate.sessionEpoch != epoch ||
                                !store.matchesProfile(progress)
                        )
                            return@withContext AccountDeletionResult.RetryRequired(progress)
                        consume(progress, service.activate(progress, authentication.token))
                    } else consume(progress, DeletionServiceResult.Receipt(receipt))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    retryWithCurrent(progress)
                }
            }
        }

    override suspend fun recoverAtStartup() =
        recover(activateReserved = false, cancelReserved = false)

    override suspend fun retry() = recover(activateReserved = true, cancelReserved = false)

    override suspend fun cancelUnactivated() =
        recover(activateReserved = false, cancelReserved = true)

    private suspend fun recover(
        activateReserved: Boolean,
        cancelReserved: Boolean
    ): AccountDeletionResult =
        mutex.withLock {
            withContext(NonCancellable) {
                val stored = store.journal() ?: return@withContext AccountDeletionResult.Idle
                if (stored.stage == AccountDeletionStage.CANCELLED)
                    return@withContext AccountDeletionResult.Cancelled
                if (stored.stage == AccountDeletionStage.COMPLETE)
                    return@withContext AccountDeletionResult.Completed
                val progress = stored
                gate.closeAdmission()
                try {
                    // Existing v1/demonstration journals have no acknowledged v2 authority. No
                    // upgrade.
                    if (
                        !v2(progress) ||
                            !store.matchesProfile(progress) &&
                                progress.stage != AccountDeletionStage.LOCAL_CLEARED
                    )
                        return@withContext retryWithCurrent(progress)
                    if (progress.stage != AccountDeletionStage.PREPARED)
                        return@withContext finish(progress)
                    if (!service.available() || progress.serviceBinding != service.binding)
                        return@withContext retryWithCurrent(progress)
                    val status = service.status(progress)
                    val receipt =
                        (status as? DeletionServiceResult.Receipt)?.value
                            ?: return@withContext retryWithCurrent(progress)
                    var confirmed =
                        store.recordReceipt(progress, receipt)
                            ?: return@withContext retryWithCurrent(progress)
                    if (receipt.state != AccountDeletionRemoteState.RESERVED)
                        return@withContext consume(confirmed, status)
                    if (cancelReserved)
                        return@withContext consume(confirmed, service.cancel(confirmed))
                    if (activateReserved) {
                        if (identity.currentAccount() != confirmed.accountId)
                            return@withContext retryWithCurrent(confirmed)
                        val epoch = gate.sessionEpoch
                        val auth = identity.reauthenticate(confirmed.accountId)
                        if (
                            auth !is DeletionReauthentication.Authenticated ||
                                identity.currentAccount() != confirmed.accountId ||
                                gate.sessionEpoch != epoch ||
                                !store.matchesProfile(confirmed)
                        )
                            return@withContext retryWithCurrent(confirmed)
                        return@withContext consume(
                            confirmed,
                            service.activate(confirmed, auth.token)
                        )
                    }
                    AccountDeletionResult.RetryRequired(confirmed)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    retryWithCurrent(progress)
                }
            }
        }

    private fun v2(progress: AccountDeletionProgress) =
        !progress.serviceBinding.isNullOrBlank() &&
            !progress.receiptSecret.isNullOrBlank() &&
            !progress.subjectBinding.isNullOrBlank() &&
            !progress.installationId.isNullOrBlank() &&
            progress.receiptVersion > 0

    private suspend fun consume(
        initial: AccountDeletionProgress,
        result: DeletionServiceResult
    ): AccountDeletionResult {
        val receipt =
            (result as? DeletionServiceResult.Receipt)?.value ?: return retryWithCurrent(initial)
        val progress = store.recordReceipt(initial, receipt) ?: return retryWithCurrent(initial)
        return when (receipt.state) {
            AccountDeletionRemoteState.RESERVED,
            AccountDeletionRemoteState.PENDING -> AccountDeletionResult.RetryRequired(progress)
            AccountDeletionRemoteState.CANCELLED_NO_DELETE -> {
                if (store.cancelReservation(progress, receipt)) AccountDeletionResult.Cancelled
                else retryWithCurrent(progress)
            }
            AccountDeletionRemoteState.COMPLETE -> finish(progress)
        }
    }

    private suspend fun finish(initial: AccountDeletionProgress): AccountDeletionResult {
        var progress = initial
        return try {
            while (progress.stage != AccountDeletionStage.COMPLETE) {
                if (
                    progress.stage != AccountDeletionStage.LOCAL_CLEARED &&
                        !store.matchesProfile(progress)
                )
                    return retryWithCurrent(progress)
                when (progress.stage) {
                    AccountDeletionStage.PREPARED -> {
                        if (
                            progress.remoteState != AccountDeletionRemoteState.COMPLETE ||
                                !store.advance(progress, AccountDeletionStage.BACKUPS_PURGED)
                        )
                            return retryWithCurrent(progress)
                        progress = progress.copy(stage = AccountDeletionStage.BACKUPS_PURGED)
                    }
                    AccountDeletionStage.BACKUPS_PURGED -> {
                        if (!store.advance(progress, AccountDeletionStage.ACCOUNT_TOMBSTONED))
                            return retryWithCurrent(progress)
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
                                identity.currentAccount() == progress.accountId ||
                                !store.markComplete(progress)
                        )
                            return retryWithCurrent(progress)
                        progress = progress.copy(stage = AccountDeletionStage.COMPLETE)
                    }
                    AccountDeletionStage.CANCELLED -> return AccountDeletionResult.Cancelled
                    AccountDeletionStage.COMPLETE -> Unit
                }
            }
            // Gateway owns admission reopening after session/local context stabilization.
            AccountDeletionResult.Completed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            retryWithCurrent(progress)
        }
    }

    private suspend fun retryWithCurrent(fallback: AccountDeletionProgress) =
        AccountDeletionResult.RetryRequired(
            store.journal()?.takeIf { it.operationId == fallback.operationId } ?: fallback
        )
}
