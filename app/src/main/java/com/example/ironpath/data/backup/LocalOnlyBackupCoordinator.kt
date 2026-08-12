package com.example.ironpath.data.backup

import com.example.ironpath.domain.backup.BackupActionResult
import com.example.ironpath.domain.backup.BackupCoordinator
import com.example.ironpath.domain.backup.BackupLookupResult
import com.example.ironpath.domain.backup.BackupStatus
import com.example.ironpath.domain.backup.RestoreRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class LocalOnlyBackupCoordinator
@Inject
constructor(
    private val installationGuard: InstallationGuard,
) : BackupCoordinator {
    override val status: StateFlow<BackupStatus> = MutableStateFlow(BackupStatus.LocalOnly)

    override suspend fun backUpNow(): BackupActionResult {
        installationGuard.validate()
        return BackupActionResult.Unavailable
    }

    override suspend fun latestCompleteBackup(): BackupLookupResult = BackupLookupResult.Unavailable

    override suspend fun restore(request: RestoreRequest): BackupActionResult {
        installationGuard.validate()
        return BackupActionResult.Unavailable
    }

    override suspend fun deleteAllRemoteData(): BackupActionResult {
        installationGuard.validate()
        return BackupActionResult.Unavailable
    }
}
