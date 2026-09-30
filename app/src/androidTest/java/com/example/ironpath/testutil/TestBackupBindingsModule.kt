package com.example.ironpath.testutil

import com.example.ironpath.data.backup.BackupChangeTracker
import com.example.ironpath.data.backup.InstallationGuard
import com.example.ironpath.data.backup.LocalProfileResetter
import com.example.ironpath.data.backup.RoomBackupStore
import com.example.ironpath.data.backup.RoomInstallationGuard
import com.example.ironpath.di.BackupBindingsModule
import com.example.ironpath.domain.account.AccountContextReader
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [BackupBindingsModule::class],
)
abstract class TestBackupBindingsModule {
    @Binds
    @Singleton
    abstract fun bindAccountContextReader(
        implementation: TestAccountContextReader
    ): AccountContextReader

    @Binds
    @Singleton
    abstract fun bindInstallationGuard(implementation: RoomInstallationGuard): InstallationGuard

    @Binds
    @Singleton
    abstract fun bindLocalProfileResetter(implementation: RoomBackupStore): LocalProfileResetter

    @Binds
    @Singleton
    abstract fun bindBackupChangeTracker(implementation: RoomBackupStore): BackupChangeTracker
}
