package com.example.ironpath.data.backup

import com.example.ironpath.domain.backup.BackupActionResult
import com.example.ironpath.domain.backup.BackupLookupResult
import com.example.ironpath.domain.backup.BackupStatus
import com.example.ironpath.domain.backup.RestorePreviewResult
import com.example.ironpath.domain.backup.UndoPreviewResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalOnlyBackupCoordinatorTest {
    @Test
    fun remoteActionsAreUnavailableWithoutChangingLocalOnlyStatus() = runTest {
        var validationCount = 0
        val coordinator =
            LocalOnlyBackupCoordinator(
                installationGuard =
                    object : InstallationGuard {
                        override suspend fun validate(): InstallationValidationResult {
                            validationCount++
                            return InstallationValidationResult.Validated
                        }
                    }
            )

        assertEquals(BackupStatus.LocalOnly, coordinator.status.value)
        assertEquals(BackupActionResult.Unavailable, coordinator.backUpNow())
        assertEquals(BackupLookupResult.Unavailable, coordinator.latestCompleteBackup())
        assertEquals(RestorePreviewResult.Unavailable, coordinator.previewRestore())
        assertEquals(UndoPreviewResult.Unavailable, coordinator.previewUndo())
        assertEquals(BackupActionResult.Unavailable, coordinator.deleteAllRemoteData())
        assertEquals(BackupStatus.LocalOnly, coordinator.status.value)
        assertEquals(2, validationCount)
    }
}
