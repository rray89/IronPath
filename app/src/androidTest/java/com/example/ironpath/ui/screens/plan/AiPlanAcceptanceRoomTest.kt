package com.example.ironpath.ui.screens.plan

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.backup.BackupChangeTracker
import com.example.ironpath.data.local.entity.PlanStatus
import com.example.ironpath.data.performance.PerformanceTracer
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.planner.AiPlanDraftReviewState
import com.example.ironpath.domain.planner.AiPlanReviewEditor
import com.example.ironpath.domain.planner.DefaultExerciseCatalog
import com.example.ironpath.domain.planner.Equipment
import com.example.ironpath.domain.planner.ExerciseCatalogIds
import com.example.ironpath.domain.planner.ExerciseDraft
import com.example.ironpath.domain.planner.ExerciseEligibilityPolicy
import com.example.ironpath.domain.planner.PlanDraft
import com.example.ironpath.domain.planner.PlanEntityMapper
import com.example.ironpath.domain.planner.PlanGenerator
import com.example.ironpath.domain.planner.PlanValidationContext
import com.example.ironpath.domain.planner.PlanValidationResult
import com.example.ironpath.domain.planner.PlanValidator
import com.example.ironpath.domain.planner.PlanViolationCode
import com.example.ironpath.domain.planner.PlanningEngineType
import com.example.ironpath.domain.planner.PlanningProviderMetadata
import com.example.ironpath.domain.planner.RuleBasedPlanFactory
import com.example.ironpath.domain.planner.TrainingExperience
import com.example.ironpath.domain.planner.ValidatedPlanDraft
import com.example.ironpath.domain.planner.ValidatedPlanDraftMapper
import com.example.ironpath.domain.planner.WorkoutDraft
import com.example.ironpath.testutil.MutableTimeProvider
import com.example.ironpath.testutil.RoomTestDatabaseRule
import com.example.ironpath.testutil.SequenceIdProvider
import com.example.ironpath.testutil.TestData
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production acceptance path using only a fresh in-memory database. */
@RunWith(AndroidJUnit4::class)
class AiPlanAcceptanceRoomTest {
    @get:Rule val databaseRule = RoomTestDatabaseRule()

    private val catalog = DefaultExerciseCatalog()
    private val clock =
        MutableTimeProvider(
            Instant.parse("2026-07-16T19:00:00Z"),
            ZoneId.of("America/Vancouver"),
        )
    private val eligibility = ExerciseEligibilityPolicy(catalog)
    private val validator = PlanValidator(catalog, clock, eligibility)
    private val previousPlan = TestData.plan()
    private val previousWorkout = TestData.workout()
    private val previousExercise = TestData.plannedExercise()

    @Test
    fun activeSessionBlocksAiAcceptanceWithoutChangingEitherPersistedGraph() = runBlocking {
        seedPreviousPlan()
        val session = TestData.session()
        databaseRule.database.sessionDao().insertSession(session)
        withViewModel { viewModel ->
            withContext(Dispatchers.Main.immediate) {
                viewModel.enterAiReview(validatedToken())
                viewModel.acceptPlan {}
            }
            awaitAcceptanceFinished(viewModel)
            assertEquals(
                "Finish the active workout before accepting a new plan.",
                viewModel.aiReviewState.value!!.saveError
            )
            assertPreviousGraphUnchanged()
            assertEquals(session, databaseRule.database.sessionDao().getActiveSession())
        }
    }

    @Test
    fun changedRemoteConfigurationPreventsOldNextWeekDraftFromWritingRoom() = runBlocking {
        seedPreviousPlan()
        val state =
            kotlinx.coroutines.flow.MutableStateFlow(
                com.example.ironpath.domain.planner.RemotePlanningExperimentState(revision = 1)
            )
        val experiment =
            object : com.example.ironpath.domain.planner.RemotePlanningExperiment {
                override val state = state

                override fun setEnabled(enabled: Boolean) = Unit

                override fun setApiKey(apiKey: String) = Unit
            }
        withViewModel(experiment = experiment) { viewModel ->
            withContext(Dispatchers.Main.immediate) {
                assertTrue(viewModel.enterAiReview(validatedToken(), 1))
                state.value = state.value.copy(revision = 2)
                viewModel.acceptPlan {}
                assertNull(viewModel.aiReviewState.value)
            }
            assertPreviousGraphUnchanged()
        }
    }

