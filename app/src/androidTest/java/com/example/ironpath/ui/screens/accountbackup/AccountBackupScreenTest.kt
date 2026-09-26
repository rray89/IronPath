package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.ironpath.domain.account.*
import com.example.ironpath.domain.backup.RemoteBackupSummary
import com.example.ironpath.ui.theme.IronPathTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccountBackupScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private var signIns = 0
    private var retries = 0
    private var cancellations = 0
    private var previews = 0
    private var restorePreviews = 0
    private var emptyAssociations = 0
    private var signOutConfirmations = mutableListOf<Boolean>()
    private var removeConfirmRequests = 0
    private var removeConfirmationDismissals = 0
    private var signOutDismissals = 0

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
    fun pendingOtherOwner_explainsBlockAndAllowsDecideLaterAtTwoHundredPercent() {
        setScreen(pending(LocalOwnership.Account(AccountId("other"))), DpSize(320.dp, 640.dp))
        composeRule.onNodeWithText("Data choice required").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "This device is linked to another account. Backup, sync, and restore are unavailable for this account."
            )
            .performScrollTo()
            .assertIsDisplayed()
        assertManualOperationsUnavailable()
        composeRule.onNodeWithText("DECIDE LATER").performScrollTo().performClick()
        assertEquals(1, cancellations)
        composeRule.onNodeWithText("CANCEL ACCOUNT SETUP").assertDoesNotExist()
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
    fun unreadableDemoSessionOffersExplicitRecoveryThatKeepsTrainingData() {
        var recoveries = 0
        setScreen(
            AccountState.RecoverableError(
                AccountFailureReason.LocalStateUnavailable,
                canRecoverUnreadableSession = true,
            ),
            actions = ManualBackupActions(recoverUnreadableSession = { recoveries++ }),
        )

        composeRule
            .onNodeWithText("The stored demo session is unreadable.", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("CLEAR INVALID DEMO SESSION").performScrollTo().performClick()
        assertEquals(1, recoveries)
        assertEquals(0, cancellations)
    }

    @Test
    fun signedIn_doesNotClaimThatDataIsBackedUp() {
        val profile = AccountProfile(AccountId("demo"), "Demo Athlete", "athlete@example.invalid")
        setScreen(AccountState.SignedIn(profile.id, profile))
        composeRule.onNodeWithText("Demo Athlete").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Signed in").assertExists()
        composeRule.onNodeWithText("Up to date").assertDoesNotExist()
        composeRule.onNodeWithText("CANCEL ACCOUNT SETUP").assertDoesNotExist()
        composeRule.onNodeWithText("BACK UP NOW").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("REVIEW MANUAL SYNC").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("PREVIEW WHOLE-BACKUP RESTORE").assertIsNotEnabled()
    }

    @Test
    fun signOutReview_defaultsToKeepingData_andCancelDoesNotSubmit() {
        val profile = AccountProfile(AccountId("demo"), "Demo Athlete", "athlete@example.invalid")
        val manual =
            ManualBackupUiState(
                signOutReview = SignOutReviewUiState(SignOutTarget(profile.id, sessionEpoch = 4))
            )
        setScreen(
            AccountState.SignedIn(profile.id, profile, sessionEpoch = 4),
            manual = manual,
            actions =
                ManualBackupActions(
                    confirmSignOut = { signOutConfirmations += it },
                    dismissSignOutReview = { signOutDismissals++ },
                ),
        )

        composeRule.onNodeWithText("Keep data on this device").assertIsSelected()
        composeRule.onNodeWithText("Remove data from this device").assertIsNotSelected()
        composeRule.onNodeWithText("CANCEL").performClick()
        assertTrue(signOutConfirmations.isEmpty())
        assertEquals(1, signOutDismissals)
    }

    @Test
    fun keepDataCanBeSubmittedWithoutRemovalConfirmation() {
        val profile = AccountProfile(AccountId("demo"), "Demo Athlete", "athlete@example.invalid")
        val manual =
            ManualBackupUiState(
                signOutReview = SignOutReviewUiState(SignOutTarget(profile.id, sessionEpoch = 4))
            )
        setScreen(
            AccountState.SignedIn(profile.id, profile, sessionEpoch = 4),
            manual = manual,
            actions = ManualBackupActions(confirmSignOut = { signOutConfirmations += it }),
        )

        composeRule.onNodeWithText("Keep data on this device").assertIsSelected()
        composeRule.onAllNodesWithText("SIGN OUT").onLast().performClick()
        assertEquals(listOf(false), signOutConfirmations)
    }

    @Test
    fun removeDataRequiresASeparateConfirmationStep() {
        val profile = AccountProfile(AccountId("demo"), "Demo Athlete", "athlete@example.invalid")
        val target = SignOutTarget(profile.id, sessionEpoch = 4)
        val manual =
            ManualBackupUiState(
                signOutReview =
                    SignOutReviewUiState(
                        target,
                        choice = SignOutDataChoice.RemoveData,
                    )
            )
        setScreen(
            AccountState.SignedIn(profile.id, profile, sessionEpoch = 4),
            manual = manual,
            actions =
                ManualBackupActions(
                    requestRemoveConfirmation = { removeConfirmRequests++ },
                    confirmSignOut = { signOutConfirmations += it },
                ),
        )

        composeRule
            .onNodeWithText("Your remote backup will not be deleted.", substring = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText("CONTINUE").performClick()
        assertEquals(1, removeConfirmRequests)
        assertTrue(signOutConfirmations.isEmpty())
    }

    @Test
    fun removalConfirmationNamesLocalScopeAndLeavesRemoteBackupAlone() {
        val profile = AccountProfile(AccountId("demo"), "Demo Athlete", "athlete@example.invalid")
        val manual =
            ManualBackupUiState(
                signOutReview =
                    SignOutReviewUiState(
                        SignOutTarget(profile.id, sessionEpoch = 4),
                        choice = SignOutDataChoice.RemoveData,
                        confirmingRemoval = true,
                    )
            )
        setScreen(
            AccountState.SignedIn(profile.id, profile, sessionEpoch = 4),
            manual = manual,
            actions =
                ManualBackupActions(
                    confirmSignOut = { signOutConfirmations += it },
                    dismissRemoveConfirmation = { removeConfirmationDismissals++ },
                ),
        )
        composeRule
            .onNodeWithText("Your remote backup will not be deleted.", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("BACK").assertIsDisplayed().performClick()
        assertEquals(1, removeConfirmationDismissals)
        composeRule.onNodeWithText("REMOVE DATA AND SIGN OUT").performClick()
        assertEquals(listOf(true), signOutConfirmations)
    }

    @Test
    fun unclaimedAccount_canRequestAnExplicitBackupPreview() {
        setScreen(pending(LocalOwnership.Unclaimed))
        composeRule.onNodeWithText("BACK UP NOW").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("PREVIEW WHOLE-BACKUP RESTORE").assertIsNotEnabled()
    }

    @Test
    fun emptyUnclaimedAccountWithCompleteBackup_offersRestoreKeepEmptyAndDecideLater() {
        val state =
            AccountState.AwaitingDataChoice(
                AccountId("demo"),
                DataChoiceContext(
                    LocalOwnership.Unclaimed,
                    localDataIsEmpty = true,
                    remoteSnapshot = RemoteSnapshotPresence.Complete("backup", 2, "other-device"),
                    conflict = PersistedConflictContext(null, 0, null, null, "installation", 1, 0),
                ),
            )
        val manual =
            ManualBackupUiState(
                latest = RemoteBackupSummary("backup", 100, "other-device", mapOf("Record" to 2))
            )
        setScreen(
            state,
            size = DpSize(320.dp, 640.dp),
            manual = manual,
            actions =
                ManualBackupActions(
                    previewRestore = { restorePreviews++ },
                    keepDeviceEmpty = { emptyAssociations++ },
                ),
        )

        composeRule
            .onNodeWithText("A complete backup is available.", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("RESTORE BACKUP").performScrollTo().performClick()
        composeRule.onNodeWithText("KEEP THIS DEVICE EMPTY").performScrollTo().performClick()
        composeRule.onNodeWithText("DECIDE LATER").performScrollTo().performClick()
        assertEquals(1, restorePreviews)
        assertEquals(1, emptyAssociations)
        assertEquals(1, cancellations)
        composeRule.onNodeWithText("BACK UP NOW").assertDoesNotExist()
        composeRule.onNodeWithText("REVIEW MANUAL SYNC").assertDoesNotExist()
    }

    @Test
    fun emptyUnclaimedAccountWithActiveWorkout_doesNotOfferKeepEmptyAssociation() {
        val state =
            AccountState.AwaitingDataChoice(
                AccountId("demo"),
                DataChoiceContext(
                    LocalOwnership.Unclaimed,
                    localDataIsEmpty = true,
                    remoteSnapshot = RemoteSnapshotPresence.Complete("backup", 2, "other-device"),
                    conflict = null,
                    activeWorkoutPresent = true,
                ),
            )
        setScreen(
            state,
            manual =
                ManualBackupUiState(
                    latest =
                        RemoteBackupSummary("backup", 100, "other-device", mapOf("Record" to 2))
                ),
        )
        composeRule.onNodeWithText("KEEP THIS DEVICE EMPTY").assertDoesNotExist()
        composeRule.onNodeWithText("PREVIEW WHOLE-BACKUP RESTORE").assertIsEnabled()
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

    private fun setScreen(
        state: AccountState,
        size: DpSize = DpSize(360.dp, 800.dp),
        manual: ManualBackupUiState = ManualBackupUiState(),
        actions: ManualBackupActions = ManualBackupActions(),
    ) {
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
                            { previews++ },
                            manual = manual,
                            manualActions = actions,
                        )
                    }
                }
            }
        }
    }
}
