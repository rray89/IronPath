package com.example.ironpath.data.backup

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomInstallationGuard
@Inject
constructor(
    private val backupStore: RoomBackupStore,
) : InstallationGuard {
    override suspend fun validate(): InstallationValidationResult =
        backupStore.validateInstallation()
}
