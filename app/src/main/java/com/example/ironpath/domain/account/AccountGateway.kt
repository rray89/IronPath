package com.example.ironpath.domain.account

import kotlinx.coroutines.flow.StateFlow

interface AccountGateway {
    val state: StateFlow<AccountState>

    suspend fun startGoogleSignIn(): AccountActionResult

    suspend fun reauthenticate(): AccountActionResult

    suspend fun signOut(): AccountActionResult

    suspend fun deleteAccount(): AccountActionResult
}

sealed interface AccountState {
    data object LocalOnly : AccountState

    data object SigningIn : AccountState

    data class AwaitingDataChoice(
        val accountId: AccountId,
        val context: DataChoiceContext,
    ) : AccountState

    data class SignedIn(val accountId: AccountId) : AccountState

    data object NeedsReauthentication : AccountState

    data object SigningOut : AccountState

    data object DeletingAccount : AccountState

    data class RecoverableError(val reason: AccountFailureReason) : AccountState
}

@JvmInline
value class AccountId(val opaqueValue: String) {
    init {
        require(opaqueValue.isNotBlank())
    }
}

data class DataChoiceContext(
    val ownership: LocalOwnership,
    val localDataIsEmpty: Boolean,
    val remoteSnapshot: RemoteSnapshotPresence,
    val conflict: PersistedConflictContext?,
)

sealed interface LocalOwnership {
    data object Unclaimed : LocalOwnership

    data class Account(val accountId: AccountId) : LocalOwnership
}

sealed interface RemoteSnapshotPresence {
    data object Absent : RemoteSnapshotPresence

    data class Complete(
        val backupId: String,
        val generation: Long,
        val sourceInstallationId: String,
    ) : RemoteSnapshotPresence
}

data class PersistedConflictContext(
    val lastObservedRemoteBackupId: String?,
    val lastObservedRemoteGeneration: Long,
    val lastObservedRemoteDigest: String?,
    val lastObservedSourceInstallationId: String?,
    val currentInstallationId: String,
    val localChangeRevision: Long,
    val lastCompleteLocalRevision: Long,
)

enum class AccountFailureReason {
    Offline,
    ServiceUnavailable,
    ReauthenticationRequired,
    Unknown,
}

sealed interface AccountActionResult {
    data object Completed : AccountActionResult

    data object Cancelled : AccountActionResult

    data object Unavailable : AccountActionResult

    data class Failed(val reason: AccountFailureReason) : AccountActionResult
}

object AccountStateResolver {
    fun resolve(
        authenticatedUid: String?,
        localOwnerUid: String?,
        localDataIsEmpty: Boolean = false,
        remoteSnapshot: RemoteSnapshotPresence = RemoteSnapshotPresence.Absent,
        conflict: PersistedConflictContext? = null,
    ): AccountState =
        when {
            authenticatedUid == null -> AccountState.LocalOnly
            authenticatedUid == localOwnerUid &&
                !requiresDataChoice(localDataIsEmpty, remoteSnapshot, conflict) ->
                AccountState.SignedIn(AccountId(authenticatedUid))
            else ->
                AccountState.AwaitingDataChoice(
                    accountId = AccountId(authenticatedUid),
                    context =
                        DataChoiceContext(
                            ownership =
                                localOwnerUid?.let { LocalOwnership.Account(AccountId(it)) }
                                    ?: LocalOwnership.Unclaimed,
                            localDataIsEmpty = localDataIsEmpty,
                            remoteSnapshot = remoteSnapshot,
                            conflict = conflict,
                        ),
                )
        }

    private fun requiresDataChoice(
        localDataIsEmpty: Boolean,
        remoteSnapshot: RemoteSnapshotPresence,
        conflict: PersistedConflictContext?,
    ): Boolean {
        if (remoteSnapshot !is RemoteSnapshotPresence.Complete) return false
        if (localDataIsEmpty || conflict == null) return true
        return remoteSnapshot.generation > conflict.lastObservedRemoteGeneration &&
            remoteSnapshot.sourceInstallationId != conflict.currentInstallationId
    }
}
