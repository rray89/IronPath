package com.example.ironpath.data.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.backup.InstallationSentinel
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.entity.AccountBackupMetadata
import com.example.ironpath.data.local.entity.BackupBaselineChunk
import com.example.ironpath.data.local.entity.RestoreUndoChunk
import com.example.ironpath.data.local.entity.RestoreUndoMetadata
import com.example.ironpath.domain.account.AccountDeletionProgress
import com.example.ironpath.domain.account.AccountDeletionRequest
import com.example.ironpath.domain.account.AccountDeletionStage
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.testutil.FileBackedRoomTestDatabaseRule
import com.example.ironpath.testutil.MutableTimeProvider
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
class RoomAccountDeletionStoreTest {
    @get:Rule val databaseRule = FileBackedRoomTestDatabaseRule()

    private val account = AccountId("firebase-account")
    private val time = MutableTimeProvider(Instant.ofEpochMilli(10_000), ZoneId.of("UTC"))
    private val ids = SequenceIds()
    private val sentinel = MemorySentinel()

    @Test
    fun serviceBindingSurvivesReopenAndDifferentTargetCannotResumeOrAdvance() = runBlocking {
        val firstDatabase = databaseRule.open()
        seedProfile(firstDatabase)
        val prepared = checkNotNull(store(firstDatabase).prepare(request()))
        firstDatabase.close()

        val reopenedDatabase = databaseRule.open()
        val reopened = store(reopenedDatabase)
        assertEquals(prepared, reopened.journal())
        assertEquals(prepared, reopened.prepare(request()))
        assertEquals(SERVICE_BINDING, prepared.serviceBinding)
        assertNull(reopened.prepare(request().copy(serviceBinding = OTHER_SERVICE_BINDING)))
        assertNull(reopened.prepare(request().copy(serviceBinding = null)))
        assertFalse(reopened.matchesProfile(prepared.copy(serviceBinding = OTHER_SERVICE_BINDING)))
        assertFalse(
            reopened.advance(
                prepared.copy(serviceBinding = OTHER_SERVICE_BINDING),
                AccountDeletionStage.BACKUPS_PURGED,
            )
        )
        assertEquals(prepared, reopened.journal())
        assertProfilePopulated(reopenedDatabase)
    }

    @Test
    fun foreignOwnerIsRejectedEvenWhenRequestMatchesCurrentMetadata() = runBlocking {
        val database = databaseRule.open()
        seedProfile(database, ownerUid = "another-account")
        val store = store(database)

        assertNull(store.prepare(request().copy(expectedLocalOwnerUid = "another-account")))
        assertNull(store.journal())
        assertProfilePopulated(database)
    }

    @Test
    fun profileCheckRevalidatesEveryIdentityFieldOwnerGenerationAndPendingSignOut() = runBlocking {
        val database = databaseRule.open()
        seedProfile(database)
        val store = store(database)
        val prepared = checkNotNull(store.prepare(request()))
        assertTrue(store.matchesProfile(prepared))
        val changedIdentities =
            listOf(
                prepared.copy(operationId = "another-operation"),
                prepared.copy(accountId = AccountId("another-account")),
                prepared.copy(sessionEpoch = prepared.sessionEpoch + 1),
                prepared.copy(profileGeneration = prepared.profileGeneration + 1),
                prepared.copy(expectedLocalOwnerUid = null),
                prepared.copy(serviceBinding = OTHER_SERVICE_BINDING),
            )
        changedIdentities.forEach { assertFalse(store.matchesProfile(it)) }
        val metadata = checkNotNull(database.backupDao().getMetadata())
        listOf(
                metadata.copy(ownerUid = "another-account"),
                metadata.copy(ownerUid = null),
                metadata.copy(profileGeneration = metadata.profileGeneration + 1),
                metadata.copy(pendingSignOutUid = account.opaqueValue),
            )
            .forEach { changed ->
                database.backupDao().updateMetadata(changed)
                assertFalse(store.matchesProfile(prepared))
            }
        database.backupDao().updateMetadata(metadata)
        assertTrue(store.matchesProfile(prepared))
        assertEquals(prepared, store.journal())
        assertProfilePopulated(database)
    }

    @Test
    fun changedProfileOrServiceCannotClearTombstonedOperation() = runBlocking {
        val database = databaseRule.open()
        seedProfile(database)
        val store = store(database)
        val tombstoned = tombstone(store)
        val metadata = checkNotNull(database.backupDao().getMetadata())
        assertFalse(
            store.clearLocalProfile(tombstoned.copy(serviceBinding = OTHER_SERVICE_BINDING))
        )
        listOf(
                metadata.copy(ownerUid = "another-account"),
                metadata.copy(profileGeneration = metadata.profileGeneration + 1),
                metadata.copy(pendingSignOutUid = account.opaqueValue),
            )
            .forEach { changed ->
                database.backupDao().updateMetadata(changed)
                assertFalse(store.clearLocalProfile(tombstoned))
                assertProfilePopulated(database)
                assertEquals(tombstoned, store.journal())
            }
        database.backupDao().updateMetadata(metadata)
        assertTrue(store.clearLocalProfile(tombstoned))
        assertProfileCleared(database)
    }

