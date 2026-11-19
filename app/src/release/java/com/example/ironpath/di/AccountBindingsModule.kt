package com.example.ironpath.di

import com.example.ironpath.data.account.LocalOnlyAccountGateway
import com.example.ironpath.domain.account.AccountGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AccountBindingsModule {
    @Binds
    @Singleton
    abstract fun bindAccountGateway(implementation: LocalOnlyAccountGateway): AccountGateway
}
