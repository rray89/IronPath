package com.example.ironpath.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.MainActivity
import com.example.ironpath.data.account.AccountSessionOperationGate
import com.example.ironpath.data.account.PersistedAccountGateway
import com.example.ironpath.data.backup.InstallationGuard
import com.example.ironpath.data.backup.LocalProfileResetResult
import com.example.ironpath.data.backup.LocalProfileResetter
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.di.AccountBindingsModule
import com.example.ironpath.di.DebugAccountDeletionModule
import com.example.ironpath.di.DebugAccountModeModule
import com.example.ironpath.domain.account.AccountContextReader
import com.example.ironpath.domain.account.AccountCredentialActivityHost
import com.example.ironpath.domain.account.AccountDeletionManager
import com.example.ironpath.domain.account.AccountDeletionProgress
import com.example.ironpath.domain.account.AccountDeletionRemoteState
import com.example.ironpath.domain.account.AccountDeletionRequest
import com.example.ironpath.domain.account.AccountDeletionResult
import com.example.ironpath.domain.account.AccountDeletionStage
import com.example.ironpath.domain.account.AccountExperienceCapabilities
import com.example.ironpath.domain.account.AccountGateway
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.account.AccountProfile
import com.example.ironpath.domain.account.AccountSessionAdapter
import com.example.ironpath.domain.account.NoOpAccountCredentialActivityHost
import com.example.ironpath.domain.account.UnreadableAccountSessionException
import com.example.ironpath.testutil.AccountFilesUnchangedRule
import com.example.ironpath.testutil.FakeAccountSessionAdapter
import com.example.ironpath.testutil.FakeOnboardingRepository
import com.example.ironpath.testutil.HiltTestDatabaseRule
import com.example.ironpath.testutil.TestData
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.screens.accountbackup.ACCOUNT_DELETION_PENDING_MESSAGE
import com.example.ironpath.ui.screens.accountbackup.ACCOUNT_DELETION_RESERVED_MESSAGE
import com.example.ironpath.ui.testing.TestTags
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

