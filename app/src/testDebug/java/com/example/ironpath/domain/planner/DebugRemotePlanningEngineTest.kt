package com.example.ironpath.domain.planner

import com.example.ironpath.testutil.FakeTimeProvider
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
                .copy(workouts = listOf(validProposal().workouts.single().copy(dayOfWeek = 7)))
        val transport =
            FakeRemotePlanningTransport(RemotePlanningTransportResult.Success(invalidProposal))

        val result = engine(configuredSettings(), transport).generate(request())

        assertTrue(result is PlanningResult.Failure)
        assertTrue((result as PlanningResult.Failure).reason is PlanningFailure.InvalidRequest)
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

    private fun configuredSettings(apiKey: String = "test-api-key") =
        InMemoryRemotePlanningExperiment().apply {
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

    override suspend fun generate(
        apiKey: String,
        prompt: OnDeviceModelPrompt,
    ): RemotePlanningTransportResult {
        calls += 1
        this.apiKey = apiKey
        this.prompt = prompt
        return block?.invoke() ?: result
    }
}
