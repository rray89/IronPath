package com.example.ironpath.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.MainActivity
import com.example.ironpath.di.DebugAccountDeletionModule
import com.example.ironpath.domain.account.AccountDeletionManager
import com.example.ironpath.domain.account.AccountDeletionProgress
import com.example.ironpath.domain.account.AccountDeletionRequest
import com.example.ironpath.domain.account.AccountDeletionResult
import com.example.ironpath.domain.account.AccountDeletionStage
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.testutil.AccountFilesUnchangedRule
import com.example.ironpath.testutil.HiltTestDatabaseRule
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.testing.TestTags
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@UninstallModules(DebugAccountDeletionModule::class)
@RunWith(AndroidJUnit4::class)
class AccountDeletionStartupRetryTest {
    @get:Rule(order = 0) val accountFilesRule = AccountFilesUnchangedRule()
    @get:Rule(order = 1) val databaseRule = HiltTestDatabaseRule()
    @get:Rule(order = 2) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 3) val composeRule = createAndroidComposeRule<MainActivity>()

    private val deletion = ExplicitRetryDeletionManager()
    @BindValue @JvmField val accountDeletionManager: AccountDeletionManager = deletion

    @Before fun inject() = hiltRule.inject()

    @Test
    fun startupAndRecreationOnlyRecover_userRetryCompletesAndOpensLocalApp() {
        waitForText("Finishing account deletion")
        val initialRecoveries = deletion.recoveries.get()
        assertTrue(initialRecoveries > 0)
        assertEquals(0, deletion.retries.get())
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").assertDoesNotExist()
        composeRule.onNodeWithText("No workout plan yet").assertDoesNotExist()

        composeRule.activityRule.scenario.recreate()
        waitForText("Finishing account deletion")
        assertTrue(deletion.recoveries.get() > initialRecoveries)
        assertEquals(0, deletion.retries.get())
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").assertDoesNotExist()

        composeRule.onNodeWithText("RETRY DELETION").performClick()
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
        @Volatile private var completed = false
        @Volatile var retryResult: AccountDeletionResult = AccountDeletionResult.Completed
        @Volatile var reportPendingProgress = true
        private val progress =
            AccountDeletionProgress(
                operationId = "startup-explicit-retry",
                accountId = AccountId("isolated-google-account"),
                sessionEpoch = 1,
                profileGeneration = 0,
                stage = AccountDeletionStage.PREPARED,
                serviceBinding = "isolated-service-binding",
            )

        override suspend fun recoverAtStartup(): AccountDeletionResult {
            recoveries.incrementAndGet()
            return if (completed) AccountDeletionResult.Idle
            else AccountDeletionResult.RetryRequired(progress)
        }

        override suspend fun retry(): AccountDeletionResult {
            retries.incrementAndGet()
            val result = retryResult
            if (result == AccountDeletionResult.Completed) completed = true
            return result
        }

        override suspend fun delete(request: AccountDeletionRequest): AccountDeletionResult {
            newDeletions.incrementAndGet()
            return AccountDeletionResult.Unavailable
        }

        override suspend fun pending(): AccountDeletionProgress? =
            progress.takeIf { !completed && reportPendingProgress }
    }
}
