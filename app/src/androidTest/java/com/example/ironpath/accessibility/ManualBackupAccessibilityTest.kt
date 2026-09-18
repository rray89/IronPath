package com.example.ironpath.accessibility

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.account.AccountProfile
import com.example.ironpath.domain.account.AccountState
import com.example.ironpath.domain.backup.BackupPreview
import com.example.ironpath.domain.backup.BackupStatus
import com.example.ironpath.domain.backup.RemoteBackupSummary
import com.example.ironpath.domain.backup.SyncConflictResolution
import com.example.ironpath.domain.backup.SyncPreview
import com.example.ironpath.ui.screens.accountbackup.AccountBackupScreen
import com.example.ironpath.ui.screens.accountbackup.ManualBackupActions
import com.example.ironpath.ui.screens.accountbackup.ManualBackupUiState
import com.example.ironpath.ui.screens.accountbackup.ManualReview
import com.example.ironpath.ui.theme.IronPathTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Platform checks use isolated screen state and never create an app account or backup store. */
@SdkSuppress(minSdkVersion = 34)
@RunWith(AndroidJUnit4::class)
class ManualBackupAccessibilityTest {
    @get:Rule val composeRule = createComposeRule()

    private var ui by mutableStateOf(ManualBackupUiState())
    private var confirmations = 0

    @Before
    fun enablePlatformChecks() {
        composeRule.enableAccessibilityChecks()
    }

    @Test
    fun overview_completeFeedbackAndBusyActions_passPlatformChecksAfterScrolling() {
        setScreen(
            ManualBackupUiState(
                status = BackupStatus.UpToDate(COMPLETED_AT),
                latest = completeBackup(),
                feedback = "Manual backup complete in demo storage.",
            )
        )
        checkCurrentSurface()
        showAndCheck("DEMO BACKUP ON THIS DEVICE")
        showAndCheck("LAST COMPLETE DEMO BACKUP")
        showAndCheck("Manual backup complete in demo storage.")
        composeRule
            .onNodeWithText("Manual backup complete in demo storage.")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
            )
        showAndCheck("BACK UP NOW")
        composeRule.onNodeWithText("BACK UP NOW").assertIsEnabled()
        showAndCheck("REVIEW MANUAL SYNC")
        composeRule.onNodeWithText("REVIEW MANUAL SYNC").assertIsEnabled()
        showAndCheck("PREVIEW WHOLE-BACKUP RESTORE")
        composeRule.onNodeWithText("PREVIEW WHOLE-BACKUP RESTORE").assertIsNotEnabled()