    @Test
    fun expiredDraftCannotArchivePreviousPlanOrInsertAnyDraftRows() = runBlocking {
        seedPreviousPlan()
        withViewModel { viewModel ->
            val token = validatedToken()
            var accepted = false
            withContext(Dispatchers.Main.immediate) {
                viewModel.enterAiReview(token)
                clock.setInstant(Instant.parse("2026-07-21T19:00:00Z"))
                viewModel.acceptPlan { accepted = true }
            }
            awaitAcceptanceFinished(viewModel)

            val state = requireNotNull(viewModel.aiReviewState.value)
            assertTrue(state.review is AiPlanDraftReviewState.Invalid)
            assertTrue(state.review.violations.any { it.code == PlanViolationCode.WORKOUT_IN_PAST })
            assertFalse(state.canAccept)
            assertFalse(accepted)
            assertPreviousGraphUnchanged()
        }
    }

    @Test
    fun editedValidDraftAndDuplicateClicksPersistOneCompleteReplacement() = runBlocking {
        seedPreviousPlan()
        withViewModel { viewModel ->
            var acceptanceCount = 0
            withContext(Dispatchers.Main.immediate) {
                viewModel.enterAiReview(validatedToken())
                viewModel.replaceAiExercise(
                    workoutDay = 1,
                    originalId = ExerciseCatalogIds.PUSH_UPS,
                    replacement = ExerciseDraft(ExerciseCatalogIds.PUSH_UPS, 3, 12, 0.0),
                )
                viewModel.acceptPlan { acceptanceCount += 1 }
                viewModel.acceptPlan { acceptanceCount += 1 }
            }
            awaitAcceptanceFinished(viewModel)
            withContext(Dispatchers.Main.immediate) {
                viewModel.acceptPlan { acceptanceCount += 1 }
            }

            val dao = databaseRule.database.planDao()
            val accepted = requireNotNull(dao.getActivePlan())
            assertEquals(1, acceptanceCount)
            assertNull(viewModel.aiReviewState.value)
            assertEquals(PlanStatus.Active, accepted.status)
            assertEquals("2026-07-20", accepted.startDate)
            assertEquals("2026-07-26", accepted.endDate)
            val workout = dao.getWorkoutsForPlan(accepted.id).single()
            assertEquals(1, workout.dayOfWeek)
            assertEquals("2026-07-20", workout.scheduledDate)
            val exercise = dao.getExercisesForWorkout(workout.id).single()
            assertEquals("Push-ups", exercise.name)
            assertEquals(12, exercise.reps)
            assertEquals(3, exercise.sets)
            assertEquals(0.0, exercise.weightKg, 0.0)
            assertEquals(2, rowCount("weekly_plans"))
            assertEquals(2, rowCount("planned_workouts"))
            assertEquals(2, rowCount("planned_exercises"))
            assertEquals("Archived", previousPlanStatus())
            assertEquals(previousWorkout, dao.getWorkoutById(previousWorkout.id))
            assertEquals(listOf(previousExercise), dao.getExercisesForWorkout(previousWorkout.id))
        }
    }

    @Test
    fun failedTransactionRollsBackAndExpiredRetryLeavesPreviousPlanIntact() = runBlocking {
        seedPreviousPlan()
        var writeAttempts = 0
        withViewModel(
            BackupChangeTracker {
                writeAttempts += 1
                error("Injected transaction failure")
            }
        ) { viewModel ->
            withContext(Dispatchers.Main.immediate) {
                viewModel.enterAiReview(validatedToken())
                viewModel.acceptPlan {}
            }
            awaitAcceptanceFinished(viewModel)
            assertNotNull(viewModel.aiReviewState.value!!.saveError)
            assertPreviousGraphUnchanged()

            withContext(Dispatchers.Main.immediate) {
                clock.setInstant(Instant.parse("2026-07-21T19:00:00Z"))
                viewModel.acceptPlan {}
            }
            awaitAcceptanceFinished(viewModel)
            assertEquals(1, writeAttempts)
            assertNull(viewModel.aiReviewState.value!!.saveError)
            assertTrue(
                viewModel.aiReviewState.value!!.review.violations.any {
                    it.code == PlanViolationCode.WORKOUT_IN_PAST
                }
            )
            assertPreviousGraphUnchanged()
        }
    }

