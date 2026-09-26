package com.example.ironpath.data.account

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.backup.InstallationGuard
import com.example.ironpath.data.backup.InstallationValidationResult
import com.example.ironpath.data.backup.RoomBackupStore
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.entity.AccountBackupMetadata
import com.example.ironpath.domain.account.AccountActionResult
import com.example.ironpath.domain.account.AccountState
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.testutil.IsolatedNoBackupDirectory
import com.example.ironpath.testutil.TestData
import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountPersistenceTest {
    @get:Rule val isolatedFiles = IsolatedNoBackupDirectory()
    private val context: Context
        get() = isolatedFiles.context

    private lateinit var database: IronPathDatabase
    private val guard =
        object : InstallationGuard {
            override suspend fun validate() = InstallationValidationResult.Validated
        }

    @Before
    fun setUp() = runBlocking {
        clearSession()
        context.deleteDatabase(DATABASE_NAME)
        database = openDatabase()
        database
            .backupDao()
            .insertMetadataIfAbsent(
                AccountBackupMetadata(installationId = "test-installation", localChangeRevision = 4)
            )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun freshStoresAndControllers_reconstructChoiceUntilExplicitSignOut() = runBlocking {
        val before = database.backupDao().getMetadata()
        val first = gateway()
        assertEquals(AccountActionResult.Completed, first.startGoogleSignIn())
        assertEquals(
            DeterministicAccountSessionAdapter.PROFILE.id.opaqueValue,
            sessionFile().readText()
        )
        database.close()
        database = openDatabase()
        val recreated = gateway()
        recreated.refresh()
        assertTrue(recreated.state.value is AccountState.AwaitingDataChoice)
        assertEquals(before, database.backupDao().getMetadata())
        assertEquals(AccountActionResult.Unavailable, recreated.cancelDataChoice())
        assertEquals(
            DeterministicAccountSessionAdapter.PROFILE.id.opaqueValue,
            sessionFile().readText()
        )
        val awaiting = recreated.state.value as AccountState.AwaitingDataChoice
        assertEquals(
            AccountActionResult.Completed,
            recreated.signOut(
                com.example.ironpath.domain.account.SignOutRequest(
                    awaiting.accountId,
                    awaiting.sessionEpoch,
                    com.example.ironpath.domain.account.SignOutDataChoice.KeepData,
                )
            ),
        )
        assertEquals("", sessionFile().readText())
        val afterCancel = gateway()
        afterCancel.refresh()
        assertEquals(AccountState.LocalOnly, afterCancel.state.value)
        assertEquals(before, database.backupDao().getMetadata())
    }

    @Test
    fun roomReader_readsRootDataAndLineageWithoutMutatingAnyTable() = runBlocking {
        val reader = RoomAccountContextReader(database)
        val before = database.backupDao().getMetadata()
        assertTrue(reader.read().localDataIsEmpty)
        assertFalse(reader.read().activeWorkoutPresent)
        database.sessionDao().startNewSession(TestData.session(), emptyList())
        assertTrue(reader.read().localDataIsEmpty)
        assertTrue(reader.read().activeWorkoutPresent)
        database.historyDao().insertLog(TestData.log(id = "log-account-context"))
        val result = reader.read()
        assertFalse(result.localDataIsEmpty)
        assertEquals(4, result.conflict.localChangeRevision)
        assertEquals("test-installation", result.conflict.currentInstallationId)
        assertNull(result.ownerUid)
        assertEquals(before, database.backupDao().getMetadata())
        assertEquals(1, database.backupDao().getWorkoutLogs().size)
        assertEquals("session-a", database.sessionDao().getActiveSession()?.id)
    }

    @Test
    fun roomReader_emitsWhenActiveWorkoutChangesWithoutMetadataWrite() = runBlocking {
        val reader = RoomAccountContextReader(database)
        val metadataBefore = database.backupDao().getMetadata()
        val emissions = Channel<Unit>(Channel.UNLIMITED)
        val observer = launch { reader.changes.collect { emissions.send(it) } }
        withTimeout(5_000) { emissions.receive() }

        database.sessionDao().startNewSession(TestData.session(), emptyList())
        withTimeout(5_000) { emissions.receive() }
        observer.cancel()

        assertEquals(metadataBefore, database.backupDao().getMetadata())
        assertTrue(reader.read().activeWorkoutPresent)
    }

    @Test
    fun unknownStoredFixture_requiresExplicitRecoveryAndPreservesTrainingData() = runBlocking {
        val invalidFixture = "unsupported-fixture"
        sessionFile().writeText(invalidFixture)
        database.historyDao().insertLog(TestData.log(id = "retained-after-session-recovery"))
        val metadataBefore = database.backupDao().getMetadata()
        val gateway = gateway()
        gateway.refresh()
        val recoverable = gateway.state.value as AccountState.RecoverableError
        assertTrue(recoverable.canRecoverUnreadableSession)
        assertEquals(AccountActionResult.Unavailable, gateway.cancelDataChoice())
        assertTrue(gateway.state.value is AccountState.RecoverableError)
        assertEquals(invalidFixture, sessionFile().readText())

        assertEquals(AccountActionResult.Completed, gateway.recoverUnreadableSession())
        assertEquals(AccountState.LocalOnly, gateway.state.value)
        assertEquals("", sessionFile().readText())
        assertEquals(metadataBefore, database.backupDao().getMetadata())
        assertEquals(
            "retained-after-session-recovery",
            database.backupDao().getWorkoutLogs().single().id,
        )
    }

    @Test
    fun unreadableRecoveryRefusesToClearSessionThatBecameValidBeforeConfirmation() = runBlocking {
        sessionFile().writeText("unsupported-fixture")
        database.historyDao().insertLog(TestData.log(id = "retained-after-session-recovery"))
        val metadataBefore = database.backupDao().getMetadata()
        val gateway = gateway()
        gateway.refresh()
        assertTrue(
            (gateway.state.value as AccountState.RecoverableError).canRecoverUnreadableSession
        )

        val acceptedSessionId = DeterministicAccountSessionAdapter.PROFILE.id.opaqueValue
        sessionFile().writeText(acceptedSessionId)
        assertEquals(AccountActionResult.Unavailable, gateway.recoverUnreadableSession())
        assertTrue(
            (gateway.state.value as AccountState.RecoverableError).canRecoverUnreadableSession
        )
        assertEquals(acceptedSessionId, sessionFile().readText())

        assertEquals(AccountActionResult.Completed, gateway.refresh())
        val account = gateway.state.value as AccountState.AwaitingDataChoice
        assertEquals(DeterministicAccountSessionAdapter.PROFILE.id, account.accountId)
        assertEquals(metadataBefore, database.backupDao().getMetadata())
        assertEquals(
            "retained-after-session-recovery",
            database.backupDao().getWorkoutLogs().single().id,
        )
    }

    private val absentRemote =
        object : com.example.ironpath.data.backup.RemoteBackupStore {
            override suspend fun latest(accountId: com.example.ironpath.domain.account.AccountId) =
                com.example.ironpath.data.backup.RemoteBackupRead.Absent()

            override suspend fun publish(
                accountId: com.example.ironpath.domain.account.AccountId,
                expectedGeneration: Long,
                sourceInstallationId: String,
                snapshot: com.example.ironpath.data.backup.EncodedBackupSnapshot
            ): com.example.ironpath.data.backup.RemoteBackupPublish =
                error("Account persistence never publishes")
        }

    private fun gateway() =
        PersistedAccountGateway(
            DeterministicAccountSessionAdapter(context, absentRemote),
            RoomAccountContextReader(database),
            guard,
            RoomBackupStore(
                database,
                object : IdProvider {
                    private var next = 0

                    override fun newId() = "installation-${++next}"
                },
            ),
            AccountSessionOperationGate(),
        )

    private fun openDatabase() =
        Room.databaseBuilder(context, IronPathDatabase::class.java, DATABASE_NAME).build()

    private fun clearSession() = runBlocking {
        check(DeterministicAccountSessionAdapter(context, absentRemote).clearSession())
    }

    private fun sessionFile() =
        File(context.noBackupFilesDir, DeterministicAccountSessionAdapter.SESSION_FILE_NAME)

    @Test
    fun roomReader_recognizesEachIncludedRootAndPreservesOwnedMetadata() = runBlocking {
        val dao = database.backupDao()
        val owned =
            checkNotNull(dao.getMetadata())
                .copy(ownerUid = "owner", lastObservedRemoteGeneration = 5)
        dao.updateMetadata(owned)
        val reader = RoomAccountContextReader(database)
        dao.insertWeeklyPlans(listOf(TestData.plan()))
        assertFalse(reader.read().localDataIsEmpty)
        dao.deleteWeeklyPlans()
        dao.insertPersonalRecords(listOf(TestData.record()))
        assertFalse(reader.read().localDataIsEmpty)
        assertEquals("owner", reader.read().ownerUid)
        assertEquals(5, reader.read().conflict.lastObservedRemoteGeneration)
        assertEquals(owned, dao.getMetadata())
    }

    private companion object {
        const val DATABASE_NAME = "rra59-account-persistence-test.db"
    }
}
