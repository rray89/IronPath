package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.ironpath.domain.account.*
import com.example.ironpath.ui.theme.IronPathTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountBackupScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private var signIns = 0
    private var retries = 0
    private var cancellations = 0
    private var previews = 0

    @Test
    fun localOnly_explainsDemoAndKeepsManualOperationsUnavailable() {
        setScreen(AccountState.LocalOnly)
        composeRule.onNodeWithText("DEMO ACCOUNT").assertIsDisplayed()
        composeRule.onNodeWithText("SIGN IN WITH GOOGLE").performScrollTo().performClick()
        assertEquals(1, signIns)
        assertManualOperationsUnavailable()
        composeRule.onNodeWithText("EXPLORE BACKUP PREVIEW").performScrollTo().performClick()
        assertEquals(1, previews)
        composeRule.onNodeWithText("SIGN OUT").assertDoesNotExist()
        composeRule.onNodeWithText("DELETE CLOUD ACCOUNT AND BACKUP").assertDoesNotExist()
    }

    @Test
    fun pendingOtherOwner_explainsBlockAndAllowsCancelAtTwoHundredPercent() {
        setScreen(pending(LocalOwnership.Account(AccountId("other"))), DpSize(320.dp, 640.dp))
        composeRule.onNodeWithText("Data choice required").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "This device is linked to another account. Backup, sync, and restore are unavailable for this account."
            )
            .performScrollTo()
            .assertIsDisplayed()
        assertManualOperationsUnavailable()
        composeRule.onNodeWithText("CANCEL ACCOUNT SETUP").performScrollTo().performClick()
        assertEquals(1, cancellations)
    }

    @Test
    fun cancellationFailure_keepsRetryAndCancelReachableInLargeFontLandscape() {
        setScreen(
            AccountState.RecoverableError(AccountFailureReason.LocalStateUnavailable, true),
            DpSize(640.dp, 320.dp)
        )
        composeRule.onNodeWithText("TRY AGAIN").performScrollTo().performClick()
        composeRule.onNodeWithText("CANCEL ACCOUNT SETUP").performScrollTo().performClick()
        assertEquals(1, retries)
        assertEquals(1, cancellations)
    }

    @Test
    fun signedIn_doesNotClaimThatDataIsBackedUp() {
        val profile = AccountProfile(AccountId("demo"), "Demo Athlete", "athlete@example.invalid")
        setScreen(AccountState.SignedIn(profile.id, profile))
        composeRule.onNodeWithText("Demo Athlete").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Signed in — manual operations unavailable").assertExists()
        composeRule.onNodeWithText("Up to date").assertDoesNotExist()
        composeRule.onNodeWithText("CANCEL ACCOUNT SETUP").assertDoesNotExist()
        assertManualOperationsUnavailable()
    }

    @Test
    fun signingIn_allowsCancellationWithoutASecondSignIn() {
        setScreen(AccountState.SigningIn)
        composeRule.onNodeWithText("SIGN IN WITH GOOGLE").assertDoesNotExist()
        composeRule
            .onNodeWithText("CANCEL ACCOUNT SETUP")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, cancellations)
        assertEquals(0, signIns)
    }

    private fun assertManualOperationsUnavailable() {
        listOf("BACK UP NOW", "REVIEW MANUAL SYNC", "PREVIEW WHOLE-BACKUP RESTORE").forEach {
            composeRule.onNodeWithText(it).performScrollTo().assertIsNotEnabled()
        }
    }

    private fun pending(owner: LocalOwnership) =
        AccountState.AwaitingDataChoice(
            AccountId("demo"),
            DataChoiceContext(owner, true, RemoteSnapshotPresence.Absent, null)
        )

    private fun setScreen(state: AccountState, size: DpSize = DpSize(360.dp, 800.dp)) {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(size) then
                    DeviceConfigurationOverride.FontScale(2f)
            ) {
                IronPathTheme {
                    Surface {
                        AccountBackupScreen(
                            state,
                            { signIns++ },
                            { retries++ },
                            { cancellations++ },
                            { previews++ }
                        )
                    }
                }
            }
        }
    }
}