    private suspend fun withViewModel(
        tracker: BackupChangeTracker = BackupChangeTracker {},
        experiment: com.example.ironpath.domain.planner.RemotePlanningExperiment? = null,
        block: suspend (PlanViewModel) -> Unit,
    ) {
        val database = databaseRule.database
        val mapper = PlanEntityMapper(SequenceIdProvider("accepted-plan"), clock, catalog)
        val viewModel =
            withContext(Dispatchers.Main.immediate) {
                PlanViewModel(
                    planRepository = PlanRepository(database.planDao(), database, tracker),
                    planGenerator = PlanGenerator(clock, RuleBasedPlanFactory(catalog), mapper),
                    sessionRepository =
                        SessionRepository(
                            database.sessionDao(),
                            database.historyDao(),
                            database.planDao(),
                            database,
                            PerformanceTracer(),
                            tracker,
                            clock,
                            SequenceIdProvider("ai-session"),
                        ),
                    rulePlanReviewEditor =
                        com.example.ironpath.domain.planner.RulePlanReviewEditor(
                            SequenceIdProvider("rule-review")
                        ),
                    recordRepository =
                        com.example.ironpath.data.repository.RecordRepository(
                            database.recordDao(),
                            database,
                            tracker
                        ),
                    exerciseCatalog = catalog,
                    savedStateHandle = androidx.lifecycle.SavedStateHandle(),
                    remotePlanningExperiment = experiment,
                    timeProvider = clock,
                    aiPlanReviewEditor = AiPlanReviewEditor(validator, eligibility),
                    validatedPlanDraftMapper = ValidatedPlanDraftMapper(mapper),
                )
            }
        try {
            block(viewModel)
        } finally {
            withContext(Dispatchers.Main.immediate) { viewModel.viewModelScope.cancel() }
        }
    }

    private suspend fun awaitAcceptanceFinished(viewModel: PlanViewModel) {
        withTimeout(5_000) { viewModel.aiReviewState.first { it?.isAccepting != true } }
    }

    private suspend fun seedPreviousPlan() {
        databaseRule.database
            .planDao()
            .createPlanWithWorkouts(
                previousPlan,
                listOf(previousWorkout),
                listOf(previousExercise),
            )
    }

    private suspend fun assertPreviousGraphUnchanged() {
        val dao = databaseRule.database.planDao()
        assertEquals(previousPlan, dao.getActivePlan())
        assertEquals("Active", previousPlanStatus())
        assertEquals(listOf(previousWorkout), dao.getWorkoutsForPlan(previousPlan.id))
        assertEquals(listOf(previousExercise), dao.getExercisesForWorkout(previousWorkout.id))
        assertEquals(1, rowCount("weekly_plans"))
        assertEquals(1, rowCount("planned_workouts"))
        assertEquals(1, rowCount("planned_exercises"))
    }

    private fun rowCount(table: String): Int {
        check(table in setOf("weekly_plans", "planned_workouts", "planned_exercises"))
        return databaseRule.database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table")
            .use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
    }

    private fun previousPlanStatus(): String =
        databaseRule.database.openHelper.readableDatabase
            .query("SELECT status FROM weekly_plans WHERE id = ?", arrayOf(previousPlan.id))
            .use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }

    private fun validatedToken(): ValidatedPlanDraft {
        val monday = LocalDate.parse("2026-07-20")
        val context =
            PlanValidationContext(
                expectedTargetWeekStart = monday,
                invokedEngineType = PlanningEngineType.DEBUG_FAKE_AI,
                selectedDays = setOf(1),
                experience = TrainingExperience.BEGINNER,
                availableEquipment = setOf(Equipment.BODYWEIGHT),
            )
        val draft =
            PlanDraft(
                targetWeekStart = monday,
                workouts =
                    listOf(
                        WorkoutDraft(
                            dayOfWeek = 1,
                            scheduledDate = monday,
                            title = "Full body",
                            exercises =
                                listOf(ExerciseDraft(ExerciseCatalogIds.PUSH_UPS, 3, 10, 0.0)),
                        )
                    ),
                providerMetadata = PlanningProviderMetadata(PlanningEngineType.DEBUG_FAKE_AI, 0),
            )
        return (validator.validate(draft, context) as PlanValidationResult.Valid).validatedPlan
    }
}