    @Test
    fun failedInstallationMarkerRecoversAfterReopenWithoutRepeatingLocalDeletion() = runBlocking {
        val firstDatabase = databaseRule.open()
        seedProfile(firstDatabase)
        val store = store(firstDatabase)
        val tombstoned = tombstone(store)
        val originalJournal = checkNotNull(firstDatabase.accountDeletionDao().getJournal())
        sentinel.writeSucceeds = false

        assertFalse(store.clearLocalProfile(tombstoned))
        assertProfileCleared(firstDatabase)
        val cleared = checkNotNull(store.journal())
        assertEquals(AccountDeletionStage.LOCAL_CLEARED, cleared.stage)
        assertEquals(SERVICE_BINDING, cleared.serviceBinding)
        firstDatabase.close()

        val reopenedDatabase = databaseRule.open()
        val reopened = store(reopenedDatabase)
        assertEquals(cleared, reopened.journal())
        assertFalse(reopened.clearLocalProfile(tombstoned))
        sentinel.writeSucceeds = true
        assertTrue(reopened.ensureInstallationMarker(cleared))
        assertEquals(
            reopenedDatabase.backupDao().getMetadata()?.installationId,
            sentinel.installedId
        )
        assertTrue(reopened.markComplete(cleared))
        assertEquals(
            originalJournal.copy(stage = AccountDeletionStage.COMPLETE.name),
            reopenedDatabase.accountDeletionDao().getJournal(),
        )
        assertProfileCleared(reopenedDatabase)
        assertEquals(
            PROFILE_GENERATION + 1,
            reopenedDatabase.backupDao().getMetadata()?.profileGeneration
        )
    }

    @Test
    fun clearedProfileCannotFinalizeForDifferentServiceOrChangedMetadata() = runBlocking {
        val database = databaseRule.open()
        seedProfile(database)
        val store = store(database)
        assertTrue(store.clearLocalProfile(tombstone(store)))
        val cleared = checkNotNull(store.journal())
        val metadata = checkNotNull(database.backupDao().getMetadata())
        val changedService = cleared.copy(serviceBinding = OTHER_SERVICE_BINDING)
        assertFalse(store.ensureInstallationMarker(changedService))
        assertFalse(store.markComplete(changedService))
        listOf(
                metadata.copy(ownerUid = "another-account"),
                metadata.copy(profileGeneration = metadata.profileGeneration + 1),
                metadata.copy(pendingSignOutUid = account.opaqueValue),
            )
            .forEach { changed ->
                database.backupDao().updateMetadata(changed)
                assertFalse(store.ensureInstallationMarker(cleared))
                assertFalse(store.markComplete(cleared))
                assertEquals(cleared, store.journal())
            }
        database.backupDao().updateMetadata(metadata)
        assertTrue(store.ensureInstallationMarker(cleared))
        assertTrue(store.markComplete(cleared))
    }

    @Test
    fun localTransactionFailurePreservesEntireGraphMetadataAndJournal() = runBlocking {
        val database = databaseRule.open()
        seedProfile(database)
        val store = store(database)
        val tombstoned = tombstone(store)
        val metadata = database.backupDao().getMetadata()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_deletion BEFORE UPDATE ON account_backup_metadata BEGIN SELECT RAISE(ABORT, 'test interruption'); END"
        )
        var failed = false
        try {
            store.clearLocalProfile(tombstoned)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(metadata, database.backupDao().getMetadata())
        assertEquals(tombstoned, store.journal())
        assertProfilePopulated(database)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_deletion")
        assertTrue(store.clearLocalProfile(tombstoned))
        assertProfileCleared(database)
    }

    @Test
    fun unclaimedLegacyDemoRequestRetainsNullBindingThroughCompletion() = runBlocking {
        val database = databaseRule.open()
        seedProfile(database, ownerUid = null)
        val store = store(database)
        val legacyRequest = request().copy(expectedLocalOwnerUid = null, serviceBinding = null)
        val tombstoned = tombstone(store, legacyRequest)

        assertTrue(store.matchesProfile(tombstoned))
        assertTrue(store.clearLocalProfile(tombstoned))
        val cleared = checkNotNull(store.journal())
        assertTrue(store.ensureInstallationMarker(cleared))
        assertTrue(store.markComplete(cleared))
        assertNull(store.journal()?.serviceBinding)
        assertNull(store.journal()?.expectedLocalOwnerUid)
        assertEquals(AccountDeletionStage.COMPLETE, store.journal()?.stage)
        assertProfileCleared(database)
    }

    private fun request() =
        AccountDeletionRequest(
            accountId = account,
            sessionEpoch = 7,
            profileGeneration = PROFILE_GENERATION,
            serviceBinding = SERVICE_BINDING,
        )

    private fun store(database: IronPathDatabase) =
        RoomAccountDeletionStore(database, ids, time, sentinel)

