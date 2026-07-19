package com.example.ironpath.testutil

import com.example.ironpath.di.CoreBindingsModule
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.time.Instant
import java.time.ZoneId
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [CoreBindingsModule::class],
)
object TestCoreBindingsModule {
    @Provides
    @Singleton
    fun provideMutableTimeProvider(): MutableTimeProvider =
        MutableTimeProvider(
            initialInstant = Instant.parse("2026-07-13T17:00:00Z"),
            zoneId = ZoneId.of("America/Vancouver"),
        )

    @Provides
    @Singleton
    fun provideTimeProvider(provider: MutableTimeProvider): TimeProvider = provider

    @Provides
    @Singleton
    fun provideSequenceIdProvider(): SequenceIdProvider = SequenceIdProvider("e2e")

    @Provides @Singleton fun provideIdProvider(provider: SequenceIdProvider): IdProvider = provider
}
