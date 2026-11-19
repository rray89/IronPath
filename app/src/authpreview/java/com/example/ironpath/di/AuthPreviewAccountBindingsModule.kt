package com.example.ironpath.di

import android.content.Context
import com.example.ironpath.data.account.AuthPreviewFirebaseRuntime
import com.example.ironpath.data.account.FirebaseAccountSessionAdapter
import com.example.ironpath.data.account.GoogleCredentialActivityBroker
import com.example.ironpath.data.account.PersistedAccountGateway
import com.example.ironpath.data.backup.*
import com.example.ironpath.domain.account.AccountCredentialActivityHost
import com.example.ironpath.domain.account.AccountDeletionManager
import com.example.ironpath.domain.account.AccountExperienceCapabilities
import com.example.ironpath.domain.account.AccountGateway
import com.example.ironpath.domain.account.AccountSessionAdapter
import com.example.ironpath.domain.account.UnavailableAccountDeletionManager
import com.example.ironpath.domain.backup.BackupCoordinator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthPreviewAccountBindingsModule {
    @Binds
    @Singleton
    abstract fun bindAccountGateway(implementation: PersistedAccountGateway): AccountGateway

    @Binds
    @Singleton
    abstract fun bindSessionAdapter(
        implementation: FirebaseAccountSessionAdapter
    ): AccountSessionAdapter

    @Binds
    @Singleton
    abstract fun bindCredentialActivityHost(
        implementation: GoogleCredentialActivityBroker,
    ): AccountCredentialActivityHost

    @Binds
    @Singleton
    abstract fun bindBackupCoordinator(
        implementation: CloudManualBackupCoordinator,
    ): BackupCoordinator

    @Binds
    @Singleton
    abstract fun bindRemoteBackupStore(
        implementation: FirestoreManualBackupStore
    ): RemoteBackupStore

    @Binds
    @Singleton
    abstract fun bindFirestoreClientFactory(
        implementation: AuthPreviewFirestoreClientFactory
    ): FirestoreBackupClientFactory

    @Binds
    @Singleton
    abstract fun bindManualBackupLocalStore(implementation: RoomBackupStore): ManualBackupLocalStore

    companion object {
        @Provides
        @AuthPreviewAppVersion
        fun appVersion(@ApplicationContext context: Context): String =
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"

        @Provides
        @Singleton
        fun accountDeletionManager(): AccountDeletionManager = UnavailableAccountDeletionManager

        @Provides
        @Singleton
        fun accountExperienceCapabilities(
            runtime: AuthPreviewFirebaseRuntime,
        ): AccountExperienceCapabilities =
            AccountExperienceCapabilities.AuthPreview.copy(
                canSignIn = runtime.configured,
                canUseBackup = runtime.configured,
                canAssociateLocalData = runtime.configured,
            )
    }
}
