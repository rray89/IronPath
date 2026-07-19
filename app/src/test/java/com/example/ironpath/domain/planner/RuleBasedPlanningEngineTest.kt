package com.example.ironpath.domain.planner

import com.example.ironpath.testutil.FakeTimeProvider
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedPlanningEngineTest {

    private val catalog = DefaultExerciseCatalog()
    private val validator = PlanValidator(catalog, FakeTimeProvider())
    private val engine = RuleBasedPlanningEngine(RuleBasedPlanFactory(catalog), validator)

    @Test
    fun `generate returns a catalog backed draft without Room entities`() = runTest {
        val targetWeek = LocalDate.parse("2026-07-20")

        val result =
            engine.generate(
                PlanningRequest(
                    targetWeekStart = targetWeek,
                    goal = PlanningGoal.STRENGTH,
                    selectedDays = setOf(1, 3, 5),
                )
            )

        assertTrue(result is PlanningResult.Success)
        val draft = (result as PlanningResult.Success).draft
        assertEquals(targetWeek, draft.targetWeekStart)
        assertEquals(listOf(1, 3, 5), draft.workouts.map { it.dayOfWeek })
        assertEquals(
            listOf(targetWeek, targetWeek.plusDays(2), targetWeek.plusDays(4)),
            draft.workouts.map { it.scheduledDate },
        )
        assertTrue(
            draft.workouts
                .flatMap { it.exercises }
                .all { exercise -> catalog.find(exercise.catalogId) != null }
        )
        assertEquals(PlanningEngineType.RULE_BASED, draft.providerMetadata.engineType)
        assertTrue(draft.providerMetadata.generationDurationMillis >= 0)
    }

    @Test
    fun `generate rejects an empty training day request with typed failure`() = runTest {
        val result =
            engine.generate(
                PlanningRequest(
                    targetWeekStart = LocalDate.parse("2026-07-20"),
                    goal = PlanningGoal.HYPERTROPHY,
                    selectedDays = emptySet(),
                )
            )

        assertTrue(result is PlanningResult.Failure)
        assertTrue((result as PlanningResult.Failure).reason is PlanningFailure.InvalidRequest)
    }

    @Test
    fun `generate rejects a target week that does not start on Monday`() = runTest {
        val result =
            engine.generate(
                PlanningRequest(
                    targetWeekStart = LocalDate.parse("2026-07-21"),
                    goal = PlanningGoal.STRENGTH,
                    selectedDays = setOf(1),
                )
            )

        assertTrue(result is PlanningResult.Failure)
        val failure = (result as PlanningResult.Failure).reason as PlanningFailure.InvalidRequest
        assertTrue("Target week must start on Monday" in failure.violations)
    }

    @Test
    fun `generate rejects training days outside ISO range`() = runTest {
        val result =
            engine.generate(
                PlanningRequest(
                    targetWeekStart = LocalDate.parse("2026-07-20"),
                    goal = PlanningGoal.STRENGTH,
                    selectedDays = setOf(0, 8),
                )
            )

        assertTrue(result is PlanningResult.Failure)
        val failure = (result as PlanningResult.Failure).reason as PlanningFailure.InvalidRequest
        assertTrue("Training days must use ISO values 1 through 7" in failure.violations)
    }

    @Test
    fun `constraint aware fallback substitutes catalog exercises and records the change`() =
        runTest {
            val request =
                PlanningRequest(
                    targetWeekStart = LocalDate.parse("2026-07-20"),
                    intake =
                        PlanningIntake(
                            goal = PlanningGoal.STRENGTH,
                            selectedDays = setOf(1),
                            experience = TrainingExperience.BEGINNER,
                            availableEquipment = setOf(Equipment.BODYWEIGHT),
                        ),
                )

            val result = engine.generate(request) as PlanningResult.Success

            assertEquals(
                listOf(ExerciseCatalogIds.PUSH_UPS),
                result.draft.workouts.single().exercises.map(ExerciseDraft::catalogId),
            )
            assertTrue(result.draft.warnings.any { "Replaced Barbell Bench Press" in it })
            assertTrue(result.draft.warnings.any { "Removed Overhead Press" in it })
            assertTrue(validator.validate(result.draft, request.validationContext()).isValid())
        }

    @Test
    fun `every constraint aware success validates against the same intake snapshot`() = runTest {
        val targetMonday = LocalDate.parse("2026-07-20")
        val requests =
            listOf(
                PlanningRequest(
                    targetMonday,
                    PlanningIntake(PlanningGoal.STRENGTH, setOf(1, 3, 5)),
                ),
                PlanningRequest(
                    targetMonday,
                    PlanningIntake(
                        goal = PlanningGoal.GENERAL_FITNESS,
                        selectedDays = setOf(1),
                        experience = TrainingExperience.BEGINNER,
                        availableEquipment = setOf(Equipment.BODYWEIGHT),
                    ),
                ),
                PlanningRequest(
                    targetMonday,
                    PlanningIntake(
                        goal = PlanningGoal.HYPERTROPHY,
                        selectedDays = setOf(2, 5),
                        experience = TrainingExperience.BEGINNER,
                        availableEquipment = setOf(Equipment.DUMBBELL, Equipment.BENCH),
                    ),
                ),
            )

        val results = requests.map { request -> request to engine.generate(request) }

        assertTrue(results.all { it.second is PlanningResult.Success })
        results.forEach { (request, result) ->
            val draft = (result as PlanningResult.Success).draft
            assertTrue(validator.validate(draft, request.validationContext()).isValid())
        }
    }

    @Test
    fun `common contiguous fallback schedules return valid drafts`() = runTest {
        val targetMonday = LocalDate.parse("2026-07-20")
        val requests =
            listOf(
                PlanningRequest(
                    targetMonday,
                    PlanningIntake(PlanningGoal.MAINTENANCE, setOf(1, 2, 3))
                ),
                PlanningRequest(
                    targetMonday,
                    PlanningIntake(PlanningGoal.STRENGTH, setOf(1, 2, 3, 4, 5)),
                ),
                PlanningRequest(
                    targetMonday,
                    PlanningIntake(PlanningGoal.RETURN_TO_ROUTINE, setOf(1, 2, 3, 4, 5)),
                ),
            )

        requests.forEach { request ->
            val result = engine.generate(request)

            assertTrue(
                "Expected a fallback plan for ${request.intake}, got $result",
                result is PlanningResult.Success,
            )
            val draft = (result as PlanningResult.Success).draft
            assertTrue(
                "Fallback success did not validate for ${request.intake}",
                validator.validate(draft, request.validationContext()).isValid(),
            )
        }
    }

    @Test
    fun `every reported fallback success validates across the supported intake matrix`() = runTest {
        val targetMonday = LocalDate.parse("2026-07-20")
        val daySets =
            (1 until (1 shl 7)).mapNotNull { mask ->
                val days =
                    (1..7).filterTo(mutableSetOf()) { day -> mask and (1 shl (day - 1)) != 0 }
                days.takeIf { it.size <= PlanValidationLimits.MAX_TRAINING_DAYS }
            }
        val equipmentSets = listOf(Equipment.entries.toSet(), setOf(Equipment.BODYWEIGHT))
        val cautionSets =
            listOf(
                emptySet(),
                setOf(ExerciseCautionTag.SHOULDER),
                setOf(ExerciseCautionTag.KNEE),
            )
        var successCount = 0

        PlanningGoal.entries.forEach { goal ->
            daySets.forEach { selectedDays ->
                TrainingExperience.entries
                    .filter { it != TrainingExperience.ADVANCED }
                    .forEach { experience ->
                        equipmentSets.forEach { equipment ->
                            cautionSets.forEach { cautions ->
                                val request =
                                    PlanningRequest(
                                        targetMonday,
                                        PlanningIntake(
                                            goal = goal,
                                            selectedDays = selectedDays,
                                            experience = experience,
                                            availableEquipment = equipment,
                                            forbiddenCautionTags = cautions,
                                        ),
                                    )

                                when (val result = engine.generate(request)) {
                                    is PlanningResult.Failure -> Unit
                                    is PlanningResult.Success -> {
                                        successCount += 1
                                        assertTrue(
                                            "Invalid success for $goal/$selectedDays/$experience/" +
                                                "$equipment/$cautions",
                                            validator
                                                .validate(result.draft, request.validationContext())
                                                .isValid(),
                                        )
                                        result.draft.workouts
                                            .flatMap(WorkoutDraft::exercises)
                                            .forEach { exercise ->
                                                val entry = catalog.require(exercise.catalogId)
                                                assertTrue(
                                                    "Missing target load for ${entry.displayName} " +
                                                        "in $goal/$selectedDays/$experience/" +
                                                        "$equipment/$cautions",
                                                    !entry.requiresTargetLoad() ||
                                                        exercise.targetWeightKg > 0.0,
                                                )
                                            }
                                    }
                                }
                            }
                        }
                    }
            }
        }

        assertTrue(successCount > 0)
    }

    @Test
    fun `fallback returns failure when constraints empty any requested workout`() = runTest {
        val result =
            engine.generate(
                PlanningRequest(
                    targetWeekStart = LocalDate.parse("2026-07-20"),
                    intake =
                        PlanningIntake(
                            goal = PlanningGoal.STRENGTH,
                            selectedDays = setOf(1),
                            experience = TrainingExperience.BEGINNER,
                            availableEquipment = emptySet(),
                        ),
                )
            )

        assertTrue(result is PlanningResult.Failure)
        assertTrue((result as PlanningResult.Failure).reason is PlanningFailure.InvalidRequest)
    }

    @Test
    fun `registry exposes the rule based engine by stable type`() {
        val registry = PlanningEngineRegistry(mapOf(PlanningEngineType.RULE_BASED to engine))

        assertEquals(setOf(PlanningEngineType.RULE_BASED), registry.availableTypes)
        assertEquals(engine, registry.require(PlanningEngineType.RULE_BASED))
    }

    @Test
    fun `registry rejects a map key that disagrees with the engine type`() {
        assertThrows(IllegalStateException::class.java) {
            PlanningEngineRegistry(mapOf(PlanningEngineType.ON_DEVICE_AI to engine))
        }
    }

    @Test
    fun `factory fails fast when a rule template drifts from the catalog`() {
        val incompleteCatalog =
            object : ExerciseCatalog {
                override val entries = catalog.entries.drop(1)

                override fun find(id: ExerciseCatalogId): ExerciseCatalogEntry? =
                    entries.firstOrNull { it.id == id }
            }

        assertThrows(IllegalStateException::class.java) {
            RuleBasedPlanFactory(incompleteCatalog)
                .create(
                    request =
                        PlanningRequest(
                            targetWeekStart = LocalDate.parse("2026-07-20"),
                            goal = PlanningGoal.STRENGTH,
                            selectedDays = setOf(1),
                        ),
                    providerMetadata =
                        PlanningProviderMetadata(
                            engineType = PlanningEngineType.RULE_BASED,
                            generationDurationMillis = 1,
                        ),
                )
        }
    }
}

private fun PlanningRequest.validationContext() =
    PlanValidationContext(
        expectedTargetWeekStart = targetWeekStart,
        invokedEngineType = PlanningEngineType.RULE_BASED,
        selectedDays = selectedDays,
        experience = intake.experience,
        availableEquipment = intake.availableEquipment,
        forbiddenCautionTags = intake.forbiddenCautionTags,
        recentExerciseLoads = intake.recentTraining.exerciseLoads,
    )

private fun PlanValidationResult.isValid() = this is PlanValidationResult.Valid
