package com.example.ironpath.domain.account

/** Credential selection has no durable side effects until the current request is accepted. */
interface AccountSessionAdapter {
    suspend fun requestGoogleCredential(): CredentialResult

    suspend fun readSession(): AccountProfile?

    suspend fun saveSession(profile: AccountProfile): Boolean

    suspend fun clearSession(): Boolean

    suspend fun remoteSnapshot(accountId: AccountId): RemoteSnapshotPresence
}

sealed interface CredentialResult {
    data class Selected(val profile: AccountProfile) : CredentialResult

    data object Cancelled : CredentialResult

    data class Failed(val reason: AccountFailureReason) : CredentialResult
}
