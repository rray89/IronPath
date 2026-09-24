package com.example.ironpath.di

import com.example.ironpath.data.account.RoomAccountContextReader
import com.example.ironpath.data.backup.BackupChangeTracker
import com.example.ironpath.data.backup.InstallationGuard
import com.example.ironpath.data.backup.LocalProfileResetter
import com.example.ironpath.data.backup.RoomBackupStore
import com.example.ironpath.data.backup.RoomInstallationGuard
import com.example.ironpath.domain.account.AccountContextReader
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
    abstract fun bindAccountContextReader(
        implementation: RoomAccountContextReader
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
