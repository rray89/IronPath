package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.account.AccountProfile
import com.example.ironpath.domain.account.AccountState
import com.example.ironpath.domain.backup.*
import com.example.ironpath.ui.theme.IronPathTheme
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ManualBackupScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private var ui by mutableStateOf(ManualBackupUiState())
    private var confirmations = 0
    private var cancellations = 0
    private var backupPreviews = 0
    private var syncPreviews = 0
    private var holdGuidances = 0
    private val selections = mutableListOf<SyncConflictResolution>()
    private lateinit var originalLocale: Locale
    private lateinit var originalZone: TimeZone

    @Before
    fun fixLocaleAndZone() {
        originalLocale = Locale.getDefault()
        originalZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreLocaleAndZone() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun backupPreview_showsIncludedCountsAndRequiresExplicitConfirmation() {
        setScreen(ManualBackupUiState(review = ManualReview.Backup(backupPreview())))

        composeRule.onNodeWithText("MANUAL BACKUP PREVIEW").assertIsDisplayed()
        composeRule.onNodeWithText("DEMO BACKUP ON THIS DEVICE").assertExists()
        composeRule.onNodeWithText("Workout logs: 3\nPersonal records: 2").assertExists()
        composeRule.onNodeWithText("CONFIRM MANUAL SYNC").assertDoesNotExist()
        assertEquals(0, confirmations)
        composeRule
            .onNodeWithText("CONFIRM MANUAL BACKUP")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, confirmations) }
    }

    @Test
    fun emptyAssociation_explainsThatNoEmptyBackupWillBeCreated() {
        setScreen(
            ManualBackupUiState(
                review =
                    ManualReview.Backup(
                        backupPreview().copy(entityCounts = emptyMap(), associationOnly = true)
                    )
            )
        )

        composeRule.onNodeWithText("No included records").assertExists()
        composeRule
            .onNodeWithText(
                "There is no included training data. Confirming links this local profile to the demo account without creating an empty backup."
            )
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("CONFIRM MANUAL BACKUP").performScrollTo().assertIsEnabled()
        assertEquals(0, confirmations)
    }

    @Test
    fun reducedBackup_requiresAcknowledgementAndCanWithdrawIt() {
        setScreen(
            ManualBackupUiState(
                review =
                    ManualReview.Backup(
                        backupPreview()
                            .copy(previousEntityCount = 20, requiresDestructiveConfirmation = true)
                    )
            ),
            DpSize(320.dp, 640.dp)
        )

        composeRule.onNodeWithText("REDUCED BACKUP").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "The previous backup included 20 records. This backup includes 5. Review this reduction before replacing the latest backup."
            )
            .assertExists()
        composeRule.onNodeWithText("CONFIRM MANUAL BACKUP").performScrollTo().assertIsNotEnabled()
        acknowledgement()
            .performScrollTo()
            .assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .performClick()
        acknowledgement().assertIsOn()
        composeRule.onNodeWithText("CONFIRM MANUAL BACKUP").performScrollTo().assertIsEnabled()
        acknowledgement().performScrollTo().performClick()
        acknowledgement().assertIsOff()
        composeRule.onNodeWithText("CONFIRM MANUAL BACKUP").performScrollTo().assertIsNotEnabled()
        assertEquals(0, confirmations)
    }

    @Test
    fun conflicts_haveNoImplicitSelectionAndKeepChoicesExclusiveAtTwoHundredPercentPortrait() {
        assertExplicitConflictChoices(DpSize(320.dp, 640.dp))
    }

    @Test
    fun conflicts_haveNoImplicitSelectionAndKeepChoicesExclusiveAtTwoHundredPercentLandscape() {
        assertExplicitConflictChoices(DpSize(640.dp, 320.dp))
    }

    @Test
    fun conflictFreeSync_explainsLocalImpactAndAllowsExplicitConfirmationWithoutAChoice() {
        setScreen(
            ManualBackupUiState(
                review = ManualReview.Sync(syncPreview().copy(conflicts = emptyMap()))
            )
        )

        composeRule
            .onNodeWithText(
                "Changes made on only one side are included in either outcome. Confirming sync can change training data on this device. Device time does not choose the winner."
            )
            .performScrollTo()
            .assertIsDisplayed()
        localChoice().assertDoesNotExist()
        cloudChoice().assertDoesNotExist()
        composeRule
            .onNodeWithText("CONFIRM MANUAL SYNC")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, confirmations) }
    }

    @Test
    fun invalidLocalOutcome_isDisabledWithoutChoosingCloudForTheUser() {
        setScreen(
            ManualBackupUiState(
                review = ManualReview.Sync(syncPreview().copy(canKeepLocal = false))
            )
        )

        localChoice().performScrollTo().assertIsNotEnabled().assertIsNotSelected()
        cloudChoice().performScrollTo().assertIsEnabled().assertIsNotSelected()
        composeRule.onNodeWithText("CONFIRM MANUAL SYNC").performScrollTo().assertIsNotEnabled()
        cloudChoice().performScrollTo().performClick()
        composeRule.onNodeWithText("CONFIRM MANUAL SYNC").performScrollTo().assertIsEnabled()
        composeRule
            .onNodeWithText(
                "An unavailable outcome would leave inconsistent training records. Choose a valid outcome or cancel the review."
            )
            .assertExists()
    }

    @Test
    fun invalidCloudOutcome_cannotBeConfirmedEvenWhenPresentInState() {
        setScreen(
            ManualBackupUiState(
                review = ManualReview.Sync(syncPreview().copy(canKeepCloud = false)),
                resolution = SyncConflictResolution.KeepCloud
            )
        )

        cloudChoice().performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("CONFIRM MANUAL SYNC").performScrollTo().assertIsNotEnabled()
        localChoice().performScrollTo().performClick()
        composeRule.onNodeWithText("CONFIRM MANUAL SYNC").performScrollTo().assertIsEnabled()
    }

    @Test
    fun busySync_disablesSelectionConfirmationAndBackAndAnnouncesProgress() {
        setScreen(
            ManualBackupUiState(
                review = ManualReview.Sync(syncPreview()),
                resolution = SyncConflictResolution.KeepLocal,
                busy = true
            )
        )

        localChoice().performScrollTo().assertIsSelected().assertIsNotEnabled()
        cloudChoice().performScrollTo().assertIsNotEnabled()
        composeRule
            .onNodeWithText("Manual operation in progress")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
            )
        composeRule.onNodeWithText("CONFIRM MANUAL SYNC").performScrollTo().assertIsNotEnabled()
        composeRule
            .onNodeWithText("BACK TO ACCOUNT & BACKUP")
            .performScrollTo()
            .assertIsNotEnabled()
        assertEquals(0, confirmations)
        assertEquals(0, cancellations)
    }

    @Test
    fun busyReducedBackup_disablesAcknowledgementAndConfirmation() {
        setScreen(
            ManualBackupUiState(
                review =
                    ManualReview.Backup(
                        backupPreview().copy(requiresDestructiveConfirmation = true)
                    ),
                destructiveConfirmed = true,
                busy = true
            )
        )

        acknowledgement().performScrollTo().assertIsOn().assertIsNotEnabled()
        composeRule.onNodeWithText("CONFIRM MANUAL BACKUP").performScrollTo().assertIsNotEnabled()
        composeRule
            .onNodeWithText("BACK TO ACCOUNT & BACKUP")
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun overview_reportsLatestCompleteCountsTimeAndOnlyLatestFeedback() {
        val failure = "Offline — try again when connected."
        val completed = "Manual backup complete in demo storage."
        setScreen(ManualBackupUiState(status = BackupStatus.OfflinePending, feedback = failure))

        composeRule.onNodeWithText(failure).performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            ui =
                ui.copy(
                    status = BackupStatus.UpToDate(COMPLETED_AT),
                    latest =
                        RemoteBackupSummary(
                            "complete",
                            COMPLETED_AT,
                            "installation",
                            linkedMapOf("WorkoutLog" to 3, "PersonalRecord" to 2)
                        ),
                    feedback = completed
                )
        }
        composeRule
            .onNodeWithText("LAST COMPLETE DEMO BACKUP")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("2026-07-13 17:00\nWorkout logs: 3\nPersonal records: 2")
            .assertExists()
        composeRule.onNodeWithText("Up to date").assertExists()
        composeRule.onNodeWithText(failure).assertDoesNotExist()
        composeRule
            .onNodeWithText(completed)
            .performScrollTo()
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
            )
        composeRule.onNodeWithText("BACK UP NOW").performScrollTo().performClick()
        composeRule.onNodeWithText("REVIEW MANUAL SYNC").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, backupPreviews)
            assertEquals(1, syncPreviews)
        }
    }

    @Test
    fun overview_busyDisablesManualActionsAndFixtureNavigation() {
        setScreen(ManualBackupUiState(status = BackupStatus.BackingUp, busy = true))

        listOf(
                "BACK UP NOW",
                "REVIEW MANUAL SYNC",
                "PREVIEW WHOLE-BACKUP RESTORE",
                "EXPLORE BACKUP PREVIEW"
            )
            .forEach { composeRule.onNodeWithText(it).performScrollTo().assertIsNotEnabled() }
        composeRule
            .onNodeWithText("Manual operation in progress")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
            )
    }

    @Test
    fun restorePreview_isEnabledOnlyWhenALatestCompleteBackupIsAvailable() {
        setScreen(ManualBackupUiState())
        composeRule
            .onNodeWithText("PREVIEW WHOLE-BACKUP RESTORE")
            .performScrollTo()
            .assertIsNotEnabled()

        composeRule.runOnIdle {
            ui =
                ManualBackupUiState(
                    latest =
                        RemoteBackupSummary(
                            backupId = "complete-backup",
                            completedAtEpochMillis = COMPLETED_AT,
                            sourceInstallationId = "source-installation",
                            entityCounts = linkedMapOf("PersonalRecord" to 1),
                        )
                )
        }

        composeRule
            .onNodeWithText("PREVIEW WHOLE-BACKUP RESTORE")
            .performScrollTo()
            .assertIsEnabled()
    }

    @Test
    fun restoreReviewNamesSourceAndWorkoutRequiresAcknowledgementAndLongPress() {
        val restore =
            RestorePreview(
                id = "restore-review",
                latest =
                    RemoteBackupSummary(
                        "backup-id",
                        1_700_000_000_000,
                        "another-installation",
                        mapOf("PersonalRecord" to 2),
                    ),
                sourceDescription = "Another device",
                impact =
                    mapOf(
                        "PersonalRecord" to
                            BackupCategoryImpact(added = 1, updated = 1, replaced = 0)
                    ),
                activeWorkoutDiscardRequired = true,
                activeWorkoutTitle = "Evening strength",
                nulledProvenanceFields = emptySet(),
            )
        setScreen(ManualBackupUiState(review = ManualReview.Restore(restore)))

        composeRule.onNodeWithText("WHOLE-BACKUP RESTORE REVIEW").assertIsDisplayed()
        composeRule
            .onNodeWithText("Backup source: Another device")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "The active workout ‘Evening strength’ was found. Confirming will discard it, and it will not be part of the one undo."
            )
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("I understand this active workout will be discarded")
            .performScrollTo()
            .assertIsOff()
        composeRule
            .onNodeWithText("PRESS AND HOLD TO RESTORE")
            .performScrollTo()
            .assertIsNotEnabled()
        assertEquals(0, confirmations)

        composeRule
            .onNodeWithText("I understand this active workout will be discarded")
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithText("I understand this active workout will be discarded")
            .assertIsOn()
        composeRule.onNodeWithText("PRESS AND HOLD TO RESTORE").assertIsEnabled().performClick()
        composeRule
            .onNodeWithText("Press and hold to confirm this whole-backup change.")
            .assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, holdGuidances)
            assertEquals(0, confirmations)
        }
        composeRule
            .onNodeWithText("PRESS AND HOLD TO RESTORE")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.runOnIdle { assertEquals(1, confirmations) }
    }

    @Test
    fun overview_exposesEveryManualStatusInSemantics() {
        setScreen(ManualBackupUiState(status = BackupStatus.SignedInNoBackup))
        listOf(
                BackupStatus.SignedInNoBackup to "Signed in — no backup yet",
                BackupStatus.LocalChanges to "Local changes",
                BackupStatus.ReviewRequired to "Review required",
                BackupStatus.Preparing to "Preparing manual backup",
                BackupStatus.BackingUp to "Backing up now",
                BackupStatus.UpToDate(COMPLETED_AT) to "Up to date",
                BackupStatus.OfflinePending to "Offline — try again when connected",
                BackupStatus.QuotaPaused to "Backup paused — service quota or rate limit",
                BackupStatus.NeedsSignIn to "Needs sign-in",
                BackupStatus.NeedsAttention(BackupFailureReason.InvalidSnapshot) to
                    "Needs attention",
            )
            .forEach { (status, label) ->
                composeRule.runOnIdle { ui = ui.copy(status = status) }
                composeRule
                    .onNode(
                        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label)
                    )
                    .performScrollTo()
                    .assertIsDisplayed()
            }
    }

    private fun assertExplicitConflictChoices(size: DpSize) {
        setScreen(ManualBackupUiState(review = ManualReview.Sync(syncPreview())), size)
        composeRule.onNodeWithText("MANUAL SYNC PREVIEW").assertIsDisplayed()
        composeRule.onNodeWithText("Workout logs: 3").assertExists()
        composeRule.onNodeWithText("Personal records: 2").assertExists()
        composeRule.onNodeWithText("Personal records: 1").assertExists()
        localChoice()
            .performScrollTo()
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        cloudChoice()
            .performScrollTo()
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        composeRule.onNodeWithText("CONFIRM MANUAL SYNC").performScrollTo().assertIsNotEnabled()
        localChoice().performScrollTo().performClick()
        localChoice().assertIsSelected()
        cloudChoice().assertIsNotSelected()
        composeRule.onNodeWithText("CONFIRM MANUAL SYNC").performScrollTo().assertIsEnabled()
        cloudChoice().performScrollTo().performClick()
        cloudChoice().assertIsSelected()
        localChoice().assertIsNotSelected()
        composeRule
            .onNodeWithText("CONFIRM MANUAL SYNC")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule
            .onNodeWithText("BACK TO ACCOUNT & BACKUP")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf(SyncConflictResolution.KeepLocal, SyncConflictResolution.KeepCloud),
                selections
            )
            assertEquals(1, confirmations)
            assertEquals(1, cancellations)
        }
    }

    private fun localChoice() = composeRule.onNodeWithText("Merge and keep local conflict versions")

    private fun cloudChoice() = composeRule.onNodeWithText("Overwrite this device from cloud")

    private fun acknowledgement() =
        composeRule.onNodeWithText("I confirm replacing the latest backup with this reduced data")

    private fun setScreen(initial: ManualBackupUiState, size: DpSize = DpSize(360.dp, 800.dp)) {
        ui = initial
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(size) then
                    DeviceConfigurationOverride.FontScale(2f)
            ) {
                IronPathTheme {
                    Surface {
                        AccountBackupScreen(
                            state =
                                AccountState.SignedIn(
                                    AccountId("athlete"),
                                    AccountProfile(
                                        AccountId("athlete"),
                                        "Demo Athlete",
                                        "athlete@example.invalid"
                                    )
                                ),
                            onSignIn = {},
                            onRetry = {},
                            onCancel = { cancellations++ },
                            onPreview = {},
                            manual = ui,
                            manualActions =
                                ManualBackupActions(
                                    previewBackup = { backupPreviews++ },
                                    previewSync = { syncPreviews++ },
                                    previewRestore = {},
                                    previewUndo = {},
                                    selectResolution = {
                                        selections += it
                                        ui = ui.copy(resolution = it)
                                    },
                                    confirmDestructive = {
                                        ui = ui.copy(destructiveConfirmed = it)
                                    },
                                    confirmActiveWorkoutDiscard = {
                                        ui = ui.copy(activeWorkoutDiscardConfirmed = it)
                                    },
                                    holdGuidance = {
                                        holdGuidances++
                                        ui =
                                            ui.copy(
                                                feedback =
                                                    "Press and hold to confirm this whole-backup change."
                                            )
                                    },
                                    confirm = { confirmations++ },
                                ),
                        )
                    }
                }
            }
        }
    }

    private fun backupPreview() =
        BackupPreview(
            "backup-review",
            4,
            1,
            linkedMapOf("WorkoutLog" to 3, "PersonalRecord" to 2),
            4,
            false,
            false
        )

    private fun syncPreview() =
        SyncPreview(
            "sync-review",
            4,
            2,
            mapOf("WorkoutLog" to 3),
            mapOf("PersonalRecord" to 2),
            mapOf("PersonalRecord" to 1)
        )

    private companion object {
        val COMPLETED_AT: Long = Instant.parse("2026-07-13T17:00:00Z").toEpochMilli()
    }
}
