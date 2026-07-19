package com.example.ironpath.di

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.MainActivity
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import com.example.ironpath.testutil.HiltTestDatabaseRule
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.testing.TestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HiltStartupTest {
    @get:Rule(order = 0) val databaseRule = HiltTestDatabaseRule()

    @get:Rule(order = 1) val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var timeProvider: TimeProvider

    @Inject lateinit var idProvider: IdProvider

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun mainDestinations_resolveHiltGraphAndRender() {
        composeRule.onNodeWithText("GET STARTED").assertIsDisplayed().performClick()
        waitForText("No workout plan yet")

        clickNavigationDestination(Route.PLAN)
        waitForText("Primary Goal")

        clickNavigationDestination(Route.ACTIVE)
        waitForText("NO WORKOUT READY YET")

        clickNavigationDestination(Route.HISTORY)
        waitForText("No workout logs yet")

        clickNavigationDestination(Route.HOME)
        waitForText("No workout plan yet")
    }

    @Test
    fun productionGraph_resolvesDeterministicBoundaries() {
        assertTrue(::timeProvider.isInitialized)
        assertTrue(::idProvider.isInitialized)
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun clickNavigationDestination(route: String) {
        val tag = TestTags.bottomNav(route)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(tag).performClick()
    }
}
