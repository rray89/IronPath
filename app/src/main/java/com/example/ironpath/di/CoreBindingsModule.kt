package com.example.ironpath.di

import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.identity.UuidIdProvider
import com.example.ironpath.domain.time.SystemTimeProvider
import com.example.ironpath.domain.time.TimeProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreBindingsModule {
    @Binds
    @Singleton
    abstract fun bindTimeProvider(implementation: SystemTimeProvider): TimeProvider

    @Binds @Singleton abstract fun bindIdProvider(implementation: UuidIdProvider): IdProvider
}
