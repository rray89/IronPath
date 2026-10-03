package com.example.ironpath.data.account

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.backup.BackupSnapshotCodec
import com.example.ironpath.data.backup.InstallationSentinel
import com.example.ironpath.data.backup.RoomBackupStore
import com.example.ironpath.data.local.AccountDeletionInProgressException
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.entity.AccountBackupMetadata
import com.example.ironpath.data.local.entity.AccountDeletionJournal
import com.example.ironpath.data.local.entity.BackupBaselineChunk
import com.example.ironpath.data.local.entity.RestoreUndoChunk
import com.example.ironpath.data.local.entity.RestoreUndoMetadata
import com.example.ironpath.data.local.requireWritesAllowed
import com.example.ironpath.domain.account.AccountDeletionRemoteState
import com.example.ironpath.domain.account.AccountDeletionRequest
import com.example.ironpath.domain.account.AccountDeletionStage
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.testutil.FileBackedRoomTestDatabaseRule
import com.example.ironpath.testutil.MutableTimeProvider
import com.example.ironpath.testutil.TestData
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAccountDeletionReservationTest {
    @get:Rule val databases = FileBackedRoomTestDatabaseRule()
    private val request =
        AccountDeletionRequest(
            AccountId("reserved-owner"),
            7,
            9,
            serviceBinding = "pinned-v2-service"
        )
    private val time = MutableTimeProvider(Instant.ofEpochMilli(10_000), ZoneId.of("UTC"))
    private val ids =
        object : IdProvider {
            private var sequence = 0

            override fun newId() = "local-${++sequence}"
        }
    private val sentinel =
        object : InstallationSentinel {
            private var value: String? = "installation"

            override suspend fun readInstallationId() = value

            override suspend fun writeInstallationId(installationId: String): Boolean {
                value = installationId
                return true
            }
        }

    @Test
    fun draftSurvivesCrashWithoutBlockingWritesOrChangingTrainingExport() = runBlocking {
        val first = databases.open()
        seed(first)
        val backup = RoomBackupStore(first, ids)
        val before = backup.export()
        val draft = checkNotNull(store(first).createDraft(request))
        assertEquals(4, UUID.fromString(draft.operationId).version())
        assertEquals(32, Base64.getUrlDecoder().decode(draft.receiptSecret).size)
        assertNull(store(first).journal())
        first.withTransaction { first.requireWritesAllowed() }
        assertEquals(before, backup.export())
        assertFalse(
            checkNotNull(first.accountDeletionDao().getDraft())
                .toString()
                .contains(draft.receiptSecret)
        )
        first.close()

        val reopened = databases.open()
        assertEquals(draft, store(reopened).createDraft(request))
        assertNull(store(reopened).journal())
        reopened.withTransaction { reopened.requireWritesAllowed() }
        assertEquals(before, RoomBackupStore(reopened, ids).export())
    }

    @Test
    fun acknowledgedDraftPromotesSameIdentityAtomicallyAfterCrash() = runBlocking {
        val first = databases.open()
        seed(first)
        val draft = checkNotNull(store(first).createDraft(request))
        val acknowledged = receipt(draft)
        first.close()

        val database = databases.open()
        val store = store(database)
        val before = snapshot(database)
        val prepared = checkNotNull(store.prepareReservation(draft, acknowledged))
        assertEquals(draft.operationId, prepared.operationId)
        assertEquals(draft.receiptSecret, prepared.receiptSecret)
        assertEquals(draft.installationId, prepared.installationId)
        assertEquals(acknowledged.subjectBinding, prepared.subjectBinding)
        assertEquals(AccountDeletionRemoteState.RESERVED, prepared.remoteState)
        assertEquals(1L, prepared.receiptVersion)
        assertEquals(AccountDeletionStage.PREPARED, prepared.stage)
        assertNull(database.accountDeletionDao().getDraft())
        assertEquals(before, snapshot(database))
        assertEquals(prepared, store.prepareReservation(draft, acknowledged))
        assertTrue(
            runCatching { database.withTransaction { database.requireWritesAllowed() } }
                .exceptionOrNull() is AccountDeletionInProgressException
        )
        val exported =
            BackupSnapshotCodec()
                .encode(RoomBackupStore(database, ids).export())
                .chunks
                .joinToString { it.payload }
        assertFalse(exported.contains(draft.receiptSecret))
        assertFalse(exported.contains(draft.operationId))
        assertFalse(exported.contains("receiptSecret"))
        assertFalse(
            checkNotNull(database.accountDeletionDao().getJournal())
                .toString()
                .contains(draft.receiptSecret)
        )
    }

    @Test
    fun fabricatedOrStaleDraftCannotInstallBarrierAndPromotionRollbackKeepsDraft() = runBlocking {
        val database = databases.open()
        seed(database)
        val store = store(database)
        val draft = checkNotNull(store.createDraft(request))
        val ack = receipt(draft)
        assertNull(store.prepareReservation(draft.copy(receiptSecret = "b".repeat(43)), ack))
        assertNull(
            store.prepareReservation(draft.copy(request = request.copy(sessionEpoch = 8)), ack)
        )
        assertNull(
            store.prepareReservation(draft.copy(operationId = UUID.randomUUID().toString()), ack)
        )
        assertNull(
            store.prepareReservation(draft, ack.copy(operationId = UUID.randomUUID().toString()))
        )
        assertNull(store.prepareReservation(draft, ack.copy(subjectBinding = "invalid")))
        val metadata = checkNotNull(database.backupDao().getMetadata())
        database.backupDao().updateMetadata(metadata.copy(installationId = "replacement"))
        assertNull(store.prepareReservation(draft, ack))
        database.backupDao().updateMetadata(metadata)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_promotion BEFORE DELETE ON account_deletion_draft BEGIN SELECT RAISE(ABORT, 'test interruption'); END"
        )
        assertTrue(runCatching { store.prepareReservation(draft, ack) }.isFailure)
        assertNull(store.journal())
        assertNotNull(database.accountDeletionDao().getDraft())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_promotion")
        assertNotNull(store.prepareReservation(draft, ack))
    }

    @Test
    fun terminalCancellationPreservesEveryProfileRowAndRejectsDelayedComplete() = runBlocking {
        val database = databases.open()
        seed(database)
        val store = store(database)
        val draft = checkNotNull(store.createDraft(request))
        val prepared = checkNotNull(store.prepareReservation(draft, receipt(draft)))
        val before = snapshot(database)
        val cancelledReceipt = receipt(draft, AccountDeletionRemoteState.CANCELLED_NO_DELETE, 2)
        assertTrue(store.cancelReservation(prepared, cancelledReceipt))
        val cancelled = checkNotNull(store.journal())
        assertEquals(AccountDeletionStage.CANCELLED, cancelled.stage)
        assertEquals(AccountDeletionRemoteState.CANCELLED_NO_DELETE, cancelled.remoteState)
        assertEquals(2L, cancelled.receiptVersion)
        assertEquals(before, snapshot(database))
        database.withTransaction { database.requireWritesAllowed(9) }
        val lateComplete = receipt(draft, AccountDeletionRemoteState.COMPLETE, 3)
        assertNull(store.recordReceipt(prepared, lateComplete))
        assertNull(store.recordReceipt(cancelled, lateComplete))
        assertFalse(store.advance(cancelled, AccountDeletionStage.BACKUPS_PURGED))
        assertFalse(store.clearLocalProfile(cancelled))
        assertEquals(cancelled, store.journal())
        assertEquals(before, snapshot(database))
        val nextDraft = checkNotNull(store.createDraft(request))
        assertNotEquals(draft.operationId, nextDraft.operationId)
        assertNotEquals(draft.receiptSecret, nextDraft.receiptSecret)
        assertNotNull(store.prepareReservation(nextDraft, receipt(nextDraft)))
    }

    @Test
    fun receiptCompareAndSetRefusesWrongSubjectStaleVersionRegressionAndPendingCancellation() =
        runBlocking {
            val database = databases.open()
            seed(database)
            val store = store(database)
            val draft = checkNotNull(store.createDraft(request))
            val prepared = checkNotNull(store.prepareReservation(draft, receipt(draft)))
            assertNull(
                store.recordReceipt(prepared, receipt(draft).copy(subjectBinding = "b".repeat(64)))
            )
            assertNull(
                store.recordReceipt(
                    prepared,
                    receipt(draft, AccountDeletionRemoteState.COMPLETE, 1)
                )
            )
            val pending =
                checkNotNull(
                    store.recordReceipt(
                        prepared,
                        receipt(draft, AccountDeletionRemoteState.PENDING, 2)
                    )
                )
            assertFalse(store.advance(pending, AccountDeletionStage.BACKUPS_PURGED))
            assertNull(
                store.recordReceipt(
                    prepared,
                    receipt(draft, AccountDeletionRemoteState.COMPLETE, 3)
                )
            )
            assertNull(
                store.recordReceipt(pending, receipt(draft, AccountDeletionRemoteState.RESERVED, 3))
            )
            assertNull(
                store.recordReceipt(pending, receipt(draft, AccountDeletionRemoteState.PENDING, 1))
            )
            assertFalse(
                store.cancelReservation(
                    pending,
                    receipt(draft, AccountDeletionRemoteState.CANCELLED_NO_DELETE, 3)
                )
            )
            assertEquals(pending, store.journal())
        }

    @Test
    fun completeCanClearOnlyCapturedInstallationAndProfile() = runBlocking {
        val database = databases.open()
        seed(database)
        val store = store(database)
        val draft = checkNotNull(store.createDraft(request))
        val prepared = checkNotNull(store.prepareReservation(draft, receipt(draft)))
        val complete =
            checkNotNull(
                store.recordReceipt(
                    prepared,
                    receipt(draft, AccountDeletionRemoteState.COMPLETE, 2)
                )
            )
        assertTrue(store.advance(complete, AccountDeletionStage.BACKUPS_PURGED))
        assertTrue(
            store.advance(checkNotNull(store.journal()), AccountDeletionStage.ACCOUNT_TOMBSTONED)
        )
        val tombstoned = checkNotNull(store.journal())
        val metadata = checkNotNull(database.backupDao().getMetadata())
        val before = snapshot(database)
        database.backupDao().updateMetadata(metadata.copy(installationId = "replacement"))
        assertFalse(store.matchesProfile(tombstoned))
        assertFalse(store.clearLocalProfile(tombstoned))
        database.backupDao().updateMetadata(metadata)
        assertEquals(before, snapshot(database))
        assertTrue(store.clearLocalProfile(tombstoned))
        profileTables
            .filterNot { it == "account_backup_metadata" }
            .forEach { table -> assertEquals(emptyList<List<String?>>(), rows(database, table)) }
        assertEquals(10L, database.backupDao().getMetadata()?.profileGeneration)
    }

    @Test
    fun legacyServiceJournalWithoutSecretCannotGainV2Authority() = runBlocking {
        val database = databases.open()
        seed(database)
        val store = store(database)
        database
            .accountDeletionDao()
            .save(
                AccountDeletionJournal(
                    operationId = UUID.randomUUID().toString(),
                    accountId = request.accountId.opaqueValue,
                    sessionEpoch = 7,
                    profileGeneration = 9,
                    stage = AccountDeletionStage.ACCOUNT_TOMBSTONED.name,
                    createdAtEpochMillis = 1,
                    serviceBinding = "legacy-service",
                )
            )
        val legacy = checkNotNull(store.journal())
        val before = snapshot(database)
        assertNull(store.createDraft(request))
        assertNull(store.prepare(request))
        assertFalse(store.matchesProfile(legacy))
        assertFalse(store.clearLocalProfile(legacy))
        assertNull(
            store.recordReceipt(
                legacy,
                DeletionServiceReceipt(
                    legacy.operationId,
                    "a".repeat(64),
                    AccountDeletionRemoteState.COMPLETE,
                    1
                )
            )
        )
        assertEquals(before, snapshot(database))
        assertNull(store.journal()?.receiptSecret)
    }

    @Test
    fun exactDraftRetirementPreservesDataAndAllowsFreshConfirmationAfterCancelledAck() =
        runBlocking {
            val database = databases.open()
            seed(database)
            val store = store(database)
            val draft = checkNotNull(store.createDraft(request))
            val before = snapshot(database)
            assertNull(
                store.prepareReservation(
                    draft,
                    receipt(draft, AccountDeletionRemoteState.CANCELLED_NO_DELETE, 2)
                )
            )
            assertFalse(store.discardDraft(draft.copy(receiptSecret = "b".repeat(43))))
            assertEquals(draft, store.createDraft(request))
            assertTrue(store.discardDraft(draft))
            assertNull(database.accountDeletionDao().getDraft())
            assertNull(store.journal())
            assertEquals(before, snapshot(database))
            assertNull(store.prepareReservation(draft, receipt(draft)))
            database.withTransaction { database.requireWritesAllowed(9) }
            val fresh = checkNotNull(store.createDraft(request))
            assertNotEquals(draft.operationId, fresh.operationId)
            assertNotEquals(draft.receiptSecret, fresh.receiptSecret)
            assertNotNull(store.prepareReservation(fresh, receipt(fresh)))
            assertFalse(store.discardDraft(fresh))
        }

    private fun store(database: IronPathDatabase) =
        RoomAccountDeletionStore(database, ids, time, sentinel)

    private fun receipt(
        draft: AccountDeletionDraft,
        state: AccountDeletionRemoteState = AccountDeletionRemoteState.RESERVED,
        version: Long = 1
    ) = DeletionServiceReceipt(draft.operationId, "a".repeat(64), state, version)

    private suspend fun seed(database: IronPathDatabase) {
        val dao = database.backupDao()
        dao.insertMetadataIfAbsent(
            AccountBackupMetadata(
                ownerUid = request.accountId.opaqueValue,
                installationId = "installation",
                profileGeneration = 9,
                localChangeRevision = 12
            )
        )
        dao.insertWeeklyPlans(listOf(TestData.plan()))
        dao.insertPlannedWorkouts(listOf(TestData.workout()))
        dao.insertPlannedExercises(listOf(TestData.plannedExercise()))
        database
            .sessionDao()
            .startNewSession(TestData.session(), listOf(TestData.sessionExercise()))
        database.sessionDao().insertSet(TestData.sessionSet())
        dao.insertWorkoutLogs(listOf(TestData.log()))
        dao.insertLoggedExercises(listOf(TestData.loggedExercise()))
        dao.insertLoggedSets(listOf(TestData.loggedSet()))
        dao.insertPersonalRecords(listOf(TestData.record()))
        dao.insertBaselineChunks(
            listOf(
                BackupBaselineChunk(
                    0,
                    request.accountId.opaqueValue,
                    "installation",
                    "backup",
                    1,
                    100,
                    "source",
                    1,
                    12,
                    "{}",
                    2,
                    "a".repeat(64),
                    "{}",
                    2,
                    "a".repeat(64)
                )
            )
        )
        dao.insertRestoreUndoMetadata(
            RestoreUndoMetadata(
                slotIdentity = "undo",
                restoringOwnerUid = request.accountId.opaqueValue,
                restoringInstallationId = "installation",
                previousOwnerUid = request.accountId.opaqueValue,
                previousInstallationId = "installation",
                previousLocalChangeRevision = 1,
                previousLastCompleteLocalRevision = 1,
                previousLastObservedRemoteBackupId = "old",
                previousLastObservedRemoteGeneration = 1,
                previousLastObservedRemoteDigest = "b".repeat(64),
                previousLastObservedSourceInstallationId = "source",
                previousLastObservedRemoteCompletedAt = 100,
                snapshotFormatVersion = 1,
                snapshotRevision = 1,
                snapshotEntityCountsJson = "{}",
                snapshotByteCount = 2,
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
        dao.insertRestoreUndoChunks(listOf(RestoreUndoChunk("local", 0, "{}", 2, "b".repeat(64))))
    }

    private fun snapshot(database: IronPathDatabase) =
        profileTables.associateWith { rows(database, it) }

    private fun rows(database: IronPathDatabase, table: String): List<List<String?>> =
        database.openHelper.readableDatabase.query("SELECT * FROM `$table`").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    (0 until cursor.columnCount).map {
                        if (cursor.isNull(it)) null else cursor.getString(it)
                    }
                )
            }
        }

    private val profileTables =
        listOf(
            "account_backup_metadata",
            "weekly_plans",
            "planned_workouts",
            "planned_exercises",
            "active_sessions",
            "session_exercises",
            "session_sets",
            "workout_logs",
            "logged_exercises",
            "logged_sets",
            "personal_records",
            "backup_baseline_chunks",
            "restore_undo_metadata",
            "restore_undo_chunks"
        )
}