        composeRule.runOnIdle {
            ui = ui.copy(status = BackupStatus.BackingUp, busy = true, feedback = null)
        }
        showAndCheck("Manual operation in progress")
        composeRule
            .onNodeWithText("Manual operation in progress")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
            )
        showAndCheck("BACK UP NOW")
        composeRule.onNodeWithText("BACK UP NOW").assertIsNotEnabled()
        showAndCheck("REVIEW MANUAL SYNC")
        composeRule.onNodeWithText("REVIEW MANUAL SYNC").assertIsNotEnabled()
    }

    @Test
    fun reducedBackup_checkboxAndExplicitConfirmation_passPlatformChecksAfterScrolling() {
        setScreen(
            ManualBackupUiState(
                review =
                    ManualReview.Backup(
                        BackupPreview(
                            id = "accessible-backup-preview",
                            localRevision = 4,
                            remoteGeneration = 1,
                            entityCounts = mapOf("PersonalRecord" to 2),
                            previousEntityCount = 10,
                            requiresDestructiveConfirmation = true,
                            associationOnly = false,
                        )
                    )
            )
        )
        checkCurrentSurface()
        showAndCheck("REDUCED BACKUP")
        showAndCheck(ACKNOWLEDGEMENT)
        composeRule
            .onNodeWithText(ACKNOWLEDGEMENT)
            .assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        showAndCheck("CONFIRM MANUAL BACKUP")
        composeRule.onNodeWithText("CONFIRM MANUAL BACKUP").assertIsNotEnabled()

        composeRule.onNodeWithText(ACKNOWLEDGEMENT).performScrollTo().performClick()
        composeRule.onNodeWithText(ACKNOWLEDGEMENT).assertIsOn()
        checkCurrentSurface()
        showAndCheck("CONFIRM MANUAL BACKUP")
        composeRule.onNodeWithText("CONFIRM MANUAL BACKUP").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, confirmations) }

        composeRule.runOnIdle { ui = ui.copy(busy = true) }
        showAndCheck(ACKNOWLEDGEMENT)
        composeRule.onNodeWithText(ACKNOWLEDGEMENT).assertIsOn().assertIsNotEnabled()
        showAndCheck("CONFIRM MANUAL BACKUP")
        composeRule.onNodeWithText("CONFIRM MANUAL BACKUP").assertIsNotEnabled()
        showAndCheck("BACK TO ACCOUNT & BACKUP")
        composeRule.onNodeWithText("BACK TO ACCOUNT & BACKUP").assertIsNotEnabled()
    }

    @Test
    fun conflictChoices_confirmationAndLatestResult_passPlatformChecksAfterScrolling() {
        setScreen(
            ManualBackupUiState(
                review =
                    ManualReview.Sync(
                        SyncPreview(
                            id = "accessible-sync-preview",
                            localRevision = 4,
                            remoteGeneration = 2,
                            localChanges = mapOf("PersonalRecord" to 2),
                            cloudChanges = mapOf("WorkoutLog" to 1),
                            conflicts = mapOf("PersonalRecord" to 1),
                        )
                    )
            )
        )
        checkCurrentSurface()
        showAndCheck("CONFLICTING RECORDS")
        showAndCheck(LOCAL_CHOICE)
        composeRule
            .onNodeWithText(LOCAL_CHOICE)
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        showAndCheck(CLOUD_CHOICE)
        composeRule
            .onNodeWithText(CLOUD_CHOICE)
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        showAndCheck("CONFIRM MANUAL SYNC")
        composeRule.onNodeWithText("CONFIRM MANUAL SYNC").assertIsNotEnabled()

        composeRule.onNodeWithText(LOCAL_CHOICE).performScrollTo().performClick()
        composeRule.onNodeWithText(LOCAL_CHOICE).assertIsSelected()
        composeRule.onNodeWithText(CLOUD_CHOICE).assertIsNotSelected()
        checkCurrentSurface()
        composeRule.onNodeWithText(CLOUD_CHOICE).performScrollTo().performClick()
        composeRule.onNodeWithText(CLOUD_CHOICE).assertIsSelected()
        composeRule.onNodeWithText(LOCAL_CHOICE).assertIsNotSelected()
        checkCurrentSurface()
        showAndCheck("CONFIRM MANUAL SYNC")
        composeRule.onNodeWithText("CONFIRM MANUAL SYNC").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, confirmations) }

        composeRule.runOnIdle {
            ui =
                ManualBackupUiState(
                    status = BackupStatus.UpToDate(COMPLETED_AT),
                    latest = completeBackup(),
                    feedback = SYNC_COMPLETED,
                )
        }
        showAndCheck(SYNC_COMPLETED)
        composeRule
            .onNodeWithText(SYNC_COMPLETED)
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
            )
    }

    private fun setScreen(initial: ManualBackupUiState) {
        ui = initial
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(1f)
            ) {
                IronPathTheme {
                    Surface {
                        AccountBackupScreen(
                            state =
                                AccountState.SignedIn(
                                    AccountId("accessibility-athlete"),
                                    AccountProfile(
                                        AccountId("accessibility-athlete"),
                                        "Demo Athlete",
                                        "athlete@example.invalid",
                                    ),
                                ),
                            onSignIn = ::noOp,
                            onRetry = ::noOp,
                            onCancel = ::noOp,
                            onPreview = ::noOp,
                            manual = ui,
                            manualActions =
                                ManualBackupActions(
                                    selectResolution = ::selectResolution,
                                    confirmDestructive = ::confirmDestructive,
                                    confirm = ::recordConfirmation,
                                ),
                        )
                    }
                }
            }
        }
    }

    private fun selectResolution(resolution: SyncConflictResolution) {
        ui = ui.copy(resolution = resolution)
    }

    private fun confirmDestructive(confirmed: Boolean) {
        ui = ui.copy(destructiveConfirmed = confirmed)
    }

    private fun recordConfirmation() {
        confirmations++
    }

    private fun noOp() = Unit

    private fun showAndCheck(text: String) {
        composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        checkCurrentSurface()
    }

    private fun checkCurrentSurface() {
        composeRule.waitForIdle()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

    private fun completeBackup() =
        RemoteBackupSummary(
            backupId = "accessible-complete-backup",
            completedAtEpochMillis = COMPLETED_AT,
            sourceInstallationId = "accessibility-installation",
            entityCounts = mapOf("PersonalRecord" to 2),
        )

    private companion object {
        const val COMPLETED_AT = 1_783_962_000_000L
        const val ACKNOWLEDGEMENT = "I confirm replacing the latest backup with this reduced data"
        const val LOCAL_CHOICE = "Merge and keep local conflict versions"
        const val CLOUD_CHOICE = "Overwrite this device from cloud"
        const val SYNC_COMPLETED =
            "Manual sync complete. Your training data now reflects the confirmed choice."
    }
}
