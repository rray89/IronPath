package com.example.ironpath.domain.account

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Credential selection has no durable side effects until the current request is accepted. */
interface AccountSessionAdapter {
    /** Emits only when the provider reports a persisted account-session change. */
    val sessionChanges: Flow<Unit>
        get() = emptyFlow()

    suspend fun requestGoogleCredential(requestId: Long): CredentialResult

    /** Exchange a selected in-memory candidate for a persisted session after gateway acceptance. */
    suspend fun commitGoogleCredential(candidate: PendingGoogleCredential): CredentialCommitResult

    /** Drop a selected candidate when its gateway request is stale or cancelled. */
    suspend fun discardGoogleCredential(candidate: PendingGoogleCredential) {}

    /** Cancel only the matching provider chooser request. */
    fun cancelGoogleCredentialRequest(requestId: Long) {}

    suspend fun readSession(): AccountProfile?

    suspend fun clearSession(): Boolean

    /**
     * Explicitly clear only an unreadable local demo-session record, if the adapter can verify it.
     */
    suspend fun clearUnreadableSession(): Boolean = false

    suspend fun remoteSnapshot(accountId: AccountId): RemoteSnapshotPresence

    /** Debug adapters can tombstone one IronPath account incarnation without removing identity. */
    suspend fun deleteDemoAccount(accountId: AccountId): Boolean = false

    /** Clear only the matching session after its demo account incarnation was tombstoned. */
    suspend fun clearDeletedSession(accountId: AccountId): Boolean {
        val current = readSession() ?: return true
        return current.id == accountId && clearSession()
    }
}

class UnreadableAccountSessionException : Exception()

sealed interface CredentialResult {
    data class Selected(val candidate: PendingGoogleCredential) : CredentialResult

    data object Cancelled : CredentialResult

    data class Failed(val reason: AccountFailureReason) : CredentialResult
}

/** Opaque, single-use, process-memory credential selection. It contains no credential material. */
class PendingGoogleCredential internal constructor()

sealed interface CredentialCommitResult {
    data class Authenticated(val profile: AccountProfile) : CredentialCommitResult

    data class Failed(val reason: AccountFailureReason) : CredentialCommitResult
}
