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
}

class UnreadableAccountSessionException : Exception()

sealed interface CredentialResult {
    data class Selected(val profile: AccountProfile) : CredentialResult

    data object Cancelled : CredentialResult

    data class Failed(val reason: AccountFailureReason) : CredentialResult
}
