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
    var result: CredentialResult =
        CredentialResult.Selected(
            AccountProfile(AccountId("test-athlete"), "Test Athlete", "test@example.invalid")
        )

    override suspend fun requestGoogleCredential() = result

    override suspend fun readSession() = session

    override suspend fun saveSession(profile: AccountProfile): Boolean {
        session = profile
        return true
    }

    override suspend fun clearSession(): Boolean {
        session = null
        return true
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
