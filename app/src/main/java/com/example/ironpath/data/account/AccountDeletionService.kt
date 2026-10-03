package com.example.ironpath.data.account

import com.example.ironpath.domain.account.*

/** The same Room transaction boundary serves demo and authoritative account deletion. */
interface AccountDeletionStore {
    suspend fun journal(): AccountDeletionProgress?

    suspend fun prepare(request: AccountDeletionRequest): AccountDeletionProgress?

    suspend fun matchesProfile(expected: AccountDeletionProgress): Boolean

    suspend fun advance(expected: AccountDeletionProgress, next: AccountDeletionStage): Boolean

    suspend fun clearLocalProfile(expected: AccountDeletionProgress): Boolean

    suspend fun ensureInstallationMarker(expected: AccountDeletionProgress): Boolean

    suspend fun markComplete(expected: AccountDeletionProgress): Boolean

    suspend fun acknowledgeTerminal(expected: AccountDeletionProgress): Boolean = false

    suspend fun createDraft(request: AccountDeletionRequest): AccountDeletionDraft? = null

    suspend fun discardDraft(draft: AccountDeletionDraft): Boolean = false

    suspend fun prepareReservation(
        draft: AccountDeletionDraft,
        receipt: DeletionServiceReceipt
    ): AccountDeletionProgress? = null

    suspend fun recordReceipt(
        expected: AccountDeletionProgress,
        receipt: DeletionServiceReceipt
    ): AccountDeletionProgress? = null

    suspend fun cancelReservation(
        expected: AccountDeletionProgress,
        receipt: DeletionServiceReceipt
    ): Boolean = false
}

/** Authpreview binds the real service; no release binding can submit a deletion. */
interface AccountDeletionService {
    /** Non-secret project + endpoint fingerprint persisted before submission. */
    val binding: String?
        get() = null

    suspend fun available(): Boolean

    suspend fun start(operationId: String, token: String): DeletionServiceResult

    suspend fun resume(operationId: String): DeletionServiceResult

    suspend fun reserve(draft: AccountDeletionDraft, token: String): DeletionServiceResult =
        DeletionServiceResult.Unavailable

    suspend fun status(progress: AccountDeletionProgress): DeletionServiceResult =
        DeletionServiceResult.Unavailable

    suspend fun activate(progress: AccountDeletionProgress, token: String): DeletionServiceResult =
        DeletionServiceResult.Unavailable

    suspend fun cancel(progress: AccountDeletionProgress): DeletionServiceResult =
        DeletionServiceResult.Unavailable
}

data class AccountDeletionDraft(
    val operationId: String,
    val receiptSecret: String,
    val request: AccountDeletionRequest,
    val installationId: String,
) {
    override fun toString() = "AccountDeletionDraft(operationId=$operationId, receipt=<redacted>)"
}

data class DeletionServiceReceipt(
    val operationId: String,
    val subjectBinding: String,
    val state: AccountDeletionRemoteState,
    val version: Long,
)

sealed interface DeletionServiceResult {
    data class Receipt(val value: DeletionServiceReceipt) : DeletionServiceResult

    data object Pending : DeletionServiceResult

    data object Complete : DeletionServiceResult

    data object Missing : DeletionServiceResult

    data object Unavailable : DeletionServiceResult
}

interface AccountDeletionIdentity {
    suspend fun currentAccount(): AccountId?

    suspend fun reauthenticate(account: AccountId): DeletionReauthentication

    suspend fun clearDeletedSession(account: AccountId): Boolean
}

sealed interface DeletionReauthentication {
    /** Short-lived, memory-only Firebase ID token. Never stored in the deletion journal. */
    class Authenticated(val token: String) : DeletionReauthentication {
        override fun toString() = "Authenticated(<redacted>)"
    }

    data object Cancelled : DeletionReauthentication

    data class Failed(val reason: AccountFailureReason) : DeletionReauthentication
}
