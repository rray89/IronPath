package com.example.ironpath.domain.account

enum class AccountDeletionStage {
    PREPARED,
    BACKUPS_PURGED,
    ACCOUNT_TOMBSTONED,
    LOCAL_CLEARED,
    COMPLETE,
    CANCELLED,
}

data class AccountDeletionRequest(
    val accountId: AccountId,
    val sessionEpoch: Long,
    val profileGeneration: Long,
    val expectedLocalOwnerUid: String? = accountId.opaqueValue,
    val serviceBinding: String? = null,
)

enum class AccountDeletionRemoteState {
    RESERVED,
    PENDING,
    COMPLETE,
    CANCELLED_NO_DELETE
}

data class AccountDeletionProgress(
    val operationId: String,
    val accountId: AccountId,
    val sessionEpoch: Long,
    val profileGeneration: Long,
    val stage: AccountDeletionStage,
    val expectedLocalOwnerUid: String? = accountId.opaqueValue,
    val serviceBinding: String? = null,
    val receiptSecret: String? = null,
    val subjectBinding: String? = null,
    val receiptVersion: Long = 0,
    val remoteState: AccountDeletionRemoteState? = null,
    val installationId: String? = null,
) {
    override fun toString() =
        "AccountDeletionProgress(operationId=$operationId, stage=$stage, remoteState=$remoteState, receipt=<redacted>)"
}

sealed interface AccountDeletionResult {
    data object Idle : AccountDeletionResult

    data object Completed : AccountDeletionResult

    data class RetryRequired(val progress: AccountDeletionProgress) : AccountDeletionResult

    data object Cancelled : AccountDeletionResult

    data class Failed(val reason: AccountFailureReason) : AccountDeletionResult

    data object Unavailable : AccountDeletionResult
}

/** Build-selected durable deletion. Release uses an unavailable implementation. */
interface AccountDeletionManager {
    suspend fun recoverAtStartup(): AccountDeletionResult

    suspend fun delete(request: AccountDeletionRequest): AccountDeletionResult

    suspend fun retry(): AccountDeletionResult

    suspend fun pending(): AccountDeletionProgress?

    suspend fun cancelUnactivated(): AccountDeletionResult = AccountDeletionResult.Unavailable

    /** Retire exactly the observed terminal journal only after local/provider stabilization. */
    suspend fun acknowledgeTerminalRecovery(expected: AccountDeletionProgress?): Boolean = true
}

object UnavailableAccountDeletionManager : AccountDeletionManager {
    override suspend fun recoverAtStartup() = AccountDeletionResult.Idle

    override suspend fun delete(request: AccountDeletionRequest) = AccountDeletionResult.Unavailable

    override suspend fun retry() = AccountDeletionResult.Unavailable

    override suspend fun pending(): AccountDeletionProgress? = null
}
