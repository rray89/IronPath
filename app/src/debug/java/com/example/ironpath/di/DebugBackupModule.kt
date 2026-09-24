package com.example.ironpath.di

import android.content.Context
import com.example.ironpath.data.backup.*
import com.example.ironpath.domain.backup.BackupCoordinator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugBackupModule {
    @Binds
    @Singleton
    abstract fun bindRemote(implementation: DeterministicRemoteBackupStore): RemoteBackupStore

    @Binds
    @Singleton
    abstract fun bindCoordinator(implementation: ManualBackupCoordinator): BackupCoordinator

    @Binds
    @Singleton
    abstract fun bindLocalStore(implementation: RoomBackupStore): ManualBackupLocalStore
}

@Module
@InstallIn(SingletonComponent::class)
object DebugBackupDirectoryModule {
    @Provides
    @Singleton
    @DebugBackupDirectory
    fun directory(@ApplicationContext context: Context): File =
        File(context.noBackupFilesDir, "ironpath-debug-remote")
}
