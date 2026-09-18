package com.example.ironpath.di

import com.example.ironpath.data.account.DeterministicAccountSessionAdapter
import com.example.ironpath.domain.account.AccountSessionAdapter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugAccountSessionModule {
    @Binds
    @Singleton
    abstract fun bindSessionAdapter(
        implementation: DeterministicAccountSessionAdapter
    ): AccountSessionAdapter
}
