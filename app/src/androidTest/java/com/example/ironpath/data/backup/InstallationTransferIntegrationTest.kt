package com.example.ironpath.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.testutil.FileBackedRoomTestDatabaseRule
import com.example.ironpath.testutil.SequenceIdProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstallationTransferIntegrationTest {
    @get:Rule val databaseRule = FileBackedRoomTestDatabaseRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sentinelFile = context.noBackupFilesDir.resolve("ironpath-installation")

    @Before
    @After
    fun clearSentinel() {
        sentinelFile.deleteRecursively()
    }

    @Test
    fun copiedRoomWithoutSentinel_rotatesOwnershipBeforeTheFirstBackupAction() = runBlocking {
        val originalDatabase = databaseRule.open()
        val originalSentinel = NoBackupInstallationSentinel(context)
        val originalStore =
            RoomBackupStore(
                originalDatabase,
                SequenceIdProvider("original-installation"),
                originalSentinel,
            )
        assertEquals(InstallationValidationResult.Initialized, originalStore.validateInstallation())
        originalStore.markIncludedDataChanged()
        val original = checkNotNull(originalDatabase.backupDao().getMetadata())
        originalDatabase
            .backupDao()
            .updateMetadata(
                original.copy(
                    ownerUid = "owner-a",
                    lastCompleteLocalRevision = 1,
                    lastObservedRemoteBackupId = "backup-a",
                    lastObservedRemoteGeneration = 4,
                    lastObservedRemoteDigest = "digest-a",
                    lastObservedSourceInstallationId = "remote-installation",
                    lastObservedRemoteCompletedAt = 10,
                )
            )
        originalDatabase.close()

        sentinelFile.delete()
        val transferredDatabase = databaseRule.open()
        val transferredStore =
            RoomBackupStore(
                transferredDatabase,
                SequenceIdProvider("transferred-installation"),
                NoBackupInstallationSentinel(context),
            )
        val guard = RoomInstallationGuard(transferredStore)
        val coordinator = LocalOnlyBackupCoordinator(guard)

        assertEquals(InstallationValidationResult.Transferred, guard.validate())
        val transferred = checkNotNull(transferredDatabase.backupDao().getMetadata())
        assertNull(transferred.ownerUid)
        assertEquals("transferred-installation-1", transferred.installationId)
        assertEquals(0L, transferred.lastCompleteLocalRevision)
        assertNull(transferred.lastObservedRemoteBackupId)
        assertEquals(0L, transferred.lastObservedRemoteGeneration)
        assertNull(transferred.lastObservedRemoteDigest)
        assertNull(transferred.lastObservedSourceInstallationId)
        assertNull(transferred.lastObservedRemoteCompletedAt)
        assertEquals(
            com.example.ironpath.domain.backup.BackupActionResult.Unavailable,
            coordinator.backUpNow(),
        )
        assertEquals(InstallationValidationResult.Validated, guard.validate())
    }
}
