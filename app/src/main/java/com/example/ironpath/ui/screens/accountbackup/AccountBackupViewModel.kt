package com.example.ironpath.ui.screens.accountbackup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.domain.account.AccountActionResult
import com.example.ironpath.domain.account.AccountContextReader
import com.example.ironpath.domain.account.AccountGateway
import com.example.ironpath.domain.account.AccountState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@HiltViewModel
class AccountBackupViewModel
@Inject
constructor(
    private val accountGateway: AccountGateway,
    localContext: AccountContextReader,
) : ViewModel() {
    val state = accountGateway.state

    init {
        viewModelScope.launch {
            accountGateway.refresh()
            localContext.changes.catch { emit(Unit) }.collect { accountGateway.refresh() }
        }
    }

    fun signIn() {
        viewModelScope.launch { accountGateway.startGoogleSignIn() }
    }

    fun refresh() {
        viewModelScope.launch { accountGateway.refresh() }
    }

    fun leave(onLeave: () -> Unit) {
        viewModelScope.launch {
            val current = state.value
            val cancellationRequired =
                current == AccountState.SigningIn ||
                    current is AccountState.AwaitingDataChoice ||
                    (current is AccountState.RecoverableError && current.canCancelDataChoice)
            if (current == AccountState.CancellingDataChoice) return@launch
            if (
                !cancellationRequired ||
                    accountGateway.cancelDataChoice() == AccountActionResult.Completed
            )
                onLeave()
        }
    }
}
