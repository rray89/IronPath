package com.example.ironpath.di

import com.example.ironpath.data.account.AuthPreviewFirebaseRuntime
import com.example.ironpath.data.account.FirebaseAccountSessionAdapter
import com.example.ironpath.data.account.GoogleCredentialActivityBroker
import com.example.ironpath.data.account.PersistedAccountGateway
import com.example.ironpath.data.backup.AuthPreviewUnavailableBackupCoordinator
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
        implementation: AuthPreviewUnavailableBackupCoordinator,
    ): BackupCoordinator

    companion object {
        @Provides
        @Singleton
        fun accountDeletionManager(): AccountDeletionManager = UnavailableAccountDeletionManager

        @Provides
        @Singleton
        fun accountExperienceCapabilities(
            runtime: AuthPreviewFirebaseRuntime,
        ): AccountExperienceCapabilities =
            AccountExperienceCapabilities.AuthPreview.copy(canSignIn = runtime.configured)
    }
}
