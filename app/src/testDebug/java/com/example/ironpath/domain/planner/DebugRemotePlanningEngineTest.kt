package com.example.ironpath.domain.planner

import com.example.ironpath.testutil.FakeTimeProvider
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebugRemotePlanningEngineTest {
    private val catalog = DefaultExerciseCatalog()
    private val promptBuilder =
        OnDevicePlanPromptBuilder(catalog, ExerciseEligibilityPolicy(catalog))
    private val mapper = OnDevicePlanDraftMapper(catalog)
    private val validator = PlanValidator(catalog, FakeTimeProvider())

    @Test
    fun `disabled or unconfigured experiment never calls transport`() = runTest {
        val settings = InMemoryRemotePlanningExperiment()
        val transport = FakeRemotePlanningTransport()

        assertEquals(
            PlanningResult.Failure(PlanningFailure.Unavailable),
            engine(settings, transport).generate(request()),
        )
        settings.setEnabled(true)
        assertEquals(
            PlanningResult.Failure(PlanningFailure.Unavailable),
            engine(settings, transport).generate(request()),
        )
        assertEquals(0, transport.calls)
    }

    @Test
    fun `configured experiment returns a validated remote draft`() = runTest {
        val settings = configuredSettings()
        val transport = FakeRemotePlanningTransport(result = validTransportResult())

        val result = engine(settings, transport).generate(request())

        assertTrue(result is PlanningResult.Success)
        result as PlanningResult.Success
        assertEquals(PlanningEngineType.DEBUG_REMOTE_AI, result.draft.providerMetadata.engineType)
        assertEquals(LocalDate.parse("2026-07-20"), result.draft.workouts.single().scheduledDate)
        assertEquals("test-api-key", transport.apiKey)
        assertTrue(transport.prompt?.userPrompt?.contains("Recent workouts:") == true)
    }

    @Test
    fun `invalid remote output is rejected by the local validator`() = runTest {
        val invalidProposal =
            validProposal()
                .copy(
                    workouts =
                        listOf(
                            validProposal()
                                .workouts
                                .single()
                                .copy(
                                    exercises =
                                        listOf(
                                            validProposal()
                                                .workouts
                                                .single()
                                                .exercises
                                                .single()
                                                .copy(sets = 0)
                                        )
                                )
                        )
                )
        val transport =
            FakeRemotePlanningTransport(RemotePlanningTransportResult.Success(invalidProposal))

        val result = engine(configuredSettings(), transport).generate(request())

        assertTrue(result is PlanningResult.Failure)
        assertEquals(
            PlanningFailure.InvalidRequest(listOf("Sets must be between 1 and 6.")),
            (result as PlanningResult.Failure).reason,
        )
    }

    @Test
    fun `transport errors are sanitized and never expose the key`() = runTest {
        val settings = configuredSettings(apiKey = "secret-key-that-must-not-leak")
        val transport = FakeRemotePlanningTransport(RemotePlanningTransportResult.ProviderFailure)

        val result = engine(settings, transport).generate(request())

        assertEquals(
            PlanningResult.Failure(
                PlanningFailure.ProviderError("The remote planning experiment could not finish.")
            ),
            result,
        )
        assertTrue(result.toString().contains("secret-key-that-must-not-leak").not())
        assertEquals(1, transport.calls)
    }

    @Test
    fun `provider timeout and external cancellation stay distinct`() = runTest {
        val settings = configuredSettings()
        val timeoutTransport =
            FakeRemotePlanningTransport(
                block = {
                    delay(1_000)
                    validTransportResult()
                }
            )
        assertEquals(
            PlanningResult.Failure(PlanningFailure.Timeout),
            engine(settings, timeoutTransport, timeoutMillis = 100).generate(request()),
        )

        assertEquals(1, timeoutTransport.calls)

        var cancelled = false
        val cancellationTransport =
            FakeRemotePlanningTransport(
                block = {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            )
        val job = launch { engine(settings, cancellationTransport).generate(request()) }
        runCurrent()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(cancelled)
    }

    @Test
    fun `every fixed route returns its own label revision and usage`() = runTest {
        RemotePlanningRoute.entries.forEach { route ->
            val settings = configuredSettings(optionId = route.name)
            val revision = settings.state.value.revision
            val usage = PlanningTokenUsage(inputTokens = 120, outputTokens = 80, totalTokens = 200)
            val transport =
                FakeRemotePlanningTransport(
                    RemotePlanningTransportResult.Success(validProposal(), usage)
                )

            val result = engine(settings, transport).generate(request()) as PlanningResult.Success

            assertEquals(route.name, transport.optionId)
            assertEquals(route.option.label, result.draft.providerMetadata.sourceLabel)
            assertEquals(revision, result.draft.providerMetadata.remoteConfigurationRevision)
            assertEquals(usage, result.draft.providerMetadata.tokenUsage)
            assertEquals(1, transport.calls)
        }
    }

    @Test
    fun `option key and disable changes cancel and discard a noncooperative response`() = runTest {
        val changes: List<(InMemoryRemotePlanningExperiment) -> Unit> =
            listOf(
                { it.setOption(RemotePlanningRoute.DEEPSEEK.name) },
                { it.setApiKey("replacement-key") },
                { it.setEnabled(false) },
            )
        changes.forEach { change ->
            val settings = configuredSettings()
            val response = CompletableDeferred<RemotePlanningTransportResult>()
            val transport =
                FakeRemotePlanningTransport(
                    block = {
                        try {
                            response.await()
                        } catch (_: CancellationException) {
                            withContext(NonCancellable) { response.await() }
                        }
                    }
                )
            var delivered: PlanningResult? = null
            val job = launch { delivered = engine(settings, transport).generate(request()) }
            runCurrent()
            assertEquals(1, transport.calls)

            change(settings)
            runCurrent()
            response.complete(validTransportResult())
            runCurrent()
            job.join()

            assertTrue(job.isCancelled)
            assertNull(delivered)
            assertEquals("test-api-key", transport.apiKey)
            assertEquals(RemotePlanningRoute.GEMINI.name, transport.optionId)
            assertEquals(1, transport.calls)
        }
    }

    @Test
    fun `a new request uses the new configuration without mutating the earlier receipt`() =
        runTest {
            val settings = configuredSettings()
            val transport = FakeRemotePlanningTransport(validTransportResult())
            val engine = engine(settings, transport)
            val first = engine.generate(request()) as PlanningResult.Success
            val firstRevision = settings.state.value.revision

            settings.setOption(RemotePlanningRoute.DEEPSEEK.name)
            settings.setApiKey("second-synthetic-key")
            settings.setEnabled(true)
            val second = engine.generate(request()) as PlanningResult.Success

            assertEquals(2, transport.calls)
            assertEquals(RemotePlanningRoute.DEEPSEEK.name, transport.optionId)
            assertEquals("second-synthetic-key", transport.apiKey)
            assertEquals(firstRevision, first.draft.providerMetadata.remoteConfigurationRevision)
            assertEquals(RemotePlanningRoute.GEMINI.label, first.draft.providerMetadata.sourceLabel)
            assertEquals(
                settings.state.value.revision,
                second.draft.providerMetadata.remoteConfigurationRevision
            )
            assertEquals(
                RemotePlanningRoute.DEEPSEEK.label,
                second.draft.providerMetadata.sourceLabel
            )
        }

    @Test
    fun `an explicit retry uses the same immutable configuration revision`() = runTest {
        val settings = configuredSettings(optionId = RemotePlanningRoute.OPENROUTER_QWEN.name)
        val revision = settings.state.value.revision
        var attempt = 0
        val transport =
            FakeRemotePlanningTransport(
                block = {
                    if (attempt++ == 0) RemotePlanningTransportResult.ProviderFailure
                    else validTransportResult()
                }
            )
        val engine = engine(settings, transport)

        assertTrue(engine.generate(request()) is PlanningResult.Failure)
        assertEquals(1, transport.calls)
        val retry = engine.generate(request()) as PlanningResult.Success

        assertEquals(2, transport.calls)
        assertEquals(revision, settings.state.value.revision)
        assertEquals(revision, retry.draft.providerMetadata.remoteConfigurationRevision)
        assertEquals(RemotePlanningRoute.OPENROUTER_QWEN.name, transport.optionId)
    }

    @Test
    fun `unknown options fail closed and disabled options remain unavailable`() = runTest {
        val mutableConfiguration =
            MutableStateFlow(
                RemotePlanningExperimentState(
                    available = true,
                    enabled = true,
                    apiKey = "synthetic-key",
                    optionId = "UNKNOWN",
                )
            )
        val settings =
            object : RemotePlanningExperiment {
                override val state: StateFlow<RemotePlanningExperimentState> = mutableConfiguration

                override fun setEnabled(enabled: Boolean) = Unit

                override fun setApiKey(apiKey: String) = Unit
            }
        val transport = FakeRemotePlanningTransport(validTransportResult())
        val engine = engine(settings, transport)

        val unknown = engine.generate(request()) as PlanningResult.Failure
        assertTrue(unknown.reason is PlanningFailure.ProviderError)
        mutableConfiguration.value = mutableConfiguration.value.copy(enabled = false, revision = 1)
        assertEquals(
            PlanningResult.Failure(PlanningFailure.Unavailable),
            engine.generate(request())
        )
        assertEquals(0, transport.calls)
    }

    @Test
    fun `unknown exercises and out of bounds days are rejected without a paid repair`() = runTest {
        val base = validProposal()
        val invalidProposals =
            listOf(
                base.copy(workouts = listOf(base.workouts.single().copy(dayOfWeek = 0))),
                base.copy(workouts = listOf(base.workouts.single().copy(dayOfWeek = 8))),
                proposalWithExercise(
                    base.workouts.single().exercises.single().copy(catalogId = "not-in-catalog")
                ),
                proposalWithExercise(base.workouts.single().exercises.single().copy(reps = 31)),
                proposalWithExercise(
                    base.workouts.single().exercises.single().copy(targetWeightKg = 301.0)
                ),
            )
        invalidProposals.forEach { proposal ->
            val transport =
                FakeRemotePlanningTransport(RemotePlanningTransportResult.Success(proposal))

            val result = engine(configuredSettings(), transport).generate(request())

            assertTrue(result is PlanningResult.Failure)
            assertTrue((result as PlanningResult.Failure).reason is PlanningFailure.InvalidRequest)
            assertEquals(1, transport.calls)
        }
    }

    @Test
    fun `private intake and history stay out of prompts while local progression still applies`() =
        runTest {
            val privateMarkers =
                listOf(
                    "private-injury-marker",
                    "private-preference-marker",
                    "private-dislike-marker",
                    "private-workout-marker",
                    "private-record-marker",
                    "private-unresolved-marker",
                )
            val original = request()
            val privateRequest =
                original.copy(
                    intake =
                        original.intake.copy(
                            injuryNotes = privateMarkers[0],
                            exercisePreferences = privateMarkers[1],
                            exerciseDislikes = privateMarkers[2],
                            recentTraining =
                                RecentTrainingSummary(
                                    workouts =
                                        listOf(
                                            RecentWorkoutSummary(
                                                privateMarkers[3],
                                                LocalDate.parse("2026-07-10"),
                                                2
                                            )
                                        ),
                                    records =
                                        listOf(
                                            RecentRecordSummary(
                                                privateMarkers[4],
                                                77.7,
                                                LocalDate.parse("2026-07-10")
                                            )
                                        ),
                                    exerciseLoads =
                                        listOf(
                                            RecentExerciseLoad(
                                                ExerciseCatalogIds.BARBELL_BENCH_PRESS,
                                                77.7
                                            )
                                        ),
                                    unresolvedExerciseNames = setOf(privateMarkers[5]),
                                ),
                        )
                )
            val proposal =
                proposalWithExercise(
                    OnDeviceExerciseProposal(
                        catalogId = ExerciseCatalogIds.BARBELL_BENCH_PRESS.value,
                        sets = 3,
                        reps = 8,
                        targetWeightKg = 100.0,
                    )
                )
            val transport =
                FakeRemotePlanningTransport(RemotePlanningTransportResult.Success(proposal))

            val result =
                engine(configuredSettings(apiKey = "private-api-key-marker"), transport)
                    .generate(privateRequest)

            val sent = transport.prompt!!
            val completePrompt = sent.systemInstruction + sent.userPrompt
            (privateMarkers + "77.7" + "private-api-key-marker").forEach {
                assertFalse("Remote prompt leaked $it", completePrompt.contains(it))
            }
            assertTrue(sent.userPrompt.contains("Selected ISO days: 1"))
            assertTrue(sent.userPrompt.contains("Goal: STRENGTH"))
            assertEquals(
                PlanningResult.Failure(
                    PlanningFailure.InvalidRequest(
                        listOf("Target weight increases too quickly from recent history.")
                    )
                ),
                result,
            )
            assertEquals(1, transport.calls)
            val withoutHistory =
                engine(
                        configuredSettings(),
                        FakeRemotePlanningTransport(RemotePlanningTransportResult.Success(proposal))
                    )
                    .generate(original)
            assertTrue(withoutHistory is PlanningResult.Success)
        }

    @Test
    fun `transport exceptions are sanitized with no automatic retry`() = runTest {
        val secret = "synthetic-secret-that-must-not-leak"
        val transport = FakeRemotePlanningTransport(block = { error("provider echoed $secret") })

        val result = engine(configuredSettings(apiKey = secret), transport).generate(request())

        assertTrue(result is PlanningResult.Failure)
        assertTrue((result as PlanningResult.Failure).reason is PlanningFailure.ProviderError)
        assertFalse(result.toString().contains(secret))
        assertEquals(1, transport.calls)
    }

    private fun proposalWithExercise(exercise: OnDeviceExerciseProposal) =
        validProposal().let { proposal ->
            proposal.copy(
                workouts = listOf(proposal.workouts.single().copy(exercises = listOf(exercise)))
            )
        }

    private fun configuredSettings(
        apiKey: String = "test-api-key",
        optionId: String = RemotePlanningRoute.GEMINI.name
    ) =
        InMemoryRemotePlanningExperiment().apply {
            setOption(optionId)
            setApiKey(apiKey)
            setEnabled(true)
        }

    private fun engine(
        settings: RemotePlanningExperiment,
        transport: RemotePlanningTransport,
        timeoutMillis: Long = 5_000,
    ) =
        DebugRemotePlanningEngine(
            experiment = settings,
            transport = transport,
            promptBuilder = promptBuilder,
            draftMapper = mapper,
            planValidator = validator,
            timeoutMillis = timeoutMillis,
        )

    private fun request() =
        PlanningRequest(
            targetWeekStart = LocalDate.parse("2026-07-20"),
            intake =
                PlanningIntake(
                    goal = PlanningGoal.STRENGTH,
                    selectedDays = setOf(1),
                ),
        )

    private fun validTransportResult() = RemotePlanningTransportResult.Success(validProposal())

    private fun validProposal() =
        OnDevicePlanProposal(
            rationale = "A conservative remote draft.",
            warnings = emptyList(),
            workouts =
                listOf(
                    OnDeviceWorkoutProposal(
                        dayOfWeek = 1,
                        title = "Full Body",
                        exercises =
                            listOf(
                                OnDeviceExerciseProposal(
                                    catalogId = ExerciseCatalogIds.PUSH_UPS.value,
                                    sets = 3,
                                    reps = 8,
                                    targetWeightKg = 0.0,
                                )
                            ),
                    )
                ),
        )
}

private class FakeRemotePlanningTransport(
    private val result: RemotePlanningTransportResult =
        RemotePlanningTransportResult.ProviderFailure,
    private val block: (suspend () -> RemotePlanningTransportResult)? = null,
) : RemotePlanningTransport {
    var calls = 0
    var apiKey: String? = null
    var prompt: OnDeviceModelPrompt? = null
    var optionId: String? = null

    override suspend fun generate(
        apiKey: String,
        prompt: OnDeviceModelPrompt,
        optionId: String,
    ): RemotePlanningTransportResult {
        calls += 1
        this.apiKey = apiKey
        this.prompt = prompt
        this.optionId = optionId
        return block?.invoke() ?: result
    }
}
