package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.ironpath.domain.backup.*
import com.example.ironpath.ui.theme.Error
import com.example.ironpath.ui.theme.IronPathTheme
import com.example.ironpath.ui.theme.OnSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class CloudManualBackupOverviewTest {
    @get:Rule val composeRule = createComposeRule()
    private var backups = 0
    private var refreshes = 0
    private var ui by mutableStateOf(ManualBackupUiState())
    private lateinit var scrollState: ScrollState
    private var syncs = 0
    private var restores = 0
    private var undos = 0

    @Test
    fun cloudRestoreHasAccurateSourceCopyUncheckedDiscardAndHoldConfirmationAtLargeFont() {
        val ui =
            mutableStateOf(
                ManualBackupUiState(
                    review =
                        ManualReview.Restore(
                            RestorePreview(
                                "restore",
                                RemoteBackupSummary(
                                    "cloud",
                                    1000,
                                    "other",
                                    mapOf("PersonalRecord" to 2)
                                ),
                                "Another device",
                                mapOf("PersonalRecord" to BackupCategoryImpact(1, 1, 1)),
                                true,
                                "Leg day",
                                emptySet()
                            )
                        )
                )
            )
        var holds = 0
        var hints = 0
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp)) then
                    DeviceConfigurationOverride.FontScale(2f)
            ) {
                IronPathTheme {
                    Surface {
                        ManualBackupReviewScreen(
                            ui.value,
                            ManualBackupActions(
                                confirmActiveWorkoutDiscard = {
                                    ui.value = ui.value.copy(activeWorkoutDiscardConfirmed = it)
                                },
                                holdGuidance = { hints++ },
                                confirm = { holds++ }
                            ),
                            {},
                            demoStorage = false
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithText("LATEST COMPLETE CLOUD BACKUP").assertExists()
        composeRule.onNodeWithText("LATEST COMPLETE DEMO BACKUP").assertDoesNotExist()
        composeRule
            .onNodeWithText("Backup source: Another device")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Added: 1 · Updated: 1 · Replaced: 1")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("PRESS AND HOLD TO RESTORE")
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule
            .onNodeWithText("I understand this active workout will be discarded")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        composeRule
            .onNodeWithText("PRESS AND HOLD TO RESTORE")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, hints)
            assertEquals(0, holds)
        }
        composeRule.onNodeWithText("PRESS AND HOLD TO RESTORE").performTouchInput { longClick() }
        composeRule.runOnIdle { assertEquals(1, holds) }
    }

    @Test
    fun cloudUndoIsLocalOnlyAndActiveWorkoutBlocksTheHold() {
        composeRule.setContent {
            IronPathTheme {
                Surface {
                    ManualBackupReviewScreen(
                        ManualBackupUiState(
                            review = ManualReview.Undo(UndoPreview("undo", emptyMap(), true))
                        ),
                        ManualBackupActions(),
                        {},
                        demoStorage = false
                    )
                }
            }
        }
        composeRule.onNodeWithText("ONE LOCAL UNDO").assertExists()
        composeRule.onNodeWithText("PRESS AND HOLD TO UNDO").performScrollTo().assertIsNotEnabled()
        composeRule
            .onNodeWithText(
                "Only confirming this preview uploads included training data to your Google account's IronPath backup."
            )
            .assertDoesNotExist()
    }

    @Test
    fun cloudConflictReviewRequiresChoiceAndSupportsCancelAtLargeFont() {
        val ui =
            mutableStateOf(
                ManualBackupUiState(
                    review =
                        ManualReview.Sync(
                            SyncPreview(
                                "sync",
                                1,
                                2,
                                mapOf("PersonalRecord" to 2),
                                mapOf("PersonalRecord" to 2),
                                mapOf("PersonalRecord" to 1)
                            )
                        )
                )
            )
        var cancelled = 0
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp)) then
                    DeviceConfigurationOverride.FontScale(2f)
            ) {
                IronPathTheme {
                    Surface {
                        ManualBackupReviewScreen(
                            ui.value,
                            ManualBackupActions(
                                selectResolution = { ui.value = ui.value.copy(resolution = it) },
                                confirm = { syncs++ },
                            ),
                            { cancelled++ },
                            demoStorage = false
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithText("MANUAL CLOUD SYNC").assertExists()
        composeRule.onNodeWithText("CONFIRM MANUAL SYNC").performScrollTo().assertIsNotEnabled()
        composeRule
            .onNodeWithText("Merge and keep local conflict versions")
            .performScrollTo()
            .assertIsNotSelected()
            .performClick()
        composeRule.onNodeWithText("Merge and keep local conflict versions").assertIsSelected()
        composeRule
            .onNodeWithText("Overwrite this device from cloud")
            .performScrollTo()
            .assertIsNotSelected()
            .performClick()
        composeRule.onNodeWithText("Overwrite this device from cloud").assertIsSelected()
        composeRule.onNodeWithText("Merge and keep local conflict versions").assertIsNotSelected()
        composeRule.onNodeWithText("CONFIRM MANUAL SYNC").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("BACK TO ACCOUNT & BACKUP").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, cancelled)
            assertEquals(0, syncs)
        }
    }

    @Test
    fun explicitActionsRemainReachableAtTwoHundredPercentPortrait() =
        checkActions(DpSize(320.dp, 640.dp))

    @Test
    fun explicitActionsRemainReachableAtTwoHundredPercentLandscape() =
        checkActions(DpSize(640.dp, 320.dp))

    @Test
    fun busyOperationDisablesActionsAndHidesEarlierError() {
        setOverview(
            ManualBackupUiState(
                status = BackupStatus.OfflinePending,
                busy = true,
                feedback = DENIED,
                feedbackFailureReason = BackupFailureReason.PermissionDenied
            )
        )
        composeRule.onNodeWithText("BACK UP NOW").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("REVIEW MANUAL SYNC").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("REFRESH BACKUP STATUS").performScrollTo().assertIsNotEnabled()
        composeRule
            .onNodeWithText("Manual operation in progress")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
            )
        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            .assertDoesNotExist()
        composeRule.onNodeWithText(DENIED, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0, backups)
    }

    @Test
    fun deniedAccessIsRedAccessibleAndReachableAtTwoHundredPercentPortrait() =
        checkDeniedAccess(DpSize(320.dp, 640.dp))

    @Test
    fun deniedAccessIsRedAccessibleAndReachableAtTwoHundredPercentLandscape() =
        checkDeniedAccess(DpSize(640.dp, 320.dp))

    @Test
    fun repeatedRefreshAtBottomRevealsCauseAtTwoHundredPercentPortrait() =
        checkRefreshRevealsCause(DpSize(320.dp, 640.dp))

    @Test
    fun repeatedRefreshAtBottomRevealsCauseAtTwoHundredPercentLandscape() =
        checkRefreshRevealsCause(DpSize(640.dp, 320.dp))

    @Test
    fun restoredStatusAloneDoesNotRequestScrolling() {
        setOverview(
            ManualBackupUiState(status = BackupStatus.UpToDate(1000)),
            DpSize(640.dp, 320.dp)
        )
        composeRule.onNodeWithText("REFRESH BACKUP STATUS").performScrollTo()
        var previousScroll = 0
        composeRule.runOnIdle {
            previousScroll = scrollState.value
            ui = ui.copy(status = BackupStatus.NeedsAttention(BackupFailureReason.PermissionDenied))
        }
        composeRule.runOnIdle { assertEquals(previousScroll, scrollState.value) }
    }

    @Test
    fun unknownReceiptLeadsWithTypedCauseBeforeRefreshRecovery() {
        setOverview(ManualBackupUiState(status = BackupStatus.OfflinePending))
        val alert = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        alert.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive)
        )
        assertEquals(
            "Offline — try again when connected.",
            alert.fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        )
        composeRule
            .onNodeWithText(
                "An interrupted request may have reached the cloud. When connected, refresh to check the latest complete backup, then open a fresh preview to retry.",
                useUnmergedTree = true
            )
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun repeatedActionsReplaceErrorAndSuccessStaysNeutralDespiteOlderErrorStatus() {
        setOverview(
            ManualBackupUiState(
                status = BackupStatus.NeedsAttention(BackupFailureReason.PermissionDenied),
                feedback = DENIED,
                feedbackFailureReason = BackupFailureReason.PermissionDenied
            )
        )
        repeat(2) {
            composeRule.onNodeWithText("BACK UP NOW").performScrollTo().performClick()
            composeRule.onNodeWithText("REFRESH BACKUP STATUS").performScrollTo().performClick()
        }
        composeRule.runOnIdle {
            assertEquals(2, backups)
            assertEquals(2, refreshes)
            ui = ui.copy(feedback = "Manual cloud backup complete.", feedbackFailureReason = null)
        }
        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            .assertDoesNotExist()
        composeRule.onNodeWithText(DENIED, useUnmergedTree = true).assertDoesNotExist()
        assertTextColorAndNoOverflow("Manual cloud backup complete.", OnSurface)
        composeRule.runOnIdle { ui = ui.copy(feedback = "Manual operation cancelled.") }
        assertTextColorAndNoOverflow("Manual operation cancelled.", OnSurface)
    }

    @Test
    fun realCloudPreviewRequiresConfirmationAndExplainsDurableAssociation() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp)) then
                    DeviceConfigurationOverride.FontScale(2f)
            ) {
                IronPathTheme {
                    Surface {
                        ManualBackupReviewScreen(
                            ManualBackupUiState(
                                review =
                                    ManualReview.Backup(
                                        BackupPreview(
                                            "preview",
                                            1,
                                            0,
                                            mapOf("PersonalRecord" to 1),
                                            0,
                                            false,
                                            false
                                        )
                                    )
                            ),
                            ManualBackupActions(confirm = { backups++ }),
                            {},
                            demoStorage = false
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithText("MANUAL CLOUD BACKUP").assertExists()
        composeRule
            .onNodeWithText(
                "Confirming associates this local profile with your account, then uploads a complete cloud backup. The association remains if upload fails. Your active workout, AI drafts and credentials are excluded."
            )
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(0, backups)
        composeRule
            .onNodeWithText("CONFIRM MANUAL BACKUP")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, backups) }
    }

    private fun checkActions(size: DpSize) {
        setOverview(
            ManualBackupUiState(
                status = BackupStatus.UpToDate(1000),
                undoAvailable = true,
                latest =
                    RemoteBackupSummary(
                        "complete",
                        1000,
                        "installation",
                        mapOf("PersonalRecord" to 1)
                    )
            ),
            size
        )
        composeRule
            .onNodeWithText("LATEST COMPLETE CLOUD BACKUP")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("REVIEW MANUAL SYNC").performScrollTo().performClick()
        composeRule.onNodeWithText("BACK UP NOW").performScrollTo().performClick()
        composeRule.onNodeWithText("PREVIEW WHOLE-BACKUP RESTORE").performScrollTo().performClick()
        composeRule.onNodeWithText("PREVIEW ONE UNDO").performScrollTo().performClick()
        composeRule.onNodeWithText("REFRESH BACKUP STATUS").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, backups)
            assertEquals(1, refreshes)
            assertEquals(1, syncs)
            assertEquals(1, restores)
            assertEquals(1, undos)
        }
    }

    private fun checkDeniedAccess(size: DpSize) {
        // Action failures must be visible even before the coordinator updates its status.
        setOverview(
            ManualBackupUiState(
                status = BackupStatus.UpToDate(1000),
                feedback = DENIED,
                feedbackFailureReason = BackupFailureReason.PermissionDenied
            ),
            size
        )
        composeRule
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.Error, DENIED))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive
                )
            )
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Backup failed")
            )
        assertTextColorAndNoOverflow(DENIED, Error)
        composeRule
            .onNodeWithText(
                "Check that you are using the intended account. After cloud access is restored, refresh backup status, then open a fresh preview.",
                useUnmergedTree = true
            )
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("An interrupted request", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("BACK UP NOW").performScrollTo().assertIsEnabled().performClick()
        composeRule
            .onNodeWithText("REFRESH BACKUP STATUS")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, backups)
            assertEquals(1, refreshes)
        }
    }

    private fun checkRefreshRevealsCause(size: DpSize) {
        setOverview(
            ManualBackupUiState(status = BackupStatus.UpToDate(1000)),
            size,
            onRefresh = {
                refreshes++
                // Complete immediately to cover identical results coalesced within one frame.
                ui =
                    ui.copy(
                        feedback = DENIED,
                        feedbackFailureReason = BackupFailureReason.PermissionDenied,
                        feedbackFailureVersion = ui.feedbackFailureVersion + 1
                    )
            }
        )
        repeat(2) {
            composeRule.onNodeWithText("REFRESH BACKUP STATUS").performScrollTo().performClick()
            // No manual scroll to the alert: the returned action error must reveal its own cause.
            composeRule.onNodeWithText(DENIED, useUnmergedTree = true).assertIsDisplayed()
        }
        composeRule.runOnIdle { assertEquals(2, refreshes) }
    }

    private fun assertTextColorAndNoOverflow(
        text: String,
        color: androidx.compose.ui.graphics.Color
    ) {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(color, layouts.single().layoutInput.style.color)
        assertFalse(layouts.single().hasVisualOverflow)
    }

    private fun setOverview(
        state: ManualBackupUiState,
        size: DpSize = DpSize(320.dp, 640.dp),
        onRefresh: () -> Unit = { refreshes++ }
    ) {
        ui = state
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(size) then
                    DeviceConfigurationOverride.FontScale(2f)
            ) {
                IronPathTheme {
                    Surface {
                        scrollState = rememberScrollState()
                        Column(Modifier.verticalScroll(scrollState).padding(24.dp)) {
                            CloudManualBackupOverview(
                                ui,
                                true,
                                { backups++ },
                                onRefresh,
                                { syncs++ },
                                { restores++ },
                                { undos++ },
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val DENIED = "Backup access was denied. Your local data remains available."
    }
}
