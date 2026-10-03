package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.ironpath.domain.account.*
import com.example.ironpath.ui.theme.IronPathTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountDeletionUiTest {
    @get:Rule val composeRule = createComposeRule()
    private val profile =
        AccountProfile(AccountId("google-uid"), "Training Athlete", "athlete@example.invalid")
    private val target =
        AccountDeletionTarget(
            AccountDeletionRequest(profile.id, 7, 12),
            profile.displayName,
            profile.email
        )
    private val progress =
        AccountDeletionProgress("operation", profile.id, 7, 12, AccountDeletionStage.PREPARED)

    @Test
    fun realDeletionRequiresBothReviewsAndNamesIdentityAndCompleteScopeAtLargeFont() {
        val deletion = mutableStateOf(AccountDeletionUiState(target = target))
        var confirmations = 0
        setContent {
            AccountDeletionReviewDialog(
                deletion.value,
                ManualBackupActions(
                    continueAccountDeletion = {
                        deletion.value =
                            deletion.value.copy(target = target.copy(confirmingIdentity = true))
                    },
                    confirmAccountDeletion = { confirmations++ },
                ),
            )
        }
        composeRule.onNodeWithText("Delete account and data?").assertIsDisplayed()
        composeRule
            .onNodeWithText("Confirm you are signed in as Training Athlete.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(profile.email).performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText("all of its cloud backups", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("all current local training data", substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Restore undo and backup metadata", substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Your Google account is not affected.", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Cancelling Google verification before deletion starts",
                substring = true
            )
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Keep data on this device").assertDoesNotExist()
        composeRule.onNodeWithText("demo", substring = true, ignoreCase = true).assertDoesNotExist()
        composeRule.onNodeWithText("DELETE ACCOUNT AND ALL DATA").assertDoesNotExist()
        composeRule.onNodeWithText("CONTINUE").assertIsEnabled().performClick()
        assertEquals(0, confirmations)
        composeRule.onNodeWithText("Confirm account deletion").assertIsDisplayed()
        composeRule.onNodeWithText("DELETE ACCOUNT AND ALL DATA").assertIsDisplayed().performClick()
        assertEquals(1, confirmations)
    }

    @Test
    fun cancellingEitherReviewNeverInvokesDelete() {
        val deletion = mutableStateOf(AccountDeletionUiState(target = target))
        var confirmations = 0
        var cancellations = 0
        setContent {
            AccountDeletionReviewDialog(
                deletion.value,
                ManualBackupActions(
                    dismissDeleteReview = {
                        cancellations++
                        deletion.value = AccountDeletionUiState()
                    },
                    confirmAccountDeletion = { confirmations++ },
                ),
            )
        }
        composeRule.onNodeWithText("CANCEL").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Delete account and data?").assertDoesNotExist()
        composeRule.runOnIdle {
            deletion.value = AccountDeletionUiState(target = target.copy(confirmingIdentity = true))
        }
        composeRule.onNodeWithText("CANCEL").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Confirm account deletion").assertDoesNotExist()
        assertEquals(2, cancellations)
        assertEquals(0, confirmations)
    }

    @Test
    fun unclaimedScopeAndCancellationRemainReachableAtLargeFontLandscape() {
        var cancellations = 0
        setContent(DpSize(640.dp, 320.dp)) {
            AccountDeletionReviewDialog(
                AccountDeletionUiState(
                    target =
                        target.copy(request = target.request.copy(expectedLocalOwnerUid = null))
                ),
                ManualBackupActions(dismissDeleteReview = { cancellations++ }),
            )
        }
        composeRule
            .onNodeWithText("all unclaimed local training data", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("CANCEL").assertIsDisplayed().performClick()
        assertEquals(1, cancellations)
    }

    @Test
    fun unavailableCapabilityCannotOpenDeletionButEnabledCapabilityCan() {
        val state =
            mutableStateOf<AccountState>(
                AccountState.SignedIn(profile.id, profile, canDeleteAccount = false)
            )
        var reviews = 0
        setContent { AccountDeletionAction(state.value, ManualBackupUiState(), { reviews++ }) }
        composeRule
            .onNodeWithText("Account deletion is unavailable", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("DELETE ACCOUNT").assertDoesNotExist()
        composeRule.runOnIdle {
            state.value = AccountState.SignedIn(profile.id, profile, canDeleteAccount = true)
        }
        composeRule.onNodeWithText("DELETE ACCOUNT").assertIsEnabled().performClick()
        assertEquals(1, reviews)
    }

    @Test
    fun realPendingRecoveryExposesRetryAndBusySemanticsWithoutCancellation() {
        val manual =
            mutableStateOf(
                ManualBackupUiState(
                    accountDeletion =
                        AccountDeletionUiState(progress = progress, retryAvailable = true)
                )
            )
        var retries = 0
        setContent {
            AccountDeletionRecoveryScreen(
                AccountState.AccountDeletionPending(progress),
                manual.value,
                { retries++ }
            )
        }
        composeRule
            .onNodeWithText(ACCOUNT_DELETION_PENDING_MESSAGE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Account deletion pending"
                )
            )
            .assertExists()
        composeRule
            .onNodeWithText("RETRY DELETION")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, retries)
        composeRule.runOnIdle {
            manual.value =
                manual.value.copy(accountDeletion = manual.value.accountDeletion.copy(busy = true))
        }
        composeRule.onNodeWithText("RETRY DELETION").performScrollTo().assertIsNotEnabled()
        composeRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Account deletion in progress"
                )
            )
            .assertExists()
        assertUnrelatedActionsAbsent()
        composeRule.onNodeWithText("demo", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun accountScreenPrioritizesDeletionOverStaleSignOutReviewAndBackupActions() {
        setContent {
            AccountBackupScreen(
                AccountState.AccountDeletionPending(progress),
                {},
                {},
                {},
                {},
                manual =
                    ManualBackupUiState(
                        signOutReview = SignOutReviewUiState(SignOutTarget(profile.id, 7))
                    ),
            )
        }
        composeRule.onNodeWithText("RETRY DELETION").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("Keep data on this device").assertDoesNotExist()
        assertUnrelatedActionsAbsent()
    }

    private fun assertUnrelatedActionsAbsent() {
        listOf(
                "CANCEL",
                "SIGN OUT",
                "BACK UP NOW",
                "REVIEW MANUAL SYNC",
                "PREVIEW WHOLE-BACKUP RESTORE",
                "EXPLORE BACKUP PREVIEW"
            )
            .forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
    }

    private fun setContent(size: DpSize = DpSize(360.dp, 800.dp), content: @Composable () -> Unit) {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(size) then
                    DeviceConfigurationOverride.FontScale(2f)
            ) {
                IronPathTheme { Surface(content = content) }
            }
        }
    }
}
