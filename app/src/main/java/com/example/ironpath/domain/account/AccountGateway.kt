package com.example.ironpath.domain.account

import kotlinx.coroutines.flow.StateFlow

interface AccountGateway {
    val state: StateFlow<AccountState>

    suspend fun refresh(): AccountActionResult

    /** Reconstruct local identity/lineage without inspecting remote storage. */
    suspend fun refreshLocal(): AccountActionResult = refresh()

    suspend fun cancelDataChoice(): AccountActionResult

    suspend fun recoverUnreadableSession(): AccountActionResult = AccountActionResult.Unavailable

    suspend fun startGoogleSignIn(): AccountActionResult

    suspend fun reauthenticate(): AccountActionResult

    suspend fun signOut(request: SignOutRequest): AccountActionResult

    suspend fun deleteAccount(): AccountActionResult
}

sealed interface AccountState {
    data object Loading : AccountState

    data object LocalOnly : AccountState

    data object SigningIn : AccountState

    /** A credential was selected and the durable demo session is being saved. */
    data object SavingSignIn : AccountState

    data object CancellingDataChoice : AccountState

    data class AwaitingDataChoice(
        val accountId: AccountId,
        val context: DataChoiceContext,
        val profile: AccountProfile? = null,
        val sessionEpoch: Long = 0,
    ) : AccountState

    data class SignedIn(
        val accountId: AccountId,
        val profile: AccountProfile? = null,
        val sessionEpoch: Long = 0,
    ) : AccountState

    /** Local removal committed; only clearing this same account's session may be retried. */
    data class SignOutPending(
        val accountId: AccountId,
        val profile: AccountProfile? = null,
        val sessionEpoch: Long = 0,
    ) : AccountState

    data object NeedsReauthentication : AccountState

    data object SigningOut : AccountState

    data object DeletingAccount : AccountState

    data class RecoverableError(
        val reason: AccountFailureReason,
        val canCancelDataChoice: Boolean = false,
        val canRecoverUnreadableSession: Boolean = false,
    ) : AccountState
}

data class AccountProfile(val id: AccountId, val displayName: String, val email: String)

enum class SignOutDataChoice {
    KeepData,
    RemoveData,
}

data class SignOutRequest(
    val accountId: AccountId,
    val sessionEpoch: Long,
    val choice: SignOutDataChoice,
    val removeDataConfirmed: Boolean = false,
)

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
    val activeWorkoutPresent: Boolean = false,
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
        val contentDigest: String? = null,
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
    LocalStateUnavailable,
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
        activeWorkoutPresent: Boolean = false,
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
                            activeWorkoutPresent = activeWorkoutPresent,
                        ),
                )
        }

    private fun requiresDataChoice(
        localDataIsEmpty: Boolean,
        remoteSnapshot: RemoteSnapshotPresence,
        conflict: PersistedConflictContext?,
    ): Boolean {
        if (remoteSnapshot !is RemoteSnapshotPresence.Complete) return false
        if (conflict == null) return true
        if (localDataIsEmpty) {
            return remoteSnapshot.backupId != conflict.lastObservedRemoteBackupId ||
                remoteSnapshot.generation != conflict.lastObservedRemoteGeneration ||
                conflict.localChangeRevision != conflict.lastCompleteLocalRevision
        }
        return remoteSnapshot.generation > conflict.lastObservedRemoteGeneration &&
            remoteSnapshot.sourceInstallationId != conflict.currentInstallationId
    }
}
