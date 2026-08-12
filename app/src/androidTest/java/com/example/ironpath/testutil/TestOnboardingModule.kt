package com.example.ironpath.testutil

import com.example.ironpath.data.onboarding.OnboardingRepository
import com.example.ironpath.di.OnboardingBindingsModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [OnboardingBindingsModule::class],
)
object TestOnboardingModule {
    @Provides @Singleton fun provideFakeOnboardingRepository() = FakeOnboardingRepository()

    @Provides
    fun provideOnboardingRepository(repository: FakeOnboardingRepository): OnboardingRepository =
        repository
}
