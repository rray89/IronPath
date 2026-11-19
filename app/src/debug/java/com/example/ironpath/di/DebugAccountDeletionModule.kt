package com.example.ironpath.di

import com.example.ironpath.data.account.DeterministicAccountDeletionManager
import com.example.ironpath.domain.account.AccountDeletionManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugAccountDeletionModule {
    @Binds
    @Singleton
    abstract fun bindAccountDeletionManager(
        implementation: DeterministicAccountDeletionManager
    ): AccountDeletionManager
}
