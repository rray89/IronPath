package com.example.ironpath.data.account

import com.example.ironpath.domain.account.AccountActionResult
import com.example.ironpath.domain.account.AccountState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalOnlyAccountGatewayTest {
    @Test
    fun everyAccountActionIsUnavailableAndLeavesTheAppLocalOnly() = runTest {
        val gateway = LocalOnlyAccountGateway()

        assertEquals(AccountState.LocalOnly, gateway.state.value)
        assertEquals(AccountActionResult.Unavailable, gateway.startGoogleSignIn())
        assertEquals(AccountActionResult.Unavailable, gateway.reauthenticate())
        assertEquals(AccountActionResult.Unavailable, gateway.signOut())
        assertEquals(AccountActionResult.Unavailable, gateway.deleteAccount())
        assertEquals(AccountState.LocalOnly, gateway.state.value)
    }
}
