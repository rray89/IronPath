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
import com.example.ironpath.domain.planner.ExerciseCatalog
import com.example.ironpath.domain.planner.OnDeviceModelClient
import com.example.ironpath.domain.planner.PlanValidator
import com.example.ironpath.domain.planner.PlanningEngineRegistry
import com.example.ironpath.domain.planner.PlanningEngineType
import com.example.ironpath.domain.planner.RemotePlanningExperiment
import com.example.ironpath.domain.planner.ValidatedPlanDraftMapper
import com.example.ironpath.domain.time.TimeProvider
import com.example.ironpath.testutil.HiltTestDatabaseRule
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.testing.TestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
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

    @Inject lateinit var exerciseCatalog: ExerciseCatalog

    @Inject lateinit var planningEngineRegistry: PlanningEngineRegistry

    @Inject lateinit var onDeviceModelClient: OnDeviceModelClient

    @Inject lateinit var planValidator: PlanValidator

    @Inject lateinit var validatedPlanDraftMapper: ValidatedPlanDraftMapper

    @Inject lateinit var remotePlanningExperiment: RemotePlanningExperiment

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
        assertTrue(::exerciseCatalog.isInitialized)
        assertTrue(exerciseCatalog.entries.isNotEmpty())
        assertTrue(::planningEngineRegistry.isInitialized)
        assertTrue(PlanningEngineType.RULE_BASED in planningEngineRegistry.availableTypes)
        assertTrue(PlanningEngineType.ON_DEVICE_AI in planningEngineRegistry.availableTypes)
        assertTrue(PlanningEngineType.DEBUG_FAKE_AI in planningEngineRegistry.availableTypes)
        assertTrue(PlanningEngineType.DEBUG_REMOTE_AI in planningEngineRegistry.availableTypes)
        assertTrue(::onDeviceModelClient.isInitialized)
        assertTrue(::planValidator.isInitialized)
        assertTrue(::validatedPlanDraftMapper.isInitialized)
        assertTrue(::remotePlanningExperiment.isInitialized)
        assertTrue(remotePlanningExperiment.state.value.available)
    }

    @Test
    fun onDeviceProvider_capabilityCheckCompletesWithoutStartingGeneration() = runBlocking {
        assertTrue(
            onDeviceModelClient.checkStatus() in
                com.example.ironpath.domain.planner.OnDeviceModelStatus.entries
        )
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