    private suspend fun tombstone(
        store: RoomAccountDeletionStore,
        request: AccountDeletionRequest = request(),
    ): AccountDeletionProgress {
        val prepared = checkNotNull(store.prepare(request))
        assertTrue(store.advance(prepared, AccountDeletionStage.BACKUPS_PURGED))
        val purged = checkNotNull(store.journal())
        assertTrue(store.advance(purged, AccountDeletionStage.ACCOUNT_TOMBSTONED))
        return checkNotNull(store.journal())
    }

    private suspend fun seedProfile(
        database: IronPathDatabase,
        ownerUid: String? = account.opaqueValue
    ) {
        val backup = database.backupDao()
        backup.insertMetadataIfAbsent(
            AccountBackupMetadata(
                ownerUid = ownerUid,
                installationId = "original-installation",
                localChangeRevision = 12,
                lastCompleteLocalRevision = 10,
                lastObservedRemoteBackupId = "backup",
                lastObservedRemoteGeneration = 3,
                lastObservedRemoteDigest = "a".repeat(64),
                lastObservedSourceInstallationId = "source",
                lastObservedRemoteCompletedAt = 100,
                requiresLineageReviewAfterUndo = true,
                profileGeneration = PROFILE_GENERATION,
            )
        )
        backup.insertWeeklyPlans(listOf(TestData.plan()))
        backup.insertPlannedWorkouts(listOf(TestData.workout()))
        backup.insertPlannedExercises(listOf(TestData.plannedExercise()))
        database
            .sessionDao()
            .startNewSession(TestData.session(), listOf(TestData.sessionExercise()))
        database.sessionDao().insertSet(TestData.sessionSet())
        backup.insertWorkoutLogs(listOf(TestData.log()))
        backup.insertLoggedExercises(listOf(TestData.loggedExercise()))
        backup.insertLoggedSets(listOf(TestData.loggedSet()))
        backup.insertPersonalRecords(listOf(TestData.record()))
        backup.insertBaselineChunks(
            listOf(
                BackupBaselineChunk(
                    chunkIndex = 0,
                    ownerUid = account.opaqueValue,
                    installationId = "original-installation",
                    backupId = "backup",
                    remoteGeneration = 3,
                    completedAt = 100,
                    sourceInstallationId = "source",
                    formatVersion = 1,
                    capturedRevision = 10,
                    entityCountsJson = "{}",
                    snapshotByteCount = 2,
                    snapshotDigest = "a".repeat(64),
                    payload = "{}",
                    chunkByteCount = 2,
                    chunkDigest = "a".repeat(64),
                )
            )
        )
        backup.insertRestoreUndoMetadata(
            RestoreUndoMetadata(
                slotIdentity = "undo-slot",
                restoringOwnerUid = account.opaqueValue,
                restoringInstallationId = "original-installation",
                previousOwnerUid = ownerUid,
                previousInstallationId = "original-installation",
                previousLocalChangeRevision = 2,
                previousLastCompleteLocalRevision = 1,
                previousLastObservedRemoteBackupId = "old-backup",
                previousLastObservedRemoteGeneration = 1,
                previousLastObservedRemoteDigest = "a".repeat(64),
                previousLastObservedSourceInstallationId = "old-source",
                previousLastObservedRemoteCompletedAt = 50,
                snapshotFormatVersion = 1,
                snapshotRevision = 2,
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
        backup.insertRestoreUndoChunks(
            listOf(RestoreUndoChunk(RestoreUndoChunk.LOCAL_SNAPSHOT, 0, "{}", 2, "b".repeat(64)))
        )
    }

    private fun assertProfilePopulated(database: IronPathDatabase) {
        profileTables.forEach { table -> assertEquals(table, 1, rowCount(database, table)) }
    }

    private suspend fun assertProfileCleared(database: IronPathDatabase) {
        profileTables.forEach { table -> assertEquals(table, 0, rowCount(database, table)) }
        val metadata = checkNotNull(database.backupDao().getMetadata())
        assertEquals(
            AccountBackupMetadata(
                installationId = metadata.installationId,
                profileGeneration = PROFILE_GENERATION + 1,
            ),
            metadata,
        )
        assertTrue(metadata.installationId != "original-installation")
        assertNotNull(database.accountDeletionDao().getJournal())
    }

    private fun rowCount(database: IronPathDatabase, table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private class SequenceIds : IdProvider {
        private var next = 0

        override fun newId() = "deletion-id-${++next}"
    }

    private class MemorySentinel : InstallationSentinel {
        var installedId: String? = "original-installation"
        var writeSucceeds = true

        override suspend fun readInstallationId() = installedId

        override suspend fun writeInstallationId(installationId: String): Boolean {
            if (!writeSucceeds) return false
            installedId = installationId
            return true
        }
    }

    private companion object {
        const val PROFILE_GENERATION = 9L
        val SERVICE_BINDING = "a".repeat(64)
        val OTHER_SERVICE_BINDING = "b".repeat(64)
        val profileTables =
            listOf(
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
                "restore_undo_chunks",
            )
    }
}