@HiltAndroidTest
@UninstallModules(
    DebugAccountDeletionModule::class,
    DebugAccountModeModule::class,
    AccountBindingsModule::class,
)
@RunWith(AndroidJUnit4::class)
class AccountDeletionStartupRetryTest {
    @get:Rule(order = 0) val accountFilesRule = AccountFilesUnchangedRule()
    @get:Rule(order = 1) val databaseRule = HiltTestDatabaseRule()
    @get:Rule(order = 2) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 3)
    val startupFixture =
        object : TestWatcher() {
            override fun starting(description: Description) {
                if (description.methodName.startsWith("ordinaryStartup")) {
                    deletion.hasDeletion = false
                    accountCapabilities = AccountExperienceCapabilities.Demo
                }
            }
        }
    @get:Rule(order = 4) val composeRule = createAndroidComposeRule<MainActivity>()

    private val deletion = ExplicitRetryDeletionManager()
    @BindValue @JvmField val accountDeletionManager: AccountDeletionManager = deletion

    @BindValue @JvmField var accountCapabilities = AccountExperienceCapabilities.AuthPreview
    @BindValue @JvmField val sessionReadControl = SessionReadControl()
    @BindValue
    @JvmField
    val credentialHost: AccountCredentialActivityHost = NoOpAccountCredentialActivityHost()

    @Inject lateinit var database: IronPathDatabase
    @Inject lateinit var installationGuard: InstallationGuard
    @Inject lateinit var localProfileResetter: LocalProfileResetter
    @Inject lateinit var session: FakeAccountSessionAdapter
    @Inject lateinit var onboarding: FakeOnboardingRepository

    @Before fun inject() = hiltRule.inject()

    @Test
    fun startupAndRecreationOnlyRecover_userRetryCompletesAndOpensLocalApp() {
        waitForText("Finishing account deletion")
        val initialRecoveries = deletion.recoveries.get()
        assertTrue(initialRecoveries > 0)
        assertEquals(0, deletion.retries.get())
        assertEquals(0, deletion.cancellations.get())
        composeRule.onNodeWithText("CANCEL IF NOT STARTED").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").assertDoesNotExist()
        composeRule.onNodeWithText("No workout plan yet").assertDoesNotExist()

        composeRule.activityRule.scenario.recreate()
        waitForText("Finishing account deletion")
        assertTrue(deletion.recoveries.get() > initialRecoveries)
        assertEquals(0, deletion.retries.get())
        assertEquals(0, deletion.cancellations.get())
        composeRule.onNodeWithText("CANCEL IF NOT STARTED").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").assertDoesNotExist()

        composeRule.onNodeWithText("RETRY DELETION").performScrollTo().performClick()
        waitForText("CONTINUE ON THIS DEVICE")
        assertEquals(1, deletion.retries.get())
        composeRule.onNodeWithText("Finishing account deletion").assertDoesNotExist()
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").performScrollTo().performClick()
        waitForText("No workout plan yet")
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HOME)).assertIsDisplayed()

        val recoveriesBeforeRecreation = deletion.recoveries.get()
        composeRule.activityRule.scenario.recreate()
        waitForText("No workout plan yet")
        assertTrue(deletion.recoveries.get() > recoveriesBeforeRecreation)
        assertEquals(1, deletion.retries.get())
        assertEquals(0, deletion.newDeletions.get())
    }

    @Test
    fun unavailableStartupRetryWithoutReportedProgressKeepsTrainingClosedUntilCleanupCompletes() {
        waitForText("Finishing account deletion")
        deletion.retryResult = AccountDeletionResult.Unavailable
        deletion.reportPendingProgress = false

        composeRule.onNodeWithText("RETRY DELETION").performScrollTo().performClick()
        waitForText("Finishing account deletion")
        assertEquals(1, deletion.retries.get())
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").assertDoesNotExist()
        composeRule.onNodeWithText("No workout plan yet").assertDoesNotExist()
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HOME)).assertDoesNotExist()
        listOf("CANCEL", "SIGN OUT", "BACK UP NOW", "CONTINUE ANYWAY").forEach {
            composeRule.onNodeWithText(it).assertDoesNotExist()
        }

        deletion.retryResult = AccountDeletionResult.Completed
        composeRule.onNodeWithText("RETRY DELETION").performScrollTo().performClick()
        waitForText("CONTINUE ON THIS DEVICE")
        assertEquals(2, deletion.retries.get())
        assertEquals(0, deletion.newDeletions.get())
    }

    @Test
    fun explicitStartupCancellationPreservesTrainingGraphAndReopensNavigationAfterRecreation() {
        waitForText("Finishing account deletion")
        val profile =
            AccountProfile(
                deletion.progress.accountId,
                "Isolated Athlete",
                "isolated@example.invalid"
            )
        val before = runBlocking {
            installationGuard.validate()
            val metadata = requireNotNull(database.backupDao().getMetadata())
            database.backupDao().updateMetadata(metadata.copy(ownerUid = profile.id.opaqueValue))
            database.backupDao().insertWeeklyPlans(listOf(TestData.plan()))
            database.backupDao().insertPlannedWorkouts(listOf(TestData.workout()))
            database.backupDao().insertPlannedExercises(listOf(TestData.plannedExercise()))
            database
                .backupDao()
                .insertWorkoutLogs(listOf(TestData.log(title = "Preserved cancellation workout")))
            database.backupDao().insertLoggedExercises(listOf(TestData.loggedExercise()))
            database.backupDao().insertLoggedSets(listOf(TestData.loggedSet()))
            database.backupDao().insertPersonalRecords(listOf(TestData.record()))
            database.sessionDao().insertSession(TestData.session())
            database.sessionDao().insertSessionExercises(listOf(TestData.sessionExercise()))
            database.sessionDao().insertSet(TestData.sessionSet())
            onboarding.complete()
            localGraphSnapshot()
        }
        session.session = profile
        val cancellation = CompletableDeferred<Unit>()
        deletion.cancellationGate = cancellation
        composeRule.activityRule.scenario.recreate()
        waitForText("Finishing account deletion")
        composeRule
            .onNodeWithText(ACCOUNT_DELETION_RESERVED_MESSAGE)
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(0, deletion.cancellations.get())
        assertEquals(0, deletion.retries.get())
        composeRule.onNodeWithText("CANCEL IF NOT STARTED").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { deletion.cancellations.get() == 1 }
        composeRule.onNodeWithText("CANCEL IF NOT STARTED").assertDoesNotExist()
        composeRule.onNodeWithText("RETRY DELETION").assertDoesNotExist()
        assertStartupNavigationClosed()
        assertEquals(before, runBlocking { localGraphSnapshot() })
        assertEquals(profile, session.session)

        cancellation.complete(Unit)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("IRONPATH").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HOME)).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HISTORY)).performClick()
        waitForText("Preserved cancellation workout")
        assertEquals(before, runBlocking { localGraphSnapshot() })
        assertEquals(profile, session.session)
        assertEquals(1, deletion.cancellations.get())
        assertEquals(0, deletion.retries.get())
        assertEquals(0, deletion.newDeletions.get())

        val recoveries = deletion.recoveries.get()
        composeRule.activityRule.scenario.recreate()
        waitForText("Preserved cancellation workout")
        assertTrue(deletion.recoveries.get() > recoveries)
        assertEquals(before, runBlocking { localGraphSnapshot() })
        assertEquals(profile, session.session)
        assertEquals(1, deletion.cancellations.get())
        composeRule.onNodeWithText("CANCEL IF NOT STARTED").assertDoesNotExist()
    }

    @Test
    fun unavailableStartupCancellationKeepsBarrierAndDoesNotBecomeActivation() {
        waitForText("Finishing account deletion")
        deletion.cancelResult = AccountDeletionResult.Unavailable
        composeRule.onNodeWithText("CANCEL IF NOT STARTED").performScrollTo().performClick()
        waitForText("Finishing account deletion")
        assertEquals(1, deletion.cancellations.get())
        assertEquals(0, deletion.retries.get())
        assertEquals(0, deletion.newDeletions.get())
        assertStartupNavigationClosed()
        composeRule.onNodeWithText("CANCEL IF NOT STARTED").performScrollTo().assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        waitForText("Finishing account deletion")
        assertEquals(1, deletion.cancellations.get())
        assertEquals(0, deletion.retries.get())
        assertStartupNavigationClosed()
    }

    @Test
    fun activationWinningCancellationKeepsStartupLockedAndRemovesCancelAction() {
        waitForText("Finishing account deletion")
        val activated =
            deletion.progress.copy(
                remoteState = AccountDeletionRemoteState.PENDING,
                receiptVersion = 2
            )
        deletion.cancelResult = AccountDeletionResult.RetryRequired(activated)
        composeRule.onNodeWithText("CANCEL IF NOT STARTED").performScrollTo().performClick()
        waitForText("Finishing account deletion")
        composeRule
            .onNodeWithText(ACCOUNT_DELETION_PENDING_MESSAGE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("CANCEL IF NOT STARTED").assertDoesNotExist()
        assertStartupNavigationClosed()
        assertEquals(1, deletion.cancellations.get())
        assertEquals(0, deletion.retries.get())
        assertEquals(0, deletion.newDeletions.get())

        composeRule.activityRule.scenario.recreate()
        waitForText("Finishing account deletion")
        composeRule.onNodeWithText("CANCEL IF NOT STARTED").assertDoesNotExist()
        assertStartupNavigationClosed()
        assertEquals(1, deletion.cancellations.get())
    }

    @Test
    fun terminalAcknowledgmentFailureKeepsStartupLockedAcrossRecreationUntilVerifiedRetry() {
        waitForText("Finishing account deletion")
        deletion.allowAcknowledgment = false
        composeRule.onNodeWithText("CANCEL IF NOT STARTED").performScrollTo().performClick()
        waitForText("Finishing account deletion")
        assertEquals(1, deletion.cancellations.get())
        assertTrue(deletion.acknowledgments.get() > 0)
        assertEquals(AccountDeletionStage.CANCELLED, runBlocking { deletion.pending() }?.stage)
        composeRule.onNodeWithText("CANCEL IF NOT STARTED").assertDoesNotExist()
        assertStartupNavigationClosed()

        val acknowledgments = deletion.acknowledgments.get()
        composeRule.activityRule.scenario.recreate()
        waitForText("Finishing account deletion")
        assertTrue(deletion.acknowledgments.get() > acknowledgments)
        assertStartupNavigationClosed()
        assertEquals(1, deletion.cancellations.get())
        assertEquals(0, deletion.retries.get())

        deletion.allowAcknowledgment = true
        composeRule.onNodeWithText("RETRY DELETION").performScrollTo().performClick()
        waitForText("CONTINUE ON THIS DEVICE")
        assertNull(runBlocking { deletion.pending() })
        assertEquals(1, deletion.retries.get())
        assertEquals(1, deletion.cancellations.get())
        composeRule.activityRule.scenario.recreate()
        waitForText("CONTINUE ON THIS DEVICE")
        assertEquals(1, deletion.retries.get())
    }

    @Test
    fun idleExplicitDeletionActionsWithoutReportedProgressKeepKnownRecoveryLocked() {
        waitForText("Finishing account deletion")
        deletion.retryResult = AccountDeletionResult.Idle
        deletion.cancelResult = AccountDeletionResult.Idle
        deletion.reportPendingProgress = false

        composeRule.onNodeWithText("RETRY DELETION").performScrollTo().performClick()
        waitForText("Finishing account deletion")
        assertEquals(1, deletion.retries.get())
        composeRule
            .onNodeWithText(ACCOUNT_DELETION_RESERVED_MESSAGE)
            .performScrollTo()
            .assertIsDisplayed()
        assertStartupNavigationClosed()

        composeRule.onNodeWithText("CANCEL IF NOT STARTED").performScrollTo().performClick()
        waitForText("Finishing account deletion")
        assertEquals(1, deletion.cancellations.get())
        composeRule
            .onNodeWithText(ACCOUNT_DELETION_RESERVED_MESSAGE)
            .performScrollTo()
            .assertIsDisplayed()
        assertStartupNavigationClosed()
        assertEquals(0, deletion.newDeletions.get())

        composeRule.activityRule.scenario.recreate()
        waitForText("Finishing account deletion")
        assertStartupNavigationClosed()
        assertEquals(1, deletion.retries.get())
        assertEquals(1, deletion.cancellations.get())
    }

    @Test
    fun ordinaryStartupWithSamePendingSignOutSessionAllowsFinishWithoutAnotherProfileReset() {
        val profile =
            AccountProfile(
                AccountId("pending-sign-out"),
                "Pending Athlete",
                "pending@example.invalid"
            )
        val committed = prepareCommittedSignOut(profile)
        composeRule.activityRule.scenario.recreate()
        waitForText("No workout plan yet")
        openAccountBackup()
        waitForAccountAction("FINISH SIGN OUT")
        assertEquals(profile, session.session)
        assertEquals(committed, runBlocking { database.backupDao().getMetadata() })

        composeRule.activityRule.scenario.recreate()
        waitForAccountAction("FINISH SIGN OUT")
        assertEquals(profile, session.session)
        assertEquals(committed, runBlocking { database.backupDao().getMetadata() })
        composeRule.onNodeWithText("FINISH SIGN OUT").performScrollTo().performClick()
        waitForAccountAction("SIGN IN WITH GOOGLE")
        assertNull(session.session)
        assertEquals(
            committed.copy(pendingSignOutUid = null),
            runBlocking { database.backupDao().getMetadata() }
        )
        assertNoDeletionActionsWereInvoked()
    }

    @Test
    fun ordinaryStartupWithAlreadyClearedSignOutSessionRetiresMarkerAndPreservesGeneration() {
        val committed = prepareCommittedSignOut(null)
        composeRule.activityRule.scenario.recreate()
        waitForText("No workout plan yet")
        val retired = committed.copy(pendingSignOutUid = null)
        assertEquals(retired, runBlocking { database.backupDao().getMetadata() })
        assertNull(session.session)
        openAccountBackup()
        waitForAccountAction("SIGN IN WITH GOOGLE")
        composeRule.onNodeWithText("FINISH SIGN OUT").assertDoesNotExist()

        composeRule.activityRule.scenario.recreate()
        waitForAccountAction("SIGN IN WITH GOOGLE")
        assertEquals(retired, runBlocking { database.backupDao().getMetadata() })
        assertNull(session.session)
        assertNoDeletionActionsWereInvoked()
    }

    @Test
    fun ordinaryStartupWithForeignSignOutSessionRetiresOnlyOldMarkerAndKeepsForeignSession() {
        val foreign =
            AccountProfile(
                AccountId("foreign-account"),
                "Foreign Athlete",
                "foreign@example.invalid"
            )
        val committed = prepareCommittedSignOut(foreign)
        composeRule.activityRule.scenario.recreate()
        waitForText("No workout plan yet")
        val retired = committed.copy(pendingSignOutUid = null)
        assertEquals(retired, runBlocking { database.backupDao().getMetadata() })
        assertEquals(foreign, session.session)
        openAccountBackup()
        waitForText("YOUR ACCOUNT")
        composeRule
            .onNodeWithTag(TestTags.ACCOUNT_STATUS)
            .assertTextContains("Data choice required")
        composeRule
            .onNode(hasText("Foreign Athlete") and hasAnyAncestor(hasTestTag(TestTags.APP_CONTENT)))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("FINISH SIGN OUT").assertDoesNotExist()
        assertNull(runBlocking { database.backupDao().getMetadata()?.ownerUid })

        composeRule.activityRule.scenario.recreate()
        waitForText("YOUR ACCOUNT")
        composeRule
            .onNodeWithTag(TestTags.ACCOUNT_STATUS)
            .assertTextContains("Data choice required")
        assertEquals(retired, runBlocking { database.backupDao().getMetadata() })
        assertEquals(foreign, session.session)
        assertNoDeletionActionsWereInvoked()
    }

    @Test
    fun ordinaryStartupWithUnreadableSessionKeepsExplicitAccountRecoveryReachableWithoutClearingTraining() {
        continueToLocalHome()
        val before = runBlocking {
            installationGuard.validate()
            database
                .backupDao()
                .insertWorkoutLogs(
                    listOf(TestData.log(title = "Unreadable session preserved workout"))
                )
            localGraphSnapshot()
        }
        deletion.hasDeletion = false
        sessionReadControl.unreadable = true
        composeRule.activityRule.scenario.recreate()
        waitForText("No workout plan yet")
        composeRule.onNodeWithText("Local profile unavailable").assertDoesNotExist()
        openAccountBackup()
        waitForAccountAction("CLEAR INVALID DEMO SESSION")
        assertEquals(0, sessionReadControl.clearCalls.get())
        assertEquals(before, runBlocking { localGraphSnapshot() })

        composeRule.activityRule.scenario.recreate()
        waitForAccountAction("CLEAR INVALID DEMO SESSION")
        assertEquals(0, sessionReadControl.clearCalls.get())
        composeRule.onNodeWithText("CLEAR INVALID DEMO SESSION").performScrollTo().performClick()
        waitForAccountAction("SIGN IN WITH GOOGLE")
        assertEquals(1, sessionReadControl.clearCalls.get())
        assertNull(session.session)
        assertEquals(before, runBlocking { localGraphSnapshot() })
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HISTORY)).performClick()
        waitForText("Unreadable session preserved workout")
        assertNoDeletionActionsWereInvoked()
    }

    private fun prepareCommittedSignOut(
        currentSession: AccountProfile?
    ): com.example.ironpath.data.local.entity.AccountBackupMetadata {
        continueToLocalHome()
        // Keep the provider and committed marker coherent before Room observers can run.
        return composeRule.runOnIdle {
            session.session = currentSession
            runBlocking {
                installationGuard.validate()
                database
                    .backupDao()
                    .insertWorkoutLogs(listOf(TestData.log(id = "removed-sign-out-log")))
                val result =
                    localProfileResetter.resetLocalProfile(
                        pendingSignOutUid = "pending-sign-out",
                        expectedProfileGeneration = 0,
                    )
                assertTrue(result is LocalProfileResetResult.Committed)
                assertTrue(database.backupDao().getWorkoutLogs().isEmpty())
                requireNotNull(database.backupDao().getMetadata()).also {
                    assertEquals(1L, it.profileGeneration)
                    assertEquals("pending-sign-out", it.pendingSignOutUid)
                    assertNull(it.ownerUid)
                }
            }
        }
    }

    private fun continueToLocalHome() {
        waitForText("CONTINUE ON THIS DEVICE")
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").performScrollTo().performClick()
        waitForText("No workout plan yet")
    }

    private fun openAccountBackup() {
        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("Back up your training data").performClick()
        waitForText("YOUR ACCOUNT")
    }

    private fun waitForAccountAction(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    private fun assertNoDeletionActionsWereInvoked() {
        assertEquals(0, deletion.retries.get())
        assertEquals(0, deletion.cancellations.get())
        assertEquals(0, deletion.newDeletions.get())
        assertNull(runBlocking { database.accountDeletionDao().getJournal() })
        composeRule.onNodeWithText("Finishing account deletion").assertDoesNotExist()
    }

    private fun assertStartupNavigationClosed() {
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").assertDoesNotExist()
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HOME)).assertDoesNotExist()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HOME)).assertDoesNotExist()
        assertTrue(!composeRule.activity.isFinishing)
    }

    private suspend fun localGraphSnapshot(): List<Any?> =
        listOf(
            database.backupDao().getMetadata(),
            database.backupDao().getWeeklyPlans(),
            database.backupDao().getPlannedWorkouts(),
            database.backupDao().getPlannedExercises(),
            database.backupDao().getWorkoutLogs(),
            database.backupDao().getLoggedExercises(),
            database.backupDao().getLoggedSets(),
            database.backupDao().getPersonalRecords(),
            database.sessionDao().getActiveSession(),
            database.sessionDao().getExercisesForSession("session-a"),
            database.sessionDao().getSetsForExercises(listOf("session-exercise-a")),
        )

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    /** Simulates a prepared operation that requires user-triggered Google verification. */
    private class ExplicitRetryDeletionManager : AccountDeletionManager {
        val recoveries = AtomicInteger()
        val retries = AtomicInteger()
        val newDeletions = AtomicInteger()
        val cancellations = AtomicInteger()
        val acknowledgments = AtomicInteger()
        @Volatile var allowAcknowledgment = true
        @Volatile private var acknowledged = false
        @Volatile private var terminalResult: AccountDeletionResult? = null
        @Volatile var hasDeletion = true
        @Volatile var cancelResult: AccountDeletionResult = AccountDeletionResult.Cancelled
        @Volatile var cancellationGate: CompletableDeferred<Unit>? = null
        @Volatile var retryResult: AccountDeletionResult = AccountDeletionResult.Completed
        @Volatile var reportPendingProgress = true
        @Volatile
        var progress =
            AccountDeletionProgress(
                operationId = "8f244a25-b4db-4702-9f5a-9982b24514bf",
                accountId = AccountId("isolated-google-account"),
                sessionEpoch = 1,
                profileGeneration = 0,
                stage = AccountDeletionStage.PREPARED,
                serviceBinding = "isolated-v2-service-binding",
                receiptSecret = "isolated-startup-receipt-secret",
                subjectBinding = "isolated-startup-subject-binding",
                receiptVersion = 1,
                remoteState = AccountDeletionRemoteState.RESERVED,
                installationId = "isolated-startup-installation",
            )

        override suspend fun recoverAtStartup(): AccountDeletionResult {
            recoveries.incrementAndGet()
            return if (acknowledged || !hasDeletion) AccountDeletionResult.Idle
            else terminalResult ?: AccountDeletionResult.RetryRequired(progress)
        }

        override suspend fun retry(): AccountDeletionResult {
            retries.incrementAndGet()
            return retainResult(terminalResult ?: retryResult)
        }

        override suspend fun cancelUnactivated(): AccountDeletionResult {
            cancellations.incrementAndGet()
            cancellationGate?.await()
            return retainResult(terminalResult ?: cancelResult)
        }

        override suspend fun delete(request: AccountDeletionRequest): AccountDeletionResult {
            newDeletions.incrementAndGet()
            return AccountDeletionResult.Unavailable
        }

        override suspend fun pending(): AccountDeletionProgress? =
            progress.takeIf {
                hasDeletion && !acknowledged && (reportPendingProgress || terminalResult != null)
            }

        override suspend fun acknowledgeTerminalRecovery(
            expected: AccountDeletionProgress?
        ): Boolean {
            acknowledgments.incrementAndGet()
            if (!allowAcknowledgment || terminalResult == null || expected != progress) return false
            acknowledged = true
            return true
        }

        private fun retainResult(result: AccountDeletionResult): AccountDeletionResult {
            if (result is AccountDeletionResult.RetryRequired) progress = result.progress
            if (
                terminalResult == null &&
                    (result == AccountDeletionResult.Cancelled ||
                        result == AccountDeletionResult.Completed)
            ) {
                terminalResult = result
                progress =
                    progress.copy(
                        stage =
                            if (result == AccountDeletionResult.Cancelled)
                                AccountDeletionStage.CANCELLED
                            else AccountDeletionStage.COMPLETE,
                        remoteState =
                            if (result == AccountDeletionResult.Cancelled)
                                AccountDeletionRemoteState.CANCELLED_NO_DELETE
                            else AccountDeletionRemoteState.COMPLETE,
                        receiptVersion = progress.receiptVersion + 1,
                    )
            }
            return result
        }
    }

    class SessionReadControl {
        @Volatile var unreadable = false
        val clearCalls = AtomicInteger()
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object StartupGatewayModule {
        @Provides
        @Singleton
        fun gateway(
            sessions: FakeAccountSessionAdapter,
            control: SessionReadControl,
            localContext: AccountContextReader,
            installationGuard: InstallationGuard,
            localProfileResetter: LocalProfileResetter,
            operationGate: AccountSessionOperationGate,
            deletionManager: AccountDeletionManager,
            capabilities: AccountExperienceCapabilities,
        ): AccountGateway {
            val readableSession =
                object : AccountSessionAdapter by sessions {
                    override suspend fun readSession(): AccountProfile? {
                        if (control.unreadable) throw UnreadableAccountSessionException()
                        return sessions.readSession()
                    }

                    override suspend fun clearUnreadableSession(): Boolean {
                        control.clearCalls.incrementAndGet()
                        if (!control.unreadable || !sessions.clearSession()) return false
                        control.unreadable = false
                        return true
                    }
                }
            return PersistedAccountGateway(
                readableSession,
                localContext,
                installationGuard,
                localProfileResetter,
                operationGate,
                deletionManager,
                capabilities,
            )
        }
    }
}
