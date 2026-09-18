package com.example.ironpath.di

import com.example.ironpath.data.backup.InstallationSentinel
import com.example.ironpath.data.backup.NoBackupInstallationSentinel
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class InstallationSentinelModule {
    @Binds
    @Singleton
    abstract fun bindSentinel(implementation: NoBackupInstallationSentinel): InstallationSentinel
}
