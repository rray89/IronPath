package com.example.ironpath.di

import com.example.ironpath.data.backup.LocalOnlyBackupCoordinator
import com.example.ironpath.domain.backup.BackupCoordinator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ReleaseBackupModule {
    @Binds
    @Singleton
    abstract fun bindCoordinator(implementation: LocalOnlyBackupCoordinator): BackupCoordinator
}
