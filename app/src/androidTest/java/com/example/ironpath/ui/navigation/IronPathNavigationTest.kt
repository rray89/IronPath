package com.example.ironpath.ui.navigation

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.IronPathApp
import com.example.ironpath.MainActivity
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.dao.PlanDao
import com.example.ironpath.data.local.dao.SessionDao
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.domain.time.TimeProvider
import com.example.ironpath.testutil.FakeOnboardingRepository
import com.example.ironpath.testutil.HiltTestDatabaseRule
import com.example.ironpath.testutil.TestData
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class IronPathNavigationTest {
    @get:Rule(order = 0) val databaseRule = HiltTestDatabaseRule()

    @get:Rule(order = 1) val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var timeProvider: TimeProvider

    @Inject lateinit var onboardingRepository: FakeOnboardingRepository

    @Inject lateinit var planDao: PlanDao

    @Inject lateinit var sessionDao: SessionDao

    @Inject lateinit var historyDao: HistoryDao

    private lateinit var navController: TestNavHostController

    @Before
    fun setUp() {
        hiltRule.inject()
        navController = TestNavHostController(composeRule.activity)
        composeRule.runOnUiThread {
            navController.navigatorProvider.addNavigator(ComposeNavigator())
            composeRule.activity.setContent {
                IronPathTheme {
                    IronPathApp(
                        timeProvider = timeProvider,
                        navController = navController,
                        onCompleteOnboarding = onboardingRepository::complete,
                    )
                }
            }
        }
        waitForRoute(Route.ENTRY)
    }

    @Test
    fun getStarted_navigatesToHomeAndRemovesEntryFromBackStack() {
        assertEquals(Route.ENTRY, currentRoute())
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").assertIsDisplayed()

        enterApp()

        assertEquals(Route.HOME, currentRoute())
        assertFalse(backStackRoutes().contains(Route.ENTRY))
        assertSingleBackStackEntry(Route.HOME)
        assertTrue(onboardingRepository.completed)
        assertEquals(1, onboardingRepository.completionCount)
    }

    @Test
    fun rememberedOnboarding_coldActivityRecreationStartsAtHomeWithoutEntry() {
        enterApp()

        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithTag(TestTags.bottomNav(Route.HOME))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HOME)).assertIsSelected()
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").assertDoesNotExist()
    }

    @Test
    fun failedOnboardingWrite_staysOnEntryAndAllowsRetry() {
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                IronPathTheme {
                    IronPathApp(
                        timeProvider = timeProvider,
                        navController = navController,
                        onCompleteOnboarding = { false },
                    )
                }
            }
        }

        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").performClick()

        waitForRoute(Route.ENTRY)
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").assertIsDisplayed()
    }

    @Test
    fun recreationDuringOnboardingCompletion_restoresAnActionableEntryButton() {
        val completionStarted = CompletableDeferred<Unit>()
        val releaseCompletion = CompletableDeferred<Unit>()
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                IronPathTheme {
                    IronPathApp(
                        timeProvider = timeProvider,
                        navController = navController,
                        onCompleteOnboarding = {
                            completionStarted.complete(Unit)
                            releaseCompletion.await()
                            true
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { completionStarted.isCompleted }
        composeRule.onNodeWithText("CONTINUING…").assertIsNotEnabled()

        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithText("CONTINUE ON THIS DEVICE")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").assertIsEnabled().performClick()
        waitForTag(TestTags.bottomNav(Route.HOME))
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HOME)).assertIsSelected()
        assertEquals(1, onboardingRepository.completionCount)
    }

    @Test
    fun drawer_showsLocalStorageTruthAndNavigatesToSecondaryDestinationsInOrder() {
        enterApp()

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("LOCAL PROFILE").assertIsDisplayed()

        val headerY =
            composeRule.onNodeWithText("LOCAL PROFILE").fetchSemanticsNode().positionInRoot.y
        val manualY = composeRule.onNodeWithText("Manual").fetchSemanticsNode().positionInRoot.y
        val privacyY =
            composeRule.onNodeWithText("AI & Privacy").fetchSemanticsNode().positionInRoot.y
        val aboutY =
            composeRule.onNodeWithText("About IronPath").fetchSemanticsNode().positionInRoot.y
        assertTrue(headerY < manualY)
        assertTrue(manualY < privacyY)
        assertTrue(privacyY < aboutY)
        composeRule
            .onNodeWithText("Your training data is already saved on this device.")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "IronPath cloud backup is not available in this version. " +
                    "Android device-to-device transfer may copy it to a new phone during setup."
            )
            .assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertDoesNotExist()

        composeRule.onNodeWithText("Manual").performClick()
        waitForRoute(Route.MANUAL)
        composeRule
            .onNodeWithContentDescription("Back")
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertMinimumTouchTarget("Shared Back")
        composeRule.onNodeWithContentDescription("Menu").assertDoesNotExist()
        assertBottomBarDoesNotExist()

        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForRoute(Route.HOME)

        navigateFromDrawer("AI & Privacy", Route.AI_PRIVACY)
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForRoute(Route.HOME)

        navigateFromDrawer("About IronPath", Route.ABOUT)
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForRoute(Route.HOME)
    }

    @Test
    fun systemBack_closesOpenDrawerBeforeLeavingActiveAndPreservesSessionGraph() {
        val workoutId = "workout-drawer-safety"
        val sessionId = "session-drawer-safety"
        val exerciseId = "exercise-drawer-safety"
        val setId = "set-drawer-safety"
        seedActivePlan(workoutId = workoutId, title = "Drawer Safety")
        seedActiveSession(
            workoutId = workoutId,
            title = "Drawer Safety",
            sessionId = sessionId,
            exerciseId = exerciseId,
            setId = setId,
        )
        enterApp()
        navigateToBottomDestination(Route.ACTIVE)

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("LOCAL PROFILE").assertIsDisplayed()
        Espresso.pressBack()
        composeRule.waitForIdle()
        assertEquals(Route.ACTIVE, currentRoute())
        composeRule.onNodeWithText("LOCAL PROFILE").assertIsNotDisplayed()

        composeRule.onNodeWithContentDescription("Menu").assertIsDisplayed()
        runBlocking {
            assertEquals(sessionId, sessionDao.getActiveSession()?.id)
            assertEquals(
                listOf(exerciseId),
                sessionDao.getExercisesForSession(sessionId).map { it.id },
            )
            assertEquals(
                listOf(setId),
                sessionDao.getSetsForExercises(listOf(exerciseId)).map { it.id },
            )
        }
    }

    @Test
    fun scrimDismissAction_closesDrawerWithoutChangingTheCurrentDestination() {
        enterApp()

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("LOCAL PROFILE").assertIsDisplayed()
        composeRule
            .onNodeWithTag(TestTags.APP_CONTENT)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
        composeRule
            .onNodeWithContentDescription("Close navigation menu")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals(Route.HOME, currentRoute())
        composeRule.onNodeWithText("LOCAL PROFILE").assertIsNotDisplayed()
        composeRule
            .onNodeWithTag(TestTags.APP_CONTENT)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onNodeWithContentDescription("Menu")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Focused) == true
        }
        composeRule.onNodeWithContentDescription("Menu").assertIsDisplayed().assertIsFocused()
    }

    @Test
    fun manualRoundTrip_preservesPlannerSelectionAndReturnsToPlan() {
        enterApp()
        navigateToBottomDestination(Route.PLAN)
        waitForTag(TestTags.planDay(1))
        composeRule.onNodeWithTag(TestTags.planDay(1)).performClick().assertIsOn()

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("Manual").assertIsDisplayed().performClick()
        waitForRoute(Route.MANUAL)
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForRoute(Route.PLAN)

        composeRule.onNodeWithTag(TestTags.planDay(1)).assertIsOn()
    }

    @Test
    fun bottomNavigation_tracksSelectionAndDoesNotDuplicateDestinations() {
        enterApp()
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HOME)).assertIsSelected()

        navigateToBottomDestination(Route.PLAN)
        waitForTag(TestTags.planDay(1))
        composeRule.onNodeWithTag(TestTags.planDay(1)).performClick().assertIsOn()
        repeat(3) {
            composeRule.onNodeWithTag(TestTags.bottomNav(Route.PLAN)).performClick()
            waitForRoute(Route.PLAN)
        }
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.PLAN)).assertIsSelected()
        assertSingleBackStackEntry(Route.PLAN)

        repeat(3) {
            composeRule.onNodeWithTag(TestTags.bottomNav(Route.ACTIVE)).performClick()
            waitForRoute(Route.ACTIVE)
        }
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.ACTIVE)).assertIsSelected()
        assertSingleBackStackEntry(Route.ACTIVE)

        navigateToBottomDestination(Route.HISTORY)
        waitForText("No workout logs yet")
        composeRule.onNodeWithText("RECORDS").performClick()
        waitForText("No records yet")
        repeat(3) {
            composeRule.onNodeWithTag(TestTags.bottomNav(Route.HISTORY)).performClick()
            waitForRoute(Route.HISTORY)
        }
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HISTORY)).assertIsSelected()
        assertSingleBackStackEntry(Route.HISTORY)

        repeat(3) {
            composeRule.onNodeWithTag(TestTags.bottomNav(Route.HOME)).performClick()
            waitForRoute(Route.HOME)
        }
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HOME)).assertIsSelected()
        assertSingleBackStackEntry(Route.HOME)

        navigateToBottomDestination(Route.PLAN)
        waitForTag(TestTags.planDay(1))
        composeRule.onNodeWithTag(TestTags.planDay(1)).assertIsOn()
        assertSingleBackStackEntry(Route.PLAN)

        navigateToBottomDestination(Route.HISTORY)
        waitForText("No records yet")
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HISTORY)).assertIsSelected()
        assertSingleBackStackEntry(Route.HISTORY)
    }

    @Test
    fun workoutPreview_specialCharacterIdRoundTripsAndBackReturnsHome() {
        val workoutId = "workout / A?set=1&day=#monday"
        seedActivePlan(workoutId = workoutId, title = "Encoded Workout")
        enterApp()

        waitForTag(TestTags.workout(workoutId))
        composeRule.onNodeWithTag(TestTags.workout(workoutId)).performScrollTo().performClick()
        waitForRoute(Route.WORKOUT_PREVIEW)

        assertEquals(
            workoutId,
            composeRule.runOnIdle {
                navController.currentBackStackEntry?.arguments?.getString(Route.WORKOUT_ID_ARG)
            },
        )
        waitForText("Encoded Workout")
        composeRule.onNodeWithText("Encoded Workout").assertIsDisplayed()
        assertBottomBarDoesNotExist()
        assertEquals(
            1,
            composeRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().size,
        )

        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForRoute(Route.HOME)
        composeRule.onNodeWithTag(TestTags.workout(workoutId)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun workoutLogDetail_specialCharacterIdRoundTripsWithoutReadOnlyArgumentAndBackReturnsHistory() {
        val logId = "log / A?source=manual&day=#monday"
        runBlocking {
            historyDao.insertLog(
                TestData.log(
                    id = logId,
                    title = "Encoded Log",
                    workoutId = null,
                    completedAt = timeProvider.epochMillis(),
                ),
            )
        }
        enterApp()
        navigateToBottomDestination(Route.HISTORY)

        waitForTag(TestTags.log(logId))
        composeRule.onNodeWithTag(TestTags.log(logId)).performScrollTo().performClick()
        waitForRoute(Route.WORKOUT_LOG_DETAIL)

        composeRule.runOnIdle {
            val arguments = navController.currentBackStackEntry?.arguments
            assertEquals(logId, arguments?.getString(Route.WORKOUT_LOG_ID_ARG))
            assertFalse(arguments?.containsKey("readOnly") == true)
        }
        waitForText("Encoded Log")
        composeRule.onNodeWithText("Encoded Log").assertIsDisplayed()
        assertBottomBarDoesNotExist()
        assertEquals(
            1,
            composeRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().size,
        )

        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForRoute(Route.HISTORY)
        composeRule.onNodeWithTag(TestTags.log(logId)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun completingActiveWorkout_returnsHomeClearsSessionAndRemovesActiveFromBackStack() {
        val workoutId = "workout-complete-navigation"
        seedActivePlan(workoutId = workoutId, title = "Completion Navigation")
        seedActiveSession(workoutId = workoutId, title = "Completion Navigation")
        enterApp()
        navigateToBottomDestination(Route.ACTIVE)

        waitForText("COMPLETION NAVIGATION")
        composeRule.onNodeWithText("COMPLETION NAVIGATION").assertIsDisplayed()
        composeRule.onNodeWithText("COMPLETE WORKOUT").performScrollTo().performClick()
        waitForRoute(Route.HOME)

        runBlocking {
            assertNull(sessionDao.getActiveSession())
            assertEquals(WorkoutStatus.Completed, planDao.getWorkoutById(workoutId)?.status)
        }
        assertFalse(backStackRoutes().contains(Route.ACTIVE))
        assertSingleBackStackEntry(Route.HOME)
    }

    @Test
    fun systemBackFromActive_returnsHomeAndPreservesTheSessionGraph() {
        val workoutId = "workout-preserve-navigation"
        val sessionId = "session-preserve-navigation"
        val exerciseId = "session-exercise-preserve-navigation"
        val setId = "session-set-preserve-navigation"
        seedActivePlan(workoutId = workoutId, title = "Persistent Navigation")
        seedActiveSession(
            workoutId = workoutId,
            title = "Persistent Navigation",
            sessionId = sessionId,
            exerciseId = exerciseId,
            setId = setId,
        )
        enterApp()
        navigateToBottomDestination(Route.ACTIVE)
        waitForText("PERSISTENT NAVIGATION")
        composeRule.onNodeWithText("PERSISTENT NAVIGATION").assertIsDisplayed()

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForRoute(Route.HOME)

        runBlocking {
            assertEquals(sessionId, sessionDao.getActiveSession()?.id)
            assertEquals(
                listOf(exerciseId),
                sessionDao.getExercisesForSession(sessionId).map { it.id },
            )
            assertEquals(
                listOf(setId),
                sessionDao.getSetsForExercises(listOf(exerciseId)).map { it.id },
            )
        }

        navigateToBottomDestination(Route.ACTIVE)
        waitForText("PERSISTENT NAVIGATION")
        composeRule.onNodeWithText("PERSISTENT NAVIGATION").assertIsDisplayed()
    }

    @Test
    fun entryAndDevTools_hideApplicationBars() {
        waitForBarsHidden()
        assertApplicationBarsDoNotExist()

        enterApp()
        composeRule.onNodeWithContentDescription("Menu").assertIsDisplayed()
        composeRule
            .onNodeWithTag(TestTags.bottomNav(Route.HOME))
            .assertIsDisplayed()
            .assertIsSelected()

        repeat(5) { composeRule.onNodeWithText("IRONPATH").performClick() }
        waitForRoute(Route.DEV_TOOLS)
        waitForBarsHidden()

        waitForText("DEV TOOLS")
        composeRule.onNodeWithText("DEV TOOLS").assertIsDisplayed()
        assertApplicationBarsDoNotExist()
    }

    private fun enterApp() {
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").performClick()
        waitForRoute(Route.HOME)
        waitForTagToDisappear(TestTags.HOME_LOADING)
        waitForBarsVisible()
    }

    private fun navigateToBottomDestination(route: String) {
        val tag = TestTags.bottomNav(route)
        waitForTag(tag)
        composeRule.onNodeWithTag(tag).performClick()
        waitForRoute(route)
    }

    private fun navigateFromDrawer(label: String, route: String) {
        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText(label).assertIsDisplayed()
        composeRule.onNodeWithText(label).performClick()
        waitForRoute(route)
        assertBottomBarDoesNotExist()
    }

    private fun seedActivePlan(workoutId: String, title: String) {
        runBlocking {
            planDao.createPlanWithWorkouts(
                plan = TestData.plan(id = "plan-navigation"),
                workouts =
                    listOf(
                        TestData.workout(
                            id = workoutId,
                            planId = "plan-navigation",
                            scheduledDate = "2026-07-13",
                            title = title,
                        ),
                    ),
                exercises =
                    listOf(
                        TestData.plannedExercise(
                            id = "planned-exercise-navigation",
                            workoutId = workoutId,
                        ),
                    ),
            )
        }
    }

    private fun seedActiveSession(
        workoutId: String,
        title: String,
        sessionId: String = "session-navigation",
        exerciseId: String = "session-exercise-navigation",
        setId: String = "session-set-navigation",
    ) {
        val now = timeProvider.epochMillis()
        runBlocking {
            sessionDao.startNewSession(
                session =
                    TestData.session(
                        id = sessionId,
                        workoutId = workoutId,
                        title = title,
                        startedAt = now - 60_000,
                        lastUpdatedAt = now,
                    ),
                exercises =
                    listOf(
                        TestData.sessionExercise(
                            id = exerciseId,
                            sessionId = sessionId,
                        ),
                    ),
            )
            sessionDao.insertSet(
                TestData.sessionSet(
                    id = setId,
                    exerciseId = exerciseId,
                    reps = 5,
                    weightKg = 100.0,
                    completedAt = now,
                ),
            )
        }
    }

    private fun currentRoute(): String? =
        composeRule.runOnIdle { navController.currentDestination?.route }

    private fun backStackRoutes(): List<String?> =
        composeRule.runOnIdle { navController.backStack.map { it.destination.route } }

    private fun assertSingleBackStackEntry(route: String) {
        assertEquals(1, backStackRoutes().count { it == route })
    }

    private fun waitForRoute(route: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            navController.currentDestination?.route == route
        }
        assertEquals(route, currentRoute())
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTagToDisappear(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForBarsHidden() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("Menu").fetchSemanticsNodes().isEmpty() &&
                BOTTOM_NAV_ROUTES.all { route ->
                    composeRule
                        .onAllNodesWithTag(TestTags.bottomNav(route))
                        .fetchSemanticsNodes()
                        .isEmpty()
                }
        }
    }

    private fun waitForBarsVisible() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithContentDescription("Menu")
                .fetchSemanticsNodes()
                .isNotEmpty() &&
                BOTTOM_NAV_ROUTES.all { route ->
                    composeRule
                        .onAllNodesWithTag(TestTags.bottomNav(route))
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
        }
    }

    private fun assertApplicationBarsDoNotExist() {
        composeRule.onNodeWithContentDescription("Menu").assertDoesNotExist()
        BOTTOM_NAV_ROUTES.forEach { route ->
            composeRule.onNodeWithTag(TestTags.bottomNav(route)).assertDoesNotExist()
        }
    }

    private fun assertBottomBarDoesNotExist() {
        BOTTOM_NAV_ROUTES.forEach { route ->
            composeRule.onNodeWithTag(TestTags.bottomNav(route)).assertDoesNotExist()
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertMinimumTouchTarget(
        label: String,
    ): androidx.compose.ui.test.SemanticsNodeInteraction {
        val node = fetchSemanticsNode()
        val bounds = node.touchBoundsInRoot
        val minimumPx = with(node.layoutInfo.density) { 48.dp.toPx() }
        assertTrue(
            "$label touch target was ${bounds.width}x${bounds.height}px; expected at least " +
                "${minimumPx}x${minimumPx}px",
            bounds.width >= minimumPx && bounds.height >= minimumPx,
        )
        return this
    }

    private companion object {
        val BOTTOM_NAV_ROUTES = listOf(Route.HOME, Route.PLAN, Route.ACTIVE, Route.HISTORY)
    }
}
