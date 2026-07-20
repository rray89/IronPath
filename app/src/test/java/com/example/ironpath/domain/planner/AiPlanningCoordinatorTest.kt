package com.example.ironpath.domain.planner

import com.example.ironpath.testutil.FakeTimeProvider
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiPlanningCoordinatorTest {
    private val catalog = DefaultExerciseCatalog()
    private val validator = PlanValidator(catalog, FakeTimeProvider())

    @Test
    fun `on-device success wins without invoking lower priority engines`() = runTest {
        val onDevice =
            StaticEngine(PlanningEngineType.ON_DEVICE_AI, success(PlanningEngineType.ON_DEVICE_AI))
        val fake =
            StaticEngine(
                PlanningEngineType.DEBUG_FAKE_AI,
                success(PlanningEngineType.DEBUG_FAKE_AI)
            )
        val rule =
            StaticEngine(PlanningEngineType.RULE_BASED, success(PlanningEngineType.RULE_BASED))

        val result = coordinator(onDevice, fake, rule).generateWithAi(request())

        assertTrue(result is AiPlanningOutcome.Validated)
        result as AiPlanningOutcome.Validated
        assertEquals(PlanningEngineType.ON_DEVICE_AI, result.effectiveEngineType)
        assertEquals(1, onDevice.calls)
        assertEquals(0, fake.calls)
        assertEquals(0, rule.calls)
    }

    @Test
    fun `unsupported on-device provider falls through to debug fake`() = runTest {
        val onDevice =
            StaticEngine(
                PlanningEngineType.ON_DEVICE_AI,
                PlanningResult.Failure(PlanningFailure.Unavailable),
            )
        val fake =
            StaticEngine(
                PlanningEngineType.DEBUG_FAKE_AI,
                success(PlanningEngineType.DEBUG_FAKE_AI)
            )
        val rule =
            StaticEngine(PlanningEngineType.RULE_BASED, success(PlanningEngineType.RULE_BASED))

        val result = coordinator(onDevice, fake, rule).generateWithAi(request())

        result as AiPlanningOutcome.Validated
        assertEquals(PlanningEngineType.DEBUG_FAKE_AI, result.effectiveEngineType)
        assertTrue(result.draft.draft.providerMetadata.fallbackReason!!.contains("unavailable"))
        assertEquals(0, rule.calls)
    }

    @Test
    fun `remote provider failure is explained when debug fake wins`() = runTest {
        val onDevice =
            StaticEngine(
                PlanningEngineType.ON_DEVICE_AI,
                PlanningResult.Failure(PlanningFailure.Unavailable),
            )
        val remote =
            StaticEngine(
                PlanningEngineType.DEBUG_REMOTE_AI,
                PlanningResult.Failure(
                    PlanningFailure.ProviderError(
                        "The remote planning experiment could not finish."
                    )
                ),
            )
        val fake =
            StaticEngine(
                PlanningEngineType.DEBUG_FAKE_AI,
                success(PlanningEngineType.DEBUG_FAKE_AI),
            )

        val result = coordinator(onDevice, remote, fake).generateWithAi(request())

        result as AiPlanningOutcome.Validated
        assertEquals(PlanningEngineType.DEBUG_FAKE_AI, result.effectiveEngineType)
        assertEquals(
            "Remote AI could not finish, so the debug AI provider generated this draft.",
            result.draft.draft.providerMetadata.fallbackReason,
        )
    }

    @Test
    fun `disabled remote experiment does not obscure on-device fallback reason`() = runTest {
        val onDevice =
            StaticEngine(
                PlanningEngineType.ON_DEVICE_AI,
                PlanningResult.Failure(PlanningFailure.Unavailable),
            )
        val remote =
            StaticEngine(
                PlanningEngineType.DEBUG_REMOTE_AI,
                PlanningResult.Failure(PlanningFailure.Unavailable),
            )
        val fake =
            StaticEngine(
                PlanningEngineType.DEBUG_FAKE_AI,
                success(PlanningEngineType.DEBUG_FAKE_AI),
            )

        val result = coordinator(onDevice, remote, fake).generateWithAi(request())

        result as AiPlanningOutcome.Validated
        assertEquals(
            "On-device AI is unavailable, so the debug AI provider generated this draft.",
            result.draft.draft.providerMetadata.fallbackReason,
        )
    }

    @Test
    fun `invalid AI attempt falls back with rule provenance and rule validation context`() =
        runTest {
            val onDevice =
                StaticEngine(
                    PlanningEngineType.ON_DEVICE_AI,
                    PlanningResult.Failure(
                        PlanningFailure.InvalidRequest(listOf("unsafe secret draft detail"))
                    ),
                )
            val rule =
                StaticEngine(PlanningEngineType.RULE_BASED, success(PlanningEngineType.RULE_BASED))

            val result = coordinator(onDevice, rule).generateWithAi(request())

            result as AiPlanningOutcome.Validated
            assertEquals(PlanningEngineType.RULE_BASED, result.effectiveEngineType)
            assertEquals(PlanningEngineType.RULE_BASED, result.draft.context.invokedEngineType)
            assertEquals(
                PlanningEngineType.RULE_BASED,
                result.draft.draft.providerMetadata.engineType
            )
            val reason = result.draft.draft.providerMetadata.fallbackReason.orEmpty()
            assertTrue(reason.contains("safety checks"))
            assertFalse(reason.contains("secret"))
        }

    @Test
    fun `provider exception and timeout both allow rule fallback`() = runTest {
        val throwing =
            object : PlanningEngine {
                override val type = PlanningEngineType.ON_DEVICE_AI

                override suspend fun generate(request: PlanningRequest): PlanningResult =
                    error("provider internals")
            }
        val timeout =
            StaticEngine(
                PlanningEngineType.ON_DEVICE_AI,
                PlanningResult.Failure(PlanningFailure.Timeout),
            )
        listOf(throwing, timeout).forEach { first ->
            val rule =
                StaticEngine(PlanningEngineType.RULE_BASED, success(PlanningEngineType.RULE_BASED))

            val result = coordinator(first, rule).generateWithAi(request())

            assertTrue(result is AiPlanningOutcome.Validated)
            assertEquals(1, rule.calls)
        }
    }

    @Test
    fun `external cancellation never starts fallback`() = runTest {
        val onDevice =
            object : PlanningEngine {
                override val type = PlanningEngineType.ON_DEVICE_AI

                override suspend fun generate(request: PlanningRequest): PlanningResult =
                    awaitCancellation()
            }
        val rule =
            StaticEngine(PlanningEngineType.RULE_BASED, success(PlanningEngineType.RULE_BASED))
        val job = launch { coordinator(onDevice, rule).generateWithAi(request()) }
        runCurrent()

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(0, rule.calls)
    }

    private fun coordinator(vararg engines: PlanningEngine): AiPlanningCoordinator {
        val registry = PlanningEngineRegistry(engines.associateBy(PlanningEngine::type))
        val priorities =
            mapOf(
                PlanningEngineType.ON_DEVICE_AI to 0,
                PlanningEngineType.DEBUG_REMOTE_AI to 1,
                PlanningEngineType.DEBUG_FAKE_AI to 2,
                PlanningEngineType.RULE_BASED to 3,
            )
        return AiPlanningCoordinator(
            planningEngineRegistry = registry,
            planValidator = validator,
            candidates =
                engines.mapTo(linkedSetOf()) { engine ->
                    AiPlanningCandidate(engine.type, priorities.getValue(engine.type))
                },
        )
    }

    private fun request() =
        PlanningRequest(
            targetWeekStart = LocalDate.parse("2026-07-20"),
            goal = PlanningGoal.STRENGTH,
            selectedDays = setOf(1),
        )

    private fun success(type: PlanningEngineType) =
        PlanningResult.Success(
            PlanDraft(
                targetWeekStart = LocalDate.parse("2026-07-20"),
                workouts =
                    listOf(
                        WorkoutDraft(
                            dayOfWeek = 1,
                            scheduledDate = LocalDate.parse("2026-07-20"),
                            title = "Full Body",
                            exercises =
                                listOf(
                                    ExerciseDraft(
                                        catalogId = ExerciseCatalogIds.PUSH_UPS,
                                        sets = 3,
                                        reps = 8,
                                        targetWeightKg = 0.0,
                                    )
                                ),
                        )
                    ),
                providerMetadata = PlanningProviderMetadata(type, 1),
            )
        )
}

private class StaticEngine(
    override val type: PlanningEngineType,
    private val result: PlanningResult,
) : PlanningEngine {
    var calls = 0

    override suspend fun generate(request: PlanningRequest): PlanningResult {
        calls += 1
        return result
    }
}
