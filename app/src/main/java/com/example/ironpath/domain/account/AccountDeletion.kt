package com.example.ironpath.domain.account

enum class AccountDeletionStage {
    PREPARED,
    BACKUPS_PURGED,
    ACCOUNT_TOMBSTONED,
    LOCAL_CLEARED,
    COMPLETE,
}

data class AccountDeletionRequest(
    val accountId: AccountId,
    val sessionEpoch: Long,
    val profileGeneration: Long,
    val expectedLocalOwnerUid: String? = accountId.opaqueValue,
)

data class AccountDeletionProgress(
    val operationId: String,
    val accountId: AccountId,
    val sessionEpoch: Long,
    val profileGeneration: Long,
    val stage: AccountDeletionStage,
    val expectedLocalOwnerUid: String? = accountId.opaqueValue,
)

sealed interface AccountDeletionResult {
    data object Idle : AccountDeletionResult

    data object Completed : AccountDeletionResult

    data class RetryRequired(val progress: AccountDeletionProgress) : AccountDeletionResult

    data object Unavailable : AccountDeletionResult
}

/** Demo deletion orchestration. Release uses an unavailable implementation. */
interface AccountDeletionManager {
    suspend fun recoverAtStartup(): AccountDeletionResult

    suspend fun delete(request: AccountDeletionRequest): AccountDeletionResult

    suspend fun retry(): AccountDeletionResult

    suspend fun pending(): AccountDeletionProgress?
}

object UnavailableAccountDeletionManager : AccountDeletionManager {
    override suspend fun recoverAtStartup() = AccountDeletionResult.Idle

    override suspend fun delete(request: AccountDeletionRequest) = AccountDeletionResult.Unavailable

    override suspend fun retry() = AccountDeletionResult.Unavailable

    override suspend fun pending(): AccountDeletionProgress? = null
}
