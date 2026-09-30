package com.example.ironpath.data.backup

import com.example.ironpath.domain.backup.BackupActionResult
import com.example.ironpath.domain.backup.BackupCoordinator
import com.example.ironpath.domain.backup.BackupLookupResult
import com.example.ironpath.domain.backup.BackupStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Auth preview intentionally has no backup, restore, or remote deletion implementation. */
@Singleton
class AuthPreviewUnavailableBackupCoordinator @Inject constructor() : BackupCoordinator {
    override val status: StateFlow<BackupStatus> = MutableStateFlow(BackupStatus.LocalOnly)

    override suspend fun backUpNow(): BackupActionResult = BackupActionResult.Unavailable

    override suspend fun latestCompleteBackup(): BackupLookupResult = BackupLookupResult.Unavailable

    override suspend fun deleteAllRemoteData(): BackupActionResult = BackupActionResult.Unavailable
}
