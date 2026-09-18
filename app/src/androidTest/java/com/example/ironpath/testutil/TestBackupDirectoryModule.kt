package com.example.ironpath.testutil

import android.content.Context
import com.example.ironpath.data.backup.DebugBackupDirectory
import com.example.ironpath.di.DebugBackupDirectoryModule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.io.File
import java.nio.file.Files
import javax.inject.Singleton

/** Each test component gets a fresh remote store, never the installed app's demo backups. */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DebugBackupDirectoryModule::class]
)
object TestBackupDirectoryModule {
    @Provides
    @Singleton
    @DebugBackupDirectory
    fun directory(@ApplicationContext context: Context): File =
        Files.createTempDirectory(context.cacheDir.toPath(), "manual-backup-test-").toFile()
}
