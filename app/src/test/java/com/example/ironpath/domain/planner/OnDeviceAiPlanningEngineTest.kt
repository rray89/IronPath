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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceAiPlanningEngineTest {
    private val catalog = DefaultExerciseCatalog()
    private val validator = PlanValidator(catalog, FakeTimeProvider())
    private val promptBuilder =
        OnDevicePlanPromptBuilder(catalog, ExerciseEligibilityPolicy(catalog))
    private val mapper = OnDevicePlanDraftMapper(catalog)

    @Test
    fun `available provider returns a validated on-device draft`() = runTest {
        val client = FakeOnDeviceModelClient(generations = mutableListOf(successfulGeneration()))

        val result = engine(client).generate(request())

        assertTrue(result is PlanningResult.Success)
        result as PlanningResult.Success
        assertEquals(PlanningEngineType.ON_DEVICE_AI, result.draft.providerMetadata.engineType)
        assertEquals(LocalDate.parse("2026-07-20"), result.draft.workouts.single().scheduledDate)
        assertEquals(1, client.prompts.size)
    }

    @Test
    fun `non-available capability states skip generation`() = runTest {
        listOf(
                OnDeviceModelStatus.UNAVAILABLE,
                OnDeviceModelStatus.DOWNLOADABLE,
                OnDeviceModelStatus.DOWNLOADING,
            )
            .forEach { status ->
                val client = FakeOnDeviceModelClient(status = status)

                val result = engine(client).generate(request())

                assertEquals(PlanningResult.Failure(PlanningFailure.Unavailable), result)
                assertTrue(client.prompts.isEmpty())
            }
    }

    @Test
    fun `timeout becomes a failure instead of escaping as cancellation`() = runTest {
        val client =
            FakeOnDeviceModelClient(
                generationBlock = {
                    delay(1_000)
                    successfulGeneration()
                }
            )

        val result = engine(client, timeoutMillis = 100).generate(request())

        assertEquals(PlanningResult.Failure(PlanningFailure.Timeout), result)
    }

    @Test
    fun `external cancellation still cancels provider work`() = runTest {
        var providerCancelled = false
        val client =
            FakeOnDeviceModelClient(
                generationBlock = {
                    try {
                        awaitCancellation()
                    } finally {
                        providerCancelled = true
                    }
                }
            )
        val job = launch { engine(client).generate(request()) }
        runCurrent()

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(providerCancelled)
    }

    @Test
    fun `invalid first draft gets exactly one repair`() = runTest {
        val invalid =
            successfulGeneration(
                proposal =
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
                                                    OnDeviceExerciseProposal(
                                                        catalogId = "not-in-catalog",
                                                        sets = 3,
                                                        reps = 8,
                                                        targetWeightKg = 0.0,
                                                    )
                                                )
                                        )
                                )
                        )
            )
        val client =
            FakeOnDeviceModelClient(generations = mutableListOf(invalid, successfulGeneration()))

        val result = engine(client).generate(request())

        assertTrue(result is PlanningResult.Success)
        assertEquals(2, client.prompts.size)
        assertFalse(client.prompts[1].userPrompt.contains("not-in-catalog"))
        assertTrue(client.prompts[1].userPrompt.contains("catalog", ignoreCase = true))
    }

    @Test
    fun `malformed output gets one repair and then fails cleanly`() = runTest {
        val client =
            FakeOnDeviceModelClient(
                generations =
                    mutableListOf(
                        OnDeviceModelGeneration.MalformedOutput,
                        OnDeviceModelGeneration.MalformedOutput,
                    )
            )

        val result = engine(client).generate(request())

        assertTrue(result is PlanningResult.Failure)
        assertTrue((result as PlanningResult.Failure).reason is PlanningFailure.InvalidRequest)
        assertEquals(2, client.prompts.size)
    }

    @Test
    fun `provider exception is normalized to a provider failure`() = runTest {
        val client = FakeOnDeviceModelClient(generationBlock = { error("secret provider detail") })

        val result = engine(client).generate(request())

        assertEquals(
            PlanningResult.Failure(PlanningFailure.ProviderError("On-device generation failed.")),
            result,
        )
    }

    private fun engine(
        client: OnDeviceModelClient,
        timeoutMillis: Long = 5_000,
    ) =
        OnDeviceAiPlanningEngine(
            modelClient = client,
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

    private fun successfulGeneration(
        proposal: OnDevicePlanProposal = validProposal()
    ): OnDeviceModelGeneration = OnDeviceModelGeneration.Success(proposal)

    private fun validProposal() =
        OnDevicePlanProposal(
            rationale = "A conservative full-body start.",
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

private class FakeOnDeviceModelClient(
    private val status: OnDeviceModelStatus = OnDeviceModelStatus.AVAILABLE,
    private val generations: MutableList<OnDeviceModelGeneration> = mutableListOf(),
    private val generationBlock: (suspend (OnDeviceModelPrompt) -> OnDeviceModelGeneration)? = null,
) : OnDeviceModelClient {
    val prompts = mutableListOf<OnDeviceModelPrompt>()

    override suspend fun checkStatus(): OnDeviceModelStatus = status

    override suspend fun generate(prompt: OnDeviceModelPrompt): OnDeviceModelGeneration {
        prompts += prompt
        return generationBlock?.invoke(prompt) ?: generations.removeAt(0)
    }
}
