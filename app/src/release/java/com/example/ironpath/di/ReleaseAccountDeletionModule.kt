package com.example.ironpath.di

import com.example.ironpath.domain.account.AccountDeletionManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ReleaseAccountDeletionModule {
    @Binds
    @Singleton
    abstract fun bindAccountDeletionManager(
        implementation: UnavailableAccountDeletionManagerImpl
    ): AccountDeletionManager
}
