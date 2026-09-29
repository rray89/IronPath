package com.example.ironpath.data.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.backup.InstallationSentinel
import com.example.ironpath.data.backup.RemoteAccountPurge
import com.example.ironpath.data.backup.RemoteBackupPublish
import com.example.ironpath.data.backup.RemoteBackupRead
import com.example.ironpath.data.backup.RemoteBackupStore
import com.example.ironpath.data.local.AccountDeletionInProgressException
import com.example.ironpath.data.local.entity.AccountBackupMetadata
import com.example.ironpath.data.local.entity.RestoreUndoMetadata
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.domain.account.AccountDeletionRequest
import com.example.ironpath.domain.account.AccountDeletionResult
import com.example.ironpath.domain.account.AccountDeletionStage
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.account.AccountProfile
import com.example.ironpath.domain.account.AccountSessionAdapter
import com.example.ironpath.domain.account.CredentialResult
import com.example.ironpath.domain.account.RemoteSnapshotPresence
import com.example.ironpath.domain.backup.BackupFailureReason
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.testutil.MutableTimeProvider
import com.example.ironpath.testutil.RoomTestDatabaseRule
import com.example.ironpath.testutil.TestData
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountDeletionRecoveryTest {
    @get:Rule val databaseRule = RoomTestDatabaseRule()

    private val account = AccountId("demo-incarnation-1")
    private val time = MutableTimeProvider(Instant.ofEpochMilli(10_000), ZoneId.of("UTC"))
    private val ids = SequenceIds()

    @Test
    fun remoteFailureRetainsSessionAndDataThenRecreatedManagerCompletesRecovery() = runBlocking {
        val database = databaseRule.database
        populateOwnedProfile(database)
        val sessions = FakeSessions(account)
        val remote = FakeRemote(RemoteAccountPurge.Unavailable, RemoteAccountPurge.Completed)
        val gate = AccountSessionOperationGate()
        val first = manager(database, remote, sessions, gate)

        val pending = first.delete(request()) as AccountDeletionResult.RetryRequired
        assertEquals(AccountDeletionStage.PREPARED, pending.progress.stage)
        assertFalse(gate.withManualOperation(waitForTurn = false, unavailable = false) { true })
        assertNotNull(sessions.readSession())
        assertEquals(0, sessions.tombstones)
        assertEquals(1, database.backupDao().getWorkoutLogs().size)
        assertNotNull(database.recordDao().getRecordById("delete-record"))
        assertEquals(account.opaqueValue, database.backupDao().getMetadata()?.ownerUid)
        var blockedWrite: Exception? = null
        try {
            PlanRepository(
                    database.planDao(),
                    database,
                    com.example.ironpath.data.backup.RoomBackupStore(database, ids),
                )
                .createPlan(
                    TestData.plan(id = "blocked-plan"),
                    listOf(TestData.workout(id = "blocked-workout", planId = "blocked-plan")),
                    emptyList(),
                    expectedProfileGeneration = 0,
                )
        } catch (failure: Exception) {
            blockedWrite = failure
        }
        assertTrue(blockedWrite is AccountDeletionInProgressException)
        assertNull(database.planDao().getActivePlan())

        val recreated = manager(database, remote, sessions, gate)
        assertEquals(AccountDeletionResult.Completed, recreated.recoverAtStartup())
        assertTrue(gate.withManualOperation(waitForTurn = false, unavailable = false) { true })
        assertEquals(2, remote.purgeCalls)
        assertEquals(1, sessions.tombstones)
        assertNull(sessions.readSession())
        assertTrue(database.backupDao().getWorkoutLogs().isEmpty())
        assertNull(database.recordDao().getRecordById("delete-record"))
        assertNull(database.backupDao().getMetadata()?.ownerUid)
        assertEquals(1L, database.backupDao().getMetadata()?.profileGeneration)
        assertNull(database.backupDao().getRestoreUndoMetadata())
        assertNull(database.sessionDao().getActiveSession())
        assertEquals(
            AccountDeletionStage.COMPLETE.name,
            database.accountDeletionDao().getJournal()?.stage
        )
        assertEquals("installation-${ids.next}", sentinel.installedId)
    }

    @Test
    fun localTransactionFailureKeepsTombstoneStageAndRecreationOnlyRetriesLocalWork() =
        runBlocking {
            val database = databaseRule.database
            populateOwnedProfile(database)
            val sessions = FakeSessions(account)
            val remote = FakeRemote(RemoteAccountPurge.Completed)
            val gate = AccountSessionOperationGate()
            val first = manager(database, remote, sessions, gate)
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_deletion BEFORE DELETE ON personal_records " +
                    "BEGIN SELECT RAISE(ABORT, 'injected local cleanup failure'); END"
            )

            val pending = first.delete(request()) as AccountDeletionResult.RetryRequired
            assertEquals(AccountDeletionStage.ACCOUNT_TOMBSTONED, pending.progress.stage)
            assertEquals(1, remote.purgeCalls)
            assertEquals(1, sessions.tombstones)
            assertNotNull(sessions.readSession())
            assertEquals(1, database.backupDao().getWorkoutLogs().size)
            assertNotNull(database.recordDao().getRecordById("delete-record"))

            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_deletion")
            val recreated = manager(database, remote, sessions, gate)
            assertEquals(AccountDeletionResult.Completed, recreated.recoverAtStartup())
            assertEquals(1, remote.purgeCalls)
            assertEquals(1, sessions.tombstones)
            assertNull(sessions.readSession())
            assertTrue(database.backupDao().getWorkoutLogs().isEmpty())
            assertNull(database.recordDao().getRecordById("delete-record"))
            assertEquals(
                AccountDeletionStage.COMPLETE.name,
                database.accountDeletionDao().getJournal()?.stage
            )
        }

    private suspend fun populateOwnedProfile(
        database: com.example.ironpath.data.local.IronPathDatabase
    ) {
        database
            .backupDao()
            .insertMetadataIfAbsent(
                AccountBackupMetadata(
                    installationId = "installation-old",
                    ownerUid = account.opaqueValue,
                    localChangeRevision = 3,
                    profileGeneration = 0,
                )
            )
        database.backupDao().insertWorkoutLogs(listOf(TestData.log(id = "delete-log")))
        database.backupDao().insertPersonalRecords(listOf(TestData.record(id = "delete-record")))
        database
            .backupDao()
            .insertRestoreUndoMetadata(
                RestoreUndoMetadata(
                    slotIdentity = "undo-slot",
                    restoringOwnerUid = account.opaqueValue,
                    restoringInstallationId = "installation-old",
                    previousOwnerUid = account.opaqueValue,
                    previousInstallationId = "installation-old",
                    previousLocalChangeRevision = 2,
                    previousLastCompleteLocalRevision = 1,
                    previousLastObservedRemoteBackupId = "backup-old",
                    previousLastObservedRemoteGeneration = 1,
                    previousLastObservedRemoteDigest = "a".repeat(64),
                    previousLastObservedSourceInstallationId = "source-old",
                    previousLastObservedRemoteCompletedAt = 100,
                    snapshotFormatVersion = 1,
                    snapshotRevision = 2,
                    snapshotEntityCountsJson = "{}",
                    snapshotByteCount = 0,
                    snapshotDigest = "b".repeat(64),
                    baselineBackupId = null,
                    baselineGeneration = null,
                    baselineCompletedAt = null,
                    baselineSourceInstallationId = null,
                    baselineFormatVersion = null,
                    baselineRevision = null,
                    baselineEntityCountsJson = null,
                    baselineByteCount = null,
                    baselineDigest = null,
                )
            )
        database.sessionDao().startNewSession(TestData.session(), emptyList())
    }

    private fun request() = AccountDeletionRequest(account, sessionEpoch = 7, profileGeneration = 0)

    private fun manager(
        database: com.example.ironpath.data.local.IronPathDatabase,
        remote: FakeRemote,
        sessions: FakeSessions,
        gate: AccountSessionOperationGate,
    ) =
        DeterministicAccountDeletionManager(
            RoomAccountDeletionStore(database, ids, time, sentinel),
            remote,
            sessions,
            gate,
        )

    private val sentinel =
        object : InstallationSentinel {
            override suspend fun readInstallationId() = installedId

            override suspend fun writeInstallationId(installationId: String): Boolean {
                installedId = installationId
                return true
            }

            var installedId = "installation-old"
        }

    private class SequenceIds : IdProvider {
        var next = 0

        override fun newId(): String = "installation-${++next}"
    }

    private class FakeRemote(vararg outcomes: RemoteAccountPurge) : RemoteBackupStore {
        private val outcomes = ArrayDeque(outcomes.toList())
        var purgeCalls = 0

        override suspend fun latest(accountId: AccountId) = RemoteBackupRead.Absent()

        override suspend fun purgeAccount(accountId: AccountId): RemoteAccountPurge {
            purgeCalls++
            return if (outcomes.isEmpty()) RemoteAccountPurge.Completed else outcomes.removeFirst()
        }

        override suspend fun publish(
            accountId: AccountId,
            expectedGeneration: Long,
            sourceInstallationId: String,
            snapshot: com.example.ironpath.data.backup.EncodedBackupSnapshot,
        ): RemoteBackupPublish = RemoteBackupPublish.Failed(BackupFailureReason.ServiceUnavailable)
    }

    private class FakeSessions(private val account: AccountId) : AccountSessionAdapter {
        private val profile = AccountProfile(account, "Demo Athlete", "athlete@example.invalid")
        var session: AccountProfile? = profile
        var tombstones = 0

        override suspend fun requestGoogleCredential() = CredentialResult.Selected(profile)

        override suspend fun readSession() = session

        override suspend fun saveSession(profile: AccountProfile) = true

        override suspend fun clearSession(): Boolean {
            session = null
            return true
        }

        override suspend fun clearDeletedSession(accountId: AccountId): Boolean {
            if (accountId != account) return false
            session = null
            return true
        }

        override suspend fun remoteSnapshot(accountId: AccountId) = RemoteSnapshotPresence.Absent

        override suspend fun deleteDemoAccount(accountId: AccountId): Boolean {
            if (accountId != account) return false
            tombstones++
            return true
        }
    }
}
