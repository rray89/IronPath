package com.example.ironpath.ui.screens.plan

import androidx.lifecycle.SavedStateHandle
import com.example.ironpath.domain.planner.AiPlanningCandidate
import com.example.ironpath.domain.planner.AiPlanningCoordinator
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
import com.example.ironpath.domain.planner.RemotePlanningExperiment
import com.example.ironpath.domain.planner.RemotePlanningExperimentState
import com.example.ironpath.domain.planner.RemotePlanningOption
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun `remote experiment settings stay outside saved state`() {
        val handle = SavedStateHandle()
        val experiment = FakeRemotePlanningExperiment()
        val engine = StaticEngine(validResult(setOf(1)))
        val viewModel = createViewModel(handle, engine, experiment)

        viewModel.setRemotePlanningApiKey("secret-key")
        viewModel.setRemotePlanningEnabled(true)

        assertTrue(viewModel.remotePlanningExperimentState.value.configured)
        assertFalse(
            handle.keys().any { key -> handle.get<Any?>(key).toString().contains("secret-key") }
        )

        val recreated = createViewModel(handle, engine, FakeRemotePlanningExperiment())
        assertEquals("", recreated.remotePlanningExperimentState.value.apiKey)
        assertFalse(recreated.remotePlanningExperimentState.value.configured)
    }

    @Test
    fun `changing remote key invalidates completed generation`() = runTest {
        val viewModel = createViewModel(engine = StaticEngine(validResult(setOf(1))))
        viewModel.toggleDay(1)
        viewModel.generateWithAi()
        runCurrent()
        assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Validated)

        viewModel.setRemotePlanningApiKey("replacement-key")
        runCurrent()

        assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Stale)
    }

    @Test
    fun `disabling remote ignores a late uncancellable result`() = runTest {
        val engine = ReorderingEngine()
        val viewModel = createViewModel(engine = engine)
        viewModel.setRemotePlanningEnabled(true)
        viewModel.toggleDay(1)
        viewModel.generateWithAi()
        runCurrent()
        viewModel.setRemotePlanningEnabled(false)
        engine.first.complete(validResult(setOf(1)))
        runCurrent()
        assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Stale)
    }

    @Test
    fun `switching remote option invalidates a completed draft synchronously`() = runTest {
        val experiment = configuredExperiment()
        val viewModel =
            createViewModel(
                engine = StaticEngine(validResult(setOf(1))),
                remotePlanningExperiment = experiment,
            )
        viewModel.toggleDay(1)
        viewModel.generateWithAi()
        runCurrent()
        val draft = viewModel.validatedDraft!!

        viewModel.setRemotePlanningOption("DEEPSEEK")

        assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Stale)
        assertNull(viewModel.validatedDraft)
        assertFalse(viewModel.onDraftConsumed(draft))
        assertEquals("DEEPSEEK", experiment.state.value.optionId)
        assertEquals("", experiment.state.value.apiKey)
        assertFalse(experiment.state.value.enabled)
    }

    @Test
    fun `route key and disable changes cannot replace a newer draft with a late response`() =
        runTest {
            val changes: List<(PlannerIntakeViewModel) -> Unit> =
                listOf(
                    { it.setRemotePlanningOption("DEEPSEEK") },
                    { it.setRemotePlanningApiKey("replacement-synthetic-key") },
                    { it.setRemotePlanningEnabled(false) },
                )
            changes.forEach { change ->
                val engine = ReorderingEngine()
                val experiment = configuredExperiment()
                val viewModel =
                    createViewModel(engine = engine, remotePlanningExperiment = experiment)
                viewModel.toggleDay(1)
                viewModel.generateWithAi()
                engine.started.receive()

                change(viewModel)
                assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Stale)
                viewModel.generateWithAi()
                engine.started.receive()
                engine.second.complete(validResult(setOf(1)))
                runCurrent()
                val latest = viewModel.validatedDraft!!
                assertEquals(2, engine.calls)

                engine.first.complete(validResult(setOf(1)))
                runCurrent()

                assertSame(latest, viewModel.validatedDraft)
                assertEquals(2, engine.calls)
            }
        }

    @Test
    fun `external experiment changes invalidate a completed draft through observation`() = runTest {
        val experiment = configuredExperiment()
        val viewModel =
            createViewModel(
                engine = StaticEngine(validResult(setOf(1))),
                remotePlanningExperiment = experiment,
            )
        viewModel.toggleDay(1)
        viewModel.generateWithAi()
        runCurrent()
        val draft = viewModel.validatedDraft!!

        experiment.setOption("DEEPSEEK")
        runCurrent()

        assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Stale)
        assertNull(viewModel.validatedDraft)
        assertFalse(viewModel.onDraftConsumed(draft))
    }

    @Test
    fun `external key mutation cancels an in flight generation and suppresses its late result`() =
        runTest {
            val experiment = configuredExperiment()
            val engine = ReorderingEngine()
            val viewModel = createViewModel(engine = engine, remotePlanningExperiment = experiment)
            viewModel.toggleDay(1)
            viewModel.generateWithAi()
            engine.started.receive()

            experiment.setApiKey("changed-outside-the-view-model")
            runCurrent()
            assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Stale)
            engine.first.complete(validResult(setOf(1)))
            runCurrent()

            assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Stale)
            assertNull(viewModel.validatedDraft)
            assertEquals(1, engine.calls)
        }

    @Test
    fun `unchanged remote settings and invalid selections retain the validated draft`() = runTest {
        val experiment = configuredExperiment()
        val viewModel =
            createViewModel(
                engine = StaticEngine(validResult(setOf(1))),
                remotePlanningExperiment = experiment,
            )
        viewModel.toggleDay(1)
        viewModel.generateWithAi()
        runCurrent()
        val draft = viewModel.validatedDraft!!
        val revision = experiment.state.value.revision

        viewModel.setRemotePlanningOption("GEMINI")
        viewModel.setRemotePlanningOption("UNKNOWN")
        viewModel.setRemotePlanningApiKey("  synthetic-key  ")
        viewModel.setRemotePlanningEnabled(true)
        runCurrent()

        assertSame(draft, viewModel.validatedDraft)
        assertEquals(revision, experiment.state.value.revision)
    }

    @Test
    fun `retry invokes the engine once per action without changing the configuration revision`() =
        runTest {
            val experiment = configuredExperiment()
            val engine =
                StaticEngine(PlanningResult.Failure(PlanningFailure.ProviderError("offline")))
            val viewModel = createViewModel(engine = engine, remotePlanningExperiment = experiment)
            viewModel.toggleDay(1)
            val configuration = experiment.state.value

            viewModel.generateWithAi()
            runCurrent()
            assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Failed)
            assertEquals(1, engine.calls)
            assertSame(configuration, experiment.state.value)

            viewModel.generateWithAi()
            runCurrent()

            assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Failed)
            assertEquals(2, engine.calls)
            assertSame(configuration, experiment.state.value)
        }

    @Test
    fun `configuration change during noncooperative history loading prevents the remote call`() =
        runTest {
            val experiment = configuredExperiment()
            val historyStarted = CompletableDeferred<Unit>()
            val history = CompletableDeferred<RecentTrainingSummary>()
            val delayedHistory =
                object : PlanningHistoryProvider {
                    override suspend fun loadRecent(
                        today: java.time.LocalDate
                    ): RecentTrainingSummary {
                        historyStarted.complete(Unit)
                        return try {
                            history.await()
                        } catch (_: CancellationException) {
                            withContext(NonCancellable) { history.await() }
                        }
                    }
                }
            val engine = StaticEngine(validResult(setOf(1)))
            val viewModel =
                createViewModel(
                    engine = engine,
                    remotePlanningExperiment = experiment,
                    planningHistoryProvider = delayedHistory,
                )
            viewModel.toggleDay(1)
            viewModel.generateWithAi()
            historyStarted.await()

            viewModel.setRemotePlanningOption("DEEPSEEK")
            history.complete(RecentTrainingSummary.EMPTY)
            runCurrent()

            assertEquals(0, engine.calls)
            assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Stale)
            assertNull(viewModel.validatedDraft)
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
    fun `validated draft is cleared exactly once after review confirms handoff`() = runTest {
        val viewModel = createViewModel(engine = StaticEngine(validResult(setOf(1))))
        viewModel.toggleDay(1)
        viewModel.generateWithAi()
        runCurrent()
        val token = viewModel.validatedDraft!!

        assertTrue(viewModel.onDraftConsumed(token))
        assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Idle)
        assertFalse(viewModel.onDraftConsumed(token))
    }

    @Test
    fun `stale handoff cannot clear a newer validated draft`() = runTest {
        val viewModel = createViewModel(engine = StaticEngine(validResult(setOf(1))))
        viewModel.toggleDay(1)
        viewModel.generateWithAi()
        runCurrent()
        val first = viewModel.validatedDraft!!

        viewModel.generateWithAi()
        runCurrent()
        val second = viewModel.validatedDraft!!

        assertFalse(viewModel.onDraftConsumed(first))
        assertSame(second, viewModel.validatedDraft)
        assertTrue(viewModel.aiGenerationState.value is AiGenerationUiState.Validated)
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
    fun `provider timeout leaves generating state with retryable failure`() = runTest {
        val viewModel =
            createViewModel(engine = StaticEngine(PlanningResult.Failure(PlanningFailure.Timeout)))
        viewModel.toggleDay(1)

        viewModel.generateWithAi()
        runCurrent()

        val state = viewModel.aiGenerationState.value as AiGenerationUiState.Failed
        assertEquals(PlanningFailure.Timeout, state.failure)
    }

    @Test
    fun `rule fallback uses registered rule engine and validates with its provider type`() =
        runTest {
            val aiEngine = StaticEngine(validResult(setOf(1)))
            val ruleEngine =
                TypedStaticEngine(
                    PlanningEngineType.RULE_BASED,
                    validResult(setOf(1), PlanningEngineType.RULE_BASED),
                )
            val viewModel =
                createViewModelWithEngines(
                    engines = mapOf(aiEngine.type to aiEngine, ruleEngine.type to ruleEngine)
                )
            viewModel.toggleDay(1)

            viewModel.generateWithRuleBasedFallback()
            runCurrent()

            val state = viewModel.aiGenerationState.value as AiGenerationUiState.Validated
            assertEquals(PlanningEngineType.RULE_BASED, state.draft.context.invokedEngineType)
            assertEquals(
                PlanningEngineType.RULE_BASED,
                state.draft.draft.providerMetadata.engineType,
            )
        }

    @Test
    fun `missing rule fallback engine reports unavailable without invoking AI`() = runTest {
        val aiEngine = StaticEngine(validResult(setOf(1)))
        val viewModel = createViewModel(engine = aiEngine)
        viewModel.toggleDay(1)

        viewModel.generateWithRuleBasedFallback()
        runCurrent()

        val state = viewModel.aiGenerationState.value as AiGenerationUiState.Failed
        assertEquals(PlanningFailure.Unavailable, state.failure)
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

    @Test
    fun `AI and validated fallback receive the same app selected later week`() = runTest {
        val target = java.time.LocalDate.parse("2027-01-11")
        val received = mutableListOf<PlanningRequest>()
        fun engine(kind: PlanningEngineType) =
            object : PlanningEngine {
                override val type = kind

                override suspend fun generate(request: PlanningRequest): PlanningResult {
                    received += request
                    val base = (validResult(setOf(1), kind) as PlanningResult.Success).draft
                    return PlanningResult.Success(
                        base.copy(
                            targetWeekStart = request.targetWeekStart,
                            workouts =
                                base.workouts.map {
                                    it.copy(scheduledDate = request.targetWeekStart)
                                }
                        )
                    )
                }
            }
        val model =
            createViewModelWithEngines(
                engines =
                    listOf(
                            engine(PlanningEngineType.DEBUG_FAKE_AI),
                            engine(PlanningEngineType.RULE_BASED)
                        )
                        .associateBy { it.type }
            )
        model.toggleDay(1)
        model.generateWithAi(target)
        runCurrent()
        assertTrue(model.aiGenerationState.value is AiGenerationUiState.Validated)
        model.generateWithRuleBasedFallback(target)
        runCurrent()
        assertTrue(model.aiGenerationState.value is AiGenerationUiState.Validated)
        assertEquals(listOf(target, target), received.map { it.targetWeekStart })
    }

    private fun createViewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        engine: PlanningEngine,
        remotePlanningExperiment: RemotePlanningExperiment = FakeRemotePlanningExperiment(),
        planningHistoryProvider: PlanningHistoryProvider = historyProvider,
    ) =
        createViewModelWithEngines(
            handle,
            mapOf(engine.type to engine),
            remotePlanningExperiment,
            planningHistoryProvider,
        )

    private fun createViewModelWithEngines(
        handle: SavedStateHandle = SavedStateHandle(),
        engines: Map<PlanningEngineType, PlanningEngine>,
        remotePlanningExperiment: RemotePlanningExperiment = FakeRemotePlanningExperiment(),
        planningHistoryProvider: PlanningHistoryProvider = historyProvider,
    ): PlannerIntakeViewModel {
        val registry = PlanningEngineRegistry(engines)
        val priorities =
            mapOf(
                PlanningEngineType.ON_DEVICE_AI to 0,
                PlanningEngineType.DEBUG_FAKE_AI to 50,
                PlanningEngineType.DEBUG_REMOTE_AI to 75,
                PlanningEngineType.RULE_BASED to 100,
            )
        val coordinator =
            AiPlanningCoordinator(
                planningEngineRegistry = registry,
                planValidator = PlanValidator(catalog, timeProvider),
                candidates =
                    engines.keys.mapTo(linkedSetOf()) { type ->
                        AiPlanningCandidate(type, priorities.getValue(type))
                    },
            )
        return PlannerIntakeViewModel(
            savedStateHandle = handle,
            aiPlanningCoordinator = coordinator,
            planningHistoryProvider = planningHistoryProvider,
            timeProvider = timeProvider,
            remotePlanningExperiment = remotePlanningExperiment,
        )
    }

    private fun validResult(
        days: Set<Int>,
        engineType: PlanningEngineType = PlanningEngineType.DEBUG_FAKE_AI,
    ): PlanningResult {
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
                providerMetadata = PlanningProviderMetadata(engineType, 1),
            )
        )
    }

    private class StaticEngine(private val result: PlanningResult) : PlanningEngine {
        override val type = PlanningEngineType.DEBUG_FAKE_AI

        var calls = 0
            private set

        override suspend fun generate(request: PlanningRequest): PlanningResult {
            calls += 1
            return result
        }
    }

    private class TypedStaticEngine(
        override val type: PlanningEngineType,
        private val result: PlanningResult,
    ) : PlanningEngine {
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
        var calls = 0
            private set

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

    private fun configuredExperiment() =
        FakeRemotePlanningExperiment().apply {
            setApiKey("synthetic-key")
            setEnabled(true)
        }

    private class FakeRemotePlanningExperiment : RemotePlanningExperiment {
        private val mutableState =
            MutableStateFlow(
                RemotePlanningExperimentState(
                    available = true,
                    optionId = "GEMINI",
                    options =
                        listOf(
                            RemotePlanningOption("GEMINI", "Gemini"),
                            RemotePlanningOption("DEEPSEEK", "DeepSeek"),
                        ),
                )
            )
        override val state: StateFlow<RemotePlanningExperimentState> = mutableState

        override fun setEnabled(enabled: Boolean) {
            update(
                mutableState.value.copy(
                    enabled = enabled,
                    apiKey = if (enabled) mutableState.value.apiKey else "",
                )
            )
        }

        override fun setApiKey(apiKey: String) {
            update(mutableState.value.copy(apiKey = apiKey.trim()))
        }

        override fun setOption(optionId: String) {
            val current = mutableState.value
            if (current.optionId == optionId || current.options.none { it.id == optionId }) return
            update(current.copy(optionId = optionId, enabled = false, apiKey = ""))
        }

        private fun update(next: RemotePlanningExperimentState) {
            val previous = mutableState.value
            if (next != previous) mutableState.value = next.copy(revision = previous.revision + 1)
        }
    }
}
