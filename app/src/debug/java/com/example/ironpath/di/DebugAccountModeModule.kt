package com.example.ironpath.di

import com.example.ironpath.domain.account.AccountCredentialActivityHost
import com.example.ironpath.domain.account.AccountExperienceCapabilities
import com.example.ironpath.domain.account.NoOpAccountCredentialActivityHost
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugAccountModeModule {
    @Binds
    @Singleton
    abstract fun bindCredentialActivityHost(
        implementation: NoOpAccountCredentialActivityHost,
    ): AccountCredentialActivityHost

    companion object {
        @Provides
        @Singleton
        fun accountExperienceCapabilities() = AccountExperienceCapabilities.Demo
    }
}
