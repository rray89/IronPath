package com.example.ironpath.domain.account

/** Credential selection has no durable side effects until the current request is accepted. */
interface AccountSessionAdapter {
    suspend fun requestGoogleCredential(): CredentialResult

    suspend fun readSession(): AccountProfile?

    suspend fun saveSession(profile: AccountProfile): Boolean

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
    data class Selected(val profile: AccountProfile) : CredentialResult

    data object Cancelled : CredentialResult

    data class Failed(val reason: AccountFailureReason) : CredentialResult
}
