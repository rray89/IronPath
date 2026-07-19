package com.example.ironpath.ui.screens.plan

import androidx.lifecycle.SavedStateHandle
import com.example.ironpath.domain.planner.DefaultExerciseCatalog
import com.example.ironpath.domain.planner.Equipment
import com.example.ironpath.domain.planner.ExerciseCatalogIds
import com.example.ironpath.domain.planner.ExerciseCautionTag
import com.example.ironpath.domain.planner.ExerciseDraft
import com.example.ironpath.domain.planner.PlanDraft
import com.example.ironpath.domain.planner.PlanValidator
import com.example.ironpath.domain.planner.PlanningEngine
import com.example.ironpath.domain.planner.PlanningEngineRegistry
import com.example.ironpath.domain.planner.PlanningEngineType
import com.example.ironpath.domain.planner.PlanningFailure
import com.example.ironpath.domain.planner.PlanningGoal
import com.example.ironpath.domain.planner.PlanningHistoryProvider
import com.example.ironpath.domain.planner.PlanningProviderMetadata
import com.example.ironpath.domain.planner.PlanningRequest
import com.example.ironpath.domain.planner.PlanningResult
import com.example.ironpath.domain.planner.RecentTrainingSummary
import com.example.ironpath.domain.planner.TrainingExperience
import com.example.ironpath.domain.planner.WorkoutDraft
import com.example.ironpath.testutil.FakeTimeProvider
import com.example.ironpath.util.MainDispatcherRule
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlannerIntakeViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val catalog = DefaultExerciseCatalog()
    private val timeProvider = FakeTimeProvider()
    private val historyProvider =
        object : PlanningHistoryProvider {
            override suspend fun loadRecent(today: java.time.LocalDate) =
                RecentTrainingSummary.EMPTY
        }

    @Test
    fun `intake defaults are local safe and AI availability follows the registry`() {
        val viewModel = createViewModel(engine = StaticEngine(validResult(setOf(1))))

        assertEquals(PlanningGoal.STRENGTH, viewModel.intakeState.value.goal)
        assertEquals(TrainingExperience.INTERMEDIATE, viewModel.intakeState.value.experience)
        assertEquals(Equipment.entries.toSet(), viewModel.intakeState.value.availableEquipment)
        assertTrue(viewModel.intakeState.value.selectedDays.isEmpty())
        assertTrue(viewModel.aiAvailable)
        assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Idle)
    }

    @Test
    fun `day selection caps at six and reports why the seventh was ignored`() {
        val viewModel = createViewModel(engine = StaticEngine(validResult(setOf(1))))
        (1..6).forEach(viewModel::toggleDay)

        viewModel.toggleDay(7)

        assertEquals((1..6).toSet(), viewModel.intakeState.value.selectedDays)
        assertEquals(
            "Choose up to six workout days so the week keeps a rest day.",
            viewModel.intakeState.value.daySelectionMessage,
        )
    }

    @Test
    fun `rule based eligibility does not depend on AI equipment constraints`() {
        val viewModel = createViewModel(engine = StaticEngine(validResult(setOf(1))))
        viewModel.toggleDay(1)
        Equipment.entries.forEach(viewModel::toggleEquipment)

        assertTrue(viewModel.intakeState.value.canGenerateRuleBased)
        assertTrue(!viewModel.intakeState.value.canGenerateWithAi)
    }

    @Test
    fun `saved state restores every user entered intake field but not generation progress`() =
        runTest {
            val handle = SavedStateHandle()
            val engine = StaticEngine(validResult(setOf(2, 5)))
            val original = createViewModel(handle, engine)
            original.setGoal(PlanningGoal.RETURN_TO_ROUTINE)
            original.toggleDay(2)
            original.toggleDay(5)
            original.setExperience(TrainingExperience.BEGINNER)
            original.toggleEquipment(Equipment.BARBELL)
            original.toggleCautionTag(ExerciseCautionTag.SHOULDER)
            original.setInjuryNotes("Previous shoulder irritation")
            original.setExercisePreferences("Prefer dumbbells")
            original.setExerciseDislikes("Avoid burpees")
            original.generateWithAi()
            runCurrent()

            val restored = createViewModel(handle, engine)

            assertEquals(PlanningGoal.RETURN_TO_ROUTINE, restored.intakeState.value.goal)
            assertEquals(setOf(2, 5), restored.intakeState.value.selectedDays)
            assertEquals(TrainingExperience.BEGINNER, restored.intakeState.value.experience)
            assertTrue(Equipment.BARBELL !in restored.intakeState.value.availableEquipment)
            assertEquals(
                setOf(ExerciseCautionTag.SHOULDER),
                restored.intakeState.value.forbiddenCautionTags,
            )
            assertEquals("Previous shoulder irritation", restored.intakeState.value.injuryNotes)
            assertEquals("Prefer dumbbells", restored.intakeState.value.exercisePreferences)
            assertEquals("Avoid burpees", restored.intakeState.value.exerciseDislikes)
            assertTrue(restored.aiGenerationState.value is AiGenerationUiState.Idle)
        }

    @Test
    fun `valid fake result transitions through loading to validated state`() = runTest {
        val engine = DeferredEngine()
        val viewModel = createViewModel(engine = engine)
        viewModel.toggleDay(1)

        viewModel.generateWithAi()
        assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Generating)
        engine.result.complete(validResult(setOf(1)))
        runCurrent()

        val state = viewModel.aiGenerationState.value as AiGenerationUiState.Validated
        assertEquals(
            PlanningEngineType.DEBUG_FAKE_AI,
            state.draft.draft.providerMetadata.engineType
        )
        assertEquals(setOf(1), state.draft.context.selectedDays)
    }

    @Test
    fun `provider failure exposes a retryable error`() = runTest {
        val failure = PlanningFailure.ProviderError("offline")
        val viewModel = createViewModel(engine = StaticEngine(PlanningResult.Failure(failure)))
        viewModel.toggleDay(1)

        viewModel.generateWithAi()
        runCurrent()

        val state = viewModel.aiGenerationState.value as AiGenerationUiState.Failed
        assertEquals(failure, state.failure)
    }

    @Test
    fun `changing intake invalidates a completed AI draft`() = runTest {
        val viewModel = createViewModel(engine = StaticEngine(validResult(setOf(1))))
        viewModel.toggleDay(1)
        viewModel.generateWithAi()
        runCurrent()
        assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Validated)

        viewModel.setGoal(PlanningGoal.HYPERTROPHY)

        assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Stale)
    }

    @Test
    fun `changing intake replaces stale validation violations with an explanation`() = runTest {
        val viewModel = createViewModel(engine = StaticEngine(validResult(emptySet())))
        viewModel.toggleDay(1)
        viewModel.generateWithAi()
        runCurrent()
        assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Invalid)

        viewModel.toggleEquipment(Equipment.BARBELL)

        assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Stale)
    }

    @Test
    fun `changing intake cancels generation for the previous snapshot`() = runTest {
        val engine = DeferredEngine()
        val viewModel = createViewModel(engine = engine)
        viewModel.toggleDay(1)
        viewModel.generateWithAi()
        assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Generating)

        viewModel.setInjuryNotes("Shoulder")
        engine.result.complete(validResult(setOf(1)))
        runCurrent()

        assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Stale)
    }

    @Test
    fun `cancel is ignored when no generation is running`() = runTest {
        val viewModel = createViewModel(engine = StaticEngine(validResult(setOf(1))))
        viewModel.toggleDay(1)
        viewModel.generateWithAi()
        runCurrent()
        val validated = viewModel.aiGenerationState.value

        viewModel.cancelGeneration()

        assertEquals(validated, viewModel.aiGenerationState.value)
    }

    @Test
    fun `replacement generation ignores a noncooperative late result`() = runTest {
        val engine = ReorderingEngine()
        val viewModel = createViewModel(engine = engine)
        viewModel.toggleDay(1)
        viewModel.generateWithAi()
        assertEquals(setOf(1), engine.started.receive().selectedDays)

        viewModel.toggleDay(1)
        viewModel.toggleDay(2)
        viewModel.generateWithAi()
        assertEquals(setOf(2), engine.started.receive().selectedDays)
        engine.second.complete(validResult(setOf(2)))
        runCurrent()
        assertEquals(
            setOf(2),
            (viewModel.aiGenerationState.value as AiGenerationUiState.Validated)
                .draft
                .context
                .selectedDays,
        )

        engine.first.complete(validResult(setOf(1)))
        runCurrent()

        assertEquals(
            setOf(2),
            (viewModel.aiGenerationState.value as AiGenerationUiState.Validated)
                .draft
                .context
                .selectedDays,
        )
    }

    private fun createViewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        engine: PlanningEngine,
    ) =
        PlannerIntakeViewModel(
            savedStateHandle = handle,
            planningEngineRegistry = PlanningEngineRegistry(mapOf(engine.type to engine)),
            planValidator = PlanValidator(catalog, timeProvider),
            planningHistoryProvider = historyProvider,
            timeProvider = timeProvider,
        )

    private fun validResult(days: Set<Int>): PlanningResult {
        val targetMonday = timeProvider.today().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        return PlanningResult.Success(
            PlanDraft(
                targetWeekStart = targetMonday,
                workouts =
                    days.sorted().map { day ->
                        WorkoutDraft(
                            dayOfWeek = day,
                            scheduledDate = targetMonday.plusDays((day - 1).toLong()),
                            title = "Workout $day",
                            exercises =
                                listOf(
                                    ExerciseDraft(
                                        catalogId = ExerciseCatalogIds.PLANK_HOLD,
                                        sets = 2,
                                        reps = 1,
                                        targetWeightKg = 0.0,
                                    )
                                ),
                        )
                    },
                providerMetadata = PlanningProviderMetadata(PlanningEngineType.DEBUG_FAKE_AI, 1),
            )
        )
    }

    private class StaticEngine(private val result: PlanningResult) : PlanningEngine {
        override val type = PlanningEngineType.DEBUG_FAKE_AI

        override suspend fun generate(request: PlanningRequest): PlanningResult = result
    }

    private class DeferredEngine : PlanningEngine {
        override val type = PlanningEngineType.DEBUG_FAKE_AI
        val result = CompletableDeferred<PlanningResult>()

        override suspend fun generate(request: PlanningRequest): PlanningResult = result.await()
    }

    private class ReorderingEngine : PlanningEngine {
        override val type = PlanningEngineType.DEBUG_FAKE_AI
        val started = Channel<PlanningRequest>(Channel.UNLIMITED)
        val first = CompletableDeferred<PlanningResult>()
        val second = CompletableDeferred<PlanningResult>()
        private var calls = 0

        override suspend fun generate(request: PlanningRequest): PlanningResult {
            started.send(request)
            val result = if (calls++ == 0) first else second
            return try {
                result.await()
            } catch (_: CancellationException) {
                withContext(NonCancellable) { result.await() }
            }
        }
    }
}
