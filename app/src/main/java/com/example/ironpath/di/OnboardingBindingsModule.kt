package com.example.ironpath.di

import com.example.ironpath.data.onboarding.OnboardingRepository
import com.example.ironpath.data.onboarding.SharedPreferencesOnboardingRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OnboardingBindingsModule {
    @Binds
    @Singleton
    abstract fun bindOnboardingRepository(
        implementation: SharedPreferencesOnboardingRepository
    ): OnboardingRepository
}
