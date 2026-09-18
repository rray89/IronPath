package com.example.ironpath.data.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.domain.account.AccountActionResult
import com.example.ironpath.domain.account.AccountGateway
import com.example.ironpath.domain.account.AccountState
import com.example.ironpath.testutil.AccountFilesUnchangedRule
import com.example.ironpath.testutil.HiltTestDatabaseRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AccountBoundaryTest {
    @get:Rule(order = 0) val accountFilesRule = AccountFilesUnchangedRule()
    @get:Rule(order = 1) val databaseRule = HiltTestDatabaseRule()
    @get:Rule(order = 2) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var accountGateway: AccountGateway

    @Before fun inject() = hiltRule.inject()

    @Test
    fun deterministicSignIn_requiresDataChoiceWithoutClaimingTheLocalProfile() = runBlocking {
        assertEquals(AccountActionResult.Completed, accountGateway.startGoogleSignIn())
        assertTrue(accountGateway.state.value is AccountState.AwaitingDataChoice)
    }
}
