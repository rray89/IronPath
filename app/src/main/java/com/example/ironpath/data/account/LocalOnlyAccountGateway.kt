package com.example.ironpath.data.account

import com.example.ironpath.domain.account.AccountActionResult
import com.example.ironpath.domain.account.AccountGateway
import com.example.ironpath.domain.account.AccountState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class LocalOnlyAccountGateway @Inject constructor() : AccountGateway {
    override val state: StateFlow<AccountState> = MutableStateFlow(AccountState.LocalOnly)

    override suspend fun startGoogleSignIn(): AccountActionResult = AccountActionResult.Unavailable

    override suspend fun reauthenticate(): AccountActionResult = AccountActionResult.Unavailable

    override suspend fun signOut(): AccountActionResult = AccountActionResult.Unavailable

    override suspend fun deleteAccount(): AccountActionResult = AccountActionResult.Unavailable
}
