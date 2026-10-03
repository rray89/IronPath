package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.ironpath.domain.backup.*
import com.example.ironpath.ui.theme.IronPathTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CloudManualBackupOverviewTest {
    @get:Rule val composeRule = createComposeRule()
    private var backups = 0
    private var refreshes = 0
    private var syncs = 0

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
    fun unknownReceiptAnnouncesRefreshRecoveryAndDisablesActionsWhileBusy() {
        setOverview(ManualBackupUiState(status = BackupStatus.OfflinePending, busy = true))
        composeRule.onNodeWithText("BACK UP NOW").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("REVIEW MANUAL SYNC").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("REFRESH BACKUP STATUS").performScrollTo().assertIsNotEnabled()
        composeRule
            .onNodeWithText("Manual operation in progress")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
            )
        composeRule
            .onNodeWithText(
                "An interrupted request may have reached the cloud. Refresh to check the latest complete backup, then open a fresh preview to retry."
            )
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(0, backups)
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
        composeRule.onNodeWithText("REFRESH BACKUP STATUS").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, backups)
            assertEquals(1, refreshes)
            assertEquals(1, syncs)
        }
    }

    private fun setOverview(ui: ManualBackupUiState, size: DpSize = DpSize(320.dp, 640.dp)) {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(size) then
                    DeviceConfigurationOverride.FontScale(2f)
            ) {
                IronPathTheme {
                    Surface {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            CloudManualBackupOverview(
                                ui,
                                true,
                                { backups++ },
                                { refreshes++ },
                                { syncs++ }
                            )
                        }
                    }
                }
            }
        }
    }
}
