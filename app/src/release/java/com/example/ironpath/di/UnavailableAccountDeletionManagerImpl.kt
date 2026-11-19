package com.example.ironpath.di

import com.example.ironpath.domain.account.AccountDeletionManager
import com.example.ironpath.domain.account.AccountDeletionProgress
import com.example.ironpath.domain.account.AccountDeletionRequest
import com.example.ironpath.domain.account.AccountDeletionResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnavailableAccountDeletionManagerImpl @Inject constructor() : AccountDeletionManager {
    override suspend fun recoverAtStartup() = AccountDeletionResult.Idle

    override suspend fun delete(request: AccountDeletionRequest) = AccountDeletionResult.Unavailable

    override suspend fun retry() = AccountDeletionResult.Unavailable

    override suspend fun pending(): AccountDeletionProgress? = null
}
