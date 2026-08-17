package com.example.ironpath.di

import com.example.ironpath.data.account.LocalOnlyAccountGateway
import com.example.ironpath.data.backup.BackupChangeTracker
import com.example.ironpath.data.backup.InstallationGuard
import com.example.ironpath.data.backup.InstallationSentinel
import com.example.ironpath.data.backup.LocalOnlyBackupCoordinator
import com.example.ironpath.data.backup.NoBackupInstallationSentinel
import com.example.ironpath.data.backup.RoomBackupStore
import com.example.ironpath.data.backup.RoomInstallationGuard
import com.example.ironpath.domain.account.AccountGateway
import com.example.ironpath.domain.backup.BackupCoordinator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupBindingsModule {
    @Binds
    @Singleton
    abstract fun bindAccountGateway(implementation: LocalOnlyAccountGateway): AccountGateway

    @Binds
    @Singleton
    abstract fun bindBackupCoordinator(
        implementation: LocalOnlyBackupCoordinator
    ): BackupCoordinator

    @Binds
    @Singleton
    abstract fun bindInstallationSentinel(
        implementation: NoBackupInstallationSentinel
    ): InstallationSentinel

    @Binds
    @Singleton
    abstract fun bindInstallationGuard(implementation: RoomInstallationGuard): InstallationGuard

    @Binds
    @Singleton
    abstract fun bindBackupChangeTracker(implementation: RoomBackupStore): BackupChangeTracker
}
