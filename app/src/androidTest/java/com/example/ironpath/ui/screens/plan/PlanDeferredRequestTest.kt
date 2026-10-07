package com.example.ironpath.ui.screens.plan

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.backup.BackupChangeTracker
import com.example.ironpath.data.performance.PerformanceTracer
import com.example.ironpath.data.repository.*
import com.example.ironpath.domain.planner.*
import com.example.ironpath.testutil.*
import com.example.ironpath.ui.theme.IronPathTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanDeferredRequestTest {
    @get:Rule(order = 0) val databaseRule = RoomTestDatabaseRule()
    @get:Rule(order = 1) val composeRule = createComposeRule()

    @Test
    fun failedRuleSaveReleasesDeferredNextWeekRequestExactlyOnce() {
        val db = databaseRule.database
        val old = TestData.plan()
        val oldWorkout =
            TestData.workout(
                status = com.example.ironpath.data.local.entity.WorkoutStatus.Completed
            )
        runBlocking { db.planDao().createPlanWithWorkouts(old, listOf(oldWorkout), emptyList()) }
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val tracker = BackupChangeTracker {
            started.complete(Unit)
            finish.await()
            error("Synthetic transaction failure")
        }
        val clock =
            MutableTimeProvider(
                Instant.parse("2026-07-16T19:00:00Z"),
                ZoneId.of("America/Vancouver")
            )
        val ids = SequenceIdProvider("deferred")
        val catalog = DefaultExerciseCatalog()
        val eligibility = ExerciseEligibilityPolicy(catalog)
        val validator = PlanValidator(catalog, clock, eligibility)
        val experiment =
            object : RemotePlanningExperiment {
                override val state = MutableStateFlow(RemotePlanningExperimentState())

                override fun setEnabled(enabled: Boolean) = Unit

                override fun setApiKey(apiKey: String) = Unit
            }
        val handle = SavedStateHandle()
        val pair = runBlocking {
            withContext(Dispatchers.Main.immediate) {
                val mapper = PlanEntityMapper(ids, clock, catalog)
                val plan =
                    PlanViewModel(
                        planRepository = PlanRepository(db.planDao(), db, tracker),
                        planGenerator = PlanGenerator(clock, RuleBasedPlanFactory(catalog), mapper),
                        sessionRepository =
                            SessionRepository(
                                db.sessionDao(),
                                db.historyDao(),
                                db.planDao(),
                                db,
                                PerformanceTracer(),
                                tracker,
                                clock,
                                ids
                            ),
                        timeProvider = clock,
                        aiPlanReviewEditor = AiPlanReviewEditor(validator, eligibility),
                        validatedPlanDraftMapper = ValidatedPlanDraftMapper(mapper),
                        savedStateHandle = handle,
                        rulePlanReviewEditor = RulePlanReviewEditor(ids),
                        recordRepository = RecordRepository(db.recordDao(), db, tracker),
                        exerciseCatalog = catalog,
                        remotePlanningExperiment = experiment,
                    )
                val intake =
                    PlannerIntakeViewModel(
                        SavedStateHandle(),
                        AiPlanningCoordinator(
                            PlanningEngineRegistry(emptyMap()),
                            validator,
                            emptySet()
                        ),
                        object : PlanningHistoryProvider {
                            override suspend fun loadRecent(today: LocalDate) =
                                RecentTrainingSummary.EMPTY
                        },
                        clock,
                        experiment
                    )
                plan.generatePlan(PlanningGoal.STRENGTH, setOf(1))
                plan to intake
            }
        }
        var requested by mutableStateOf(false)
        var consumed = 0
        try {
            composeRule.setContent {
                IronPathTheme {
                    PlanScreen(
                        {},
                        {},
                        {},
                        viewModel = pair.first,
                        intakeViewModel = pair.second,
                        nextWeekRequested = requested,
                        onNextWeekRequestConsumed = {
                            consumed++
                            requested = false
                        }
                    )
                }
            }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("WEEKLY PLAN").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("ACCEPT PLAN").performScrollTo().performClick()
            composeRule.waitUntil(5_000) { started.isCompleted }
            composeRule.runOnIdle { requested = true }
            composeRule.waitForIdle()
            assertEquals(0, consumed)
            assertTrue(requested)
            finish.complete(Unit)
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("Primary Goal").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Primary Goal").assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals(1, consumed)
                assertFalse(requested)
            }
            assertEquals(old.id, handle.get<String>("next_week_setup_source"))
            runBlocking {
                assertEquals(old, db.planDao().getActivePlan())
                assertEquals(listOf(oldWorkout), db.planDao().getWorkoutsForPlan(old.id))
            }
        } finally {
            finish.complete(Unit)
            runBlocking {
                withContext(Dispatchers.Main.immediate) {
                    pair.first.viewModelScope.cancel()
                    pair.second.viewModelScope.cancel()
                }
            }
        }
    }
}
