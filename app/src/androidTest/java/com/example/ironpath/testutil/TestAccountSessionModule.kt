package com.example.ironpath.testutil

import com.example.ironpath.di.DebugAccountSessionModule
import com.example.ironpath.domain.account.*
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeAccountSessionAdapter
@Inject
constructor(private val remote: com.example.ironpath.data.backup.RemoteBackupStore) :
    AccountSessionAdapter {
    var session: AccountProfile? = null
    private val candidates = mutableMapOf<PendingGoogleCredential, AccountProfile>()
    private var selectedProfile =
        AccountProfile(AccountId("test-athlete"), "Test Athlete", "test@example.invalid")
    var result: CredentialResult = selected(selectedProfile)
        set(value) {
            field = value
            if (value is CredentialResult.Selected) {
                selectedProfile = candidates.remove(value.candidate) ?: selectedProfile
            }
        }

    private val deletedDemoAccounts = mutableSetOf<AccountId>()
    var failDemoAccountDeletion = false
    var failClearSession = false

    override suspend fun requestGoogleCredential(requestId: Long): CredentialResult =
        when (val configured = result) {
            is CredentialResult.Selected -> {
                candidates.remove(configured.candidate)
                selected(selectedProfile)
            }
            else -> configured
        }

    override suspend fun commitGoogleCredential(
        candidate: PendingGoogleCredential,
    ): CredentialCommitResult {
        val selected =
            candidates.remove(candidate)
                ?: return CredentialCommitResult.Failed(
                    AccountFailureReason.Unknown,
                )
        session = selected
        return CredentialCommitResult.Authenticated(selected)
    }

    override suspend fun discardGoogleCredential(candidate: PendingGoogleCredential) {
        candidates.remove(candidate)
    }

    override suspend fun readSession() = session

    override suspend fun clearSession(): Boolean {
        if (failClearSession) return false
        session = null
        return true
    }

    override suspend fun deleteDemoAccount(accountId: AccountId): Boolean {
        if (failDemoAccountDeletion) return false
        deletedDemoAccounts += accountId
        return accountId in deletedDemoAccounts
    }

    override suspend fun clearDeletedSession(accountId: AccountId): Boolean {
        if (accountId !in deletedDemoAccounts) return false
        if (session?.id == accountId) session = null
        return session?.id != accountId
    }

    override suspend fun remoteSnapshot(accountId: AccountId): RemoteSnapshotPresence =
        when (val result = remote.latest(accountId)) {
            is com.example.ironpath.data.backup.RemoteBackupRead.Absent ->
                RemoteSnapshotPresence.Absent
            is com.example.ironpath.data.backup.RemoteBackupRead.Complete ->
                RemoteSnapshotPresence.Complete(
                    result.backup.summary.backupId,
                    result.backup.generation,
                    result.backup.summary.sourceInstallationId,
                    result.backup.snapshot.contentDigest,
                )
            is com.example.ironpath.data.backup.RemoteBackupRead.Failed ->
                error("Isolated test remote unavailable")
        }

    private fun selected(profile: AccountProfile): CredentialResult.Selected {
        val candidate = PendingGoogleCredential()
        candidates[candidate] = profile
        return CredentialResult.Selected(candidate)
    }
}

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DebugAccountSessionModule::class]
)
abstract class TestAccountSessionModule {
    @Binds
    @Singleton
    abstract fun bindSessionAdapter(
        implementation: FakeAccountSessionAdapter
    ): AccountSessionAdapter
}
