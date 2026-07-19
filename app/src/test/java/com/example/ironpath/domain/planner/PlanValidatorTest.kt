package com.example.ironpath.domain.planner

import com.example.ironpath.testutil.FakeTimeProvider
import java.time.Duration
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanValidatorTest {
    private val timeProvider = FakeTimeProvider()
    private val catalog = DefaultExerciseCatalog()
    private val validator = PlanValidator(catalog, timeProvider)
    private val targetMonday = LocalDate.parse("2026-07-20")
    private val allEquipment = Equipment.entries.toSet()

    @Test
    fun `valid draft returns a validation token with fixed-time provenance`() {
        val draft = validDraft()

        val result = validator.validate(draft, validContext())

        assertTrue(result is PlanValidationResult.Valid)
        result as PlanValidationResult.Valid
        assertEquals(draft, result.validatedPlan.draft)
        assertNotSame(draft, result.validatedPlan.draft)
        assertEquals(timeProvider.now(), result.validatedPlan.validatedAt)
    }

    @Test
    fun `validation token owns a defensive snapshot of mutable inputs`() {
        val mutableExercises = mutableListOf(exercise(ExerciseCatalogIds.PUSH_UPS))
        val mutableWorkouts = mutableListOf(workout(1).copy(exercises = mutableExercises))
        val mutableSelectedDays = mutableSetOf(1)
        val draft = validDraft(workouts = mutableWorkouts)
        val context = validContext(mutableSelectedDays)

        val result = validator.validate(draft, context) as PlanValidationResult.Valid
        mutableExercises.clear()
        mutableWorkouts.clear()
        mutableSelectedDays.clear()

        assertEquals(1, result.validatedPlan.draft.workouts.size)
        assertEquals(1, result.validatedPlan.draft.workouts.single().exercises.size)
        assertEquals(setOf(1), result.validatedPlan.context.selectedDays)
    }

    @Test
    fun `validator accumulates structural violations instead of failing fast`() {
        val draft =
            validDraft()
                .copy(
                    targetWeekStart = targetMonday.plusDays(1),
                    workouts = emptyList(),
                    providerMetadata =
                        validDraft().providerMetadata.copy(generationDurationMillis = -1),
                )

        val codes = invalidCodes(draft)

        assertTrue(PlanViolationCode.TARGET_WEEK_NOT_MONDAY in codes)
        assertTrue(PlanViolationCode.TARGET_WEEK_MISMATCH in codes)
        assertTrue(PlanViolationCode.WORKOUT_COUNT_OUT_OF_RANGE in codes)
        assertTrue(PlanViolationCode.WORKOUT_COUNT_MISMATCH in codes)
        assertTrue(PlanViolationCode.SELECTED_DAYS_MISMATCH in codes)
        assertTrue(PlanViolationCode.INVALID_PROVIDER_METADATA in codes)
    }

    @Test
    fun `unknown catalog ids and duplicate exercises are rejected`() {
        val workout =
            workout(
                day = 1,
                exerciseIds =
                    listOf(
                        ExerciseCatalogId("not-in-catalog"),
                        ExerciseCatalogId("not-in-catalog"),
                    ),
            )

        val codes =
            invalidCodes(
                validDraft(workouts = listOf(workout)),
                validContext(selectedDays = setOf(1)),
            )

        assertTrue(PlanViolationCode.UNKNOWN_EXERCISE in codes)
        assertTrue(PlanViolationCode.DUPLICATE_EXERCISE in codes)
    }

    @Test
    fun `blank titles and empty exercise lists are rejected`() {
        val workout = workout(day = 1).copy(title = "  ", exercises = emptyList())

        val codes =
            invalidCodes(
                validDraft(workouts = listOf(workout)),
                validContext(selectedDays = setOf(1)),
            )

        assertTrue(PlanViolationCode.BLANK_WORKOUT_TITLE in codes)
        assertTrue(PlanViolationCode.EXERCISE_COUNT_OUT_OF_RANGE in codes)
    }

    @Test
    fun `duplicate workout days are rejected`() {
        val workouts =
            listOf(
                workout(1, ExerciseCatalogIds.PUSH_UPS),
                workout(1, ExerciseCatalogIds.INCLINE_DUMBBELL_PRESS),
            )

        val codes =
            invalidCodes(
                validDraft(workouts = workouts),
                validContext(selectedDays = setOf(1)),
            )

        assertTrue(PlanViolationCode.DUPLICATE_WORKOUT_DAY in codes)
        assertTrue(PlanViolationCode.INSUFFICIENT_MUSCLE_REST in codes)
    }

    @Test
    fun `workout dates must match the requested Monday through Sunday week`() {
        val wrongDate =
            workout(1, ExerciseCatalogIds.PUSH_UPS).copy(scheduledDate = targetMonday.plusDays(7))

        val codes =
            invalidCodes(
                validDraft(workouts = listOf(wrongDate)),
                validContext(selectedDays = setOf(1)),
            )

        assertTrue(PlanViolationCode.DATE_OUTSIDE_TARGET_WEEK in codes)
        assertTrue(PlanViolationCode.DATE_DAY_MISMATCH in codes)
    }

    @Test
    fun `workouts scheduled before today are rejected`() {
        val pastWorkout =
            workout(1, ExerciseCatalogIds.PUSH_UPS)
                .copy(scheduledDate = timeProvider.today().minusDays(1))

        val codes =
            invalidCodes(
                validDraft(workouts = listOf(pastWorkout)),
                validContext(selectedDays = setOf(1)),
            )

        assertTrue(PlanViolationCode.WORKOUT_IN_PAST in codes)
    }

    @Test
    fun `day numbers outside ISO range are rejected`() {
        val codes =
            invalidCodes(
                validDraft(workouts = listOf(workout(8, ExerciseCatalogIds.PUSH_UPS))),
                validContext(selectedDays = setOf(1)),
            )

        assertTrue(PlanViolationCode.DAY_OUT_OF_RANGE in codes)
    }

    @Test
    fun `requested days require one to six valid ISO days and one rest day`() {
        val selectedDays = (1..7).toSet()
        val workouts = selectedDays.map { workout(it, ExerciseCatalogIds.PLANK_HOLD) }

        val codes =
            invalidCodes(
                validDraft(workouts = workouts),
                validContext(selectedDays = selectedDays),
            )

        assertTrue(PlanViolationCode.INVALID_REQUESTED_DAYS in codes)
        assertTrue(PlanViolationCode.WORKOUT_COUNT_OUT_OF_RANGE in codes)
    }

    @Test
    fun `workout days must exactly match selected days`() {
        val codes =
            invalidCodes(
                validDraft(workouts = listOf(workout(2, ExerciseCatalogIds.PUSH_UPS))),
                validContext(selectedDays = setOf(1, 3)),
            )

        assertTrue(PlanViolationCode.WORKOUT_COUNT_MISMATCH in codes)
        assertTrue(PlanViolationCode.SELECTED_DAYS_MISMATCH in codes)
    }

    @Test
    fun `per-day exercise limit accepts one through eight exercises`() {
        val atMinimum = workout(1, ExerciseCatalogIds.PUSH_UPS)
        val atMaximum =
            workout(
                day = 1,
                exerciseIds =
                    listOf(
                        ExerciseCatalogIds.PUSH_UPS,
                        ExerciseCatalogIds.HAMMER_CURLS,
                        ExerciseCatalogIds.GOBLET_SQUATS,
                        ExerciseCatalogIds.GLUTE_BRIDGES,
                        ExerciseCatalogIds.PLANK_HOLD,
                        ExerciseCatalogIds.BAND_PULL_APARTS,
                        ExerciseCatalogIds.WALL_SLIDES,
                        ExerciseCatalogIds.CALF_RAISES,
                    ),
            )

        assertValid(validDraft(workouts = listOf(atMinimum)), validContext(setOf(1)))
        assertValid(validDraft(workouts = listOf(atMaximum)), validContext(setOf(1)))

        val tooMany =
            atMaximum.copy(
                exercises = atMaximum.exercises + exercise(ExerciseCatalogIds.LIGHT_DUMBBELL_PRESS)
            )
        assertTrue(
            PlanViolationCode.EXERCISE_COUNT_OUT_OF_RANGE in
                invalidCodes(
                    validDraft(workouts = listOf(tooMany)),
                    validContext(setOf(1)),
                )
        )
    }

    @Test
    fun `set rep and kg boundaries are inclusive`() {
        val lower = exercise(ExerciseCatalogIds.PUSH_UPS, sets = 1, reps = 1, weightKg = 0.0)
        val upper =
            exercise(
                ExerciseCatalogIds.PUSH_UPS,
                sets = PlanValidationLimits.MAX_SETS_PER_EXERCISE,
                reps = PlanValidationLimits.MAX_REPS_PER_SET,
                weightKg = PlanValidationLimits.MAX_WEIGHT_KG,
            )

        assertValid(
            validDraft(workouts = listOf(workout(1).copy(exercises = listOf(lower)))),
            validContext(setOf(1)),
        )
        assertValid(
            validDraft(workouts = listOf(workout(1).copy(exercises = listOf(upper)))),
            validContext(setOf(1)),
        )
    }

    @Test
    fun `invalid set rep and kg values are rejected`() {
        val invalidExercises =
            listOf(
                exercise(ExerciseCatalogIds.PUSH_UPS, sets = 0) to
                    PlanViolationCode.INVALID_SET_COUNT,
                exercise(
                    ExerciseCatalogIds.PUSH_UPS,
                    sets = PlanValidationLimits.MAX_SETS_PER_EXERCISE + 1,
                ) to PlanViolationCode.INVALID_SET_COUNT,
                exercise(ExerciseCatalogIds.PUSH_UPS, reps = 0) to
                    PlanViolationCode.INVALID_REP_COUNT,
                exercise(
                    ExerciseCatalogIds.PUSH_UPS,
                    reps = PlanValidationLimits.MAX_REPS_PER_SET + 1,
                ) to PlanViolationCode.INVALID_REP_COUNT,
                exercise(ExerciseCatalogIds.PUSH_UPS, weightKg = -0.1) to
                    PlanViolationCode.INVALID_WEIGHT,
                exercise(
                    ExerciseCatalogIds.PUSH_UPS,
                    weightKg = PlanValidationLimits.MAX_WEIGHT_KG + 0.1,
                ) to PlanViolationCode.INVALID_WEIGHT,
                exercise(ExerciseCatalogIds.PUSH_UPS, weightKg = Double.NaN) to
                    PlanViolationCode.INVALID_WEIGHT,
                exercise(ExerciseCatalogIds.PUSH_UPS, weightKg = Double.POSITIVE_INFINITY) to
                    PlanViolationCode.INVALID_WEIGHT,
            )

        invalidExercises.forEach { (invalidExercise, expectedCode) ->
            val draft =
                validDraft(workouts = listOf(workout(1).copy(exercises = listOf(invalidExercise))))
            assertTrue(
                "$expectedCode was not reported for $invalidExercise",
                expectedCode in invalidCodes(draft, validContext(setOf(1))),
            )
        }
    }

    @Test
    fun `missing required equipment is rejected`() {
        val draft =
            validDraft(workouts = listOf(workout(1, ExerciseCatalogIds.BARBELL_BENCH_PRESS)))

        val codes =
            invalidCodes(
                draft,
                validContext(setOf(1)).copy(availableEquipment = setOf(Equipment.BARBELL)),
            )

        assertTrue(PlanViolationCode.MISSING_EQUIPMENT in codes)
    }

    @Test
    fun `beginner-inappropriate exercises are rejected for beginners`() {
        val draft = validDraft(workouts = listOf(workout(1, ExerciseCatalogIds.DEADLIFT)))

        val codes =
            invalidCodes(
                draft,
                validContext(setOf(1)).copy(experience = TrainingExperience.BEGINNER),
            )

        assertTrue(PlanViolationCode.BEGINNER_EXERCISE_NOT_ALLOWED in codes)
    }

    @Test
    fun `explicitly forbidden movement tags are hard constraints`() {
        val draft = validDraft(workouts = listOf(workout(1, ExerciseCatalogIds.PUSH_UPS)))

        val codes =
            invalidCodes(
                draft,
                validContext(setOf(1))
                    .copy(forbiddenCautionTags = setOf(ExerciseCautionTag.SHOULDER)),
            )

        assertTrue(PlanViolationCode.FORBIDDEN_MOVEMENT in codes)
    }

    @Test
    fun `AI drafts reject catalog entries that cannot encode an unambiguous load`() {
        val draft =
            validDraft(
                workouts = listOf(workout(1, ExerciseCatalogIds.WEIGHTED_PULL_UPS)),
                engineType = PlanningEngineType.ON_DEVICE_AI,
            )

        assertTrue(
            PlanViolationCode.AI_EXERCISE_NOT_ALLOWED in invalidCodes(draft, validContext(setOf(1)))
        )

        val falsifiedMetadata =
            draft.copy(
                providerMetadata =
                    draft.providerMetadata.copy(engineType = PlanningEngineType.RULE_BASED)
            )
        val falsifiedCodes = invalidCodes(falsifiedMetadata, validContext(setOf(1)))
        assertTrue(PlanViolationCode.INVALID_PROVIDER_METADATA in falsifiedCodes)
        assertTrue(PlanViolationCode.AI_EXERCISE_NOT_ALLOWED in falsifiedCodes)

        assertValid(
            falsifiedMetadata,
            validContext(setOf(1)).copy(invokedEngineType = PlanningEngineType.RULE_BASED),
        )
    }

    @Test
    fun `weekly hard-set caps reject excessive total and primary-muscle volume`() {
        val chestWorkouts =
            (1..5).map { day ->
                workout(day)
                    .copy(
                        exercises =
                            listOf(
                                exercise(
                                    ExerciseCatalogIds.PUSH_UPS,
                                    sets = PlanValidationLimits.MAX_SETS_PER_EXERCISE,
                                )
                            )
                    )
            }
        val chestCodes =
            invalidCodes(
                validDraft(workouts = chestWorkouts),
                validContext((1..5).toSet()),
            )
        assertTrue(PlanViolationCode.MUSCLE_VOLUME_EXCEEDED in chestCodes)

        val exerciseIds =
            listOf(
                ExerciseCatalogIds.PUSH_UPS,
                ExerciseCatalogIds.HAMMER_CURLS,
                ExerciseCatalogIds.GOBLET_SQUATS,
                ExerciseCatalogIds.PLANK_HOLD,
            )
        val highVolumeWorkouts =
            (1..6).map { day ->
                workout(day)
                    .copy(
                        exercises =
                            exerciseIds.map {
                                exercise(
                                    it,
                                    sets = PlanValidationLimits.MAX_SETS_PER_EXERCISE,
                                )
                            }
                    )
            }
        val totalCodes =
            invalidCodes(
                validDraft(workouts = highVolumeWorkouts),
                validContext((1..6).toSet()),
            )
        assertTrue(PlanViolationCode.WEEKLY_VOLUME_EXCEEDED in totalCodes)
    }

    @Test
    fun `same primary muscle requires a full rest day between workouts`() {
        val workouts =
            listOf(
                workout(1, ExerciseCatalogIds.PUSH_UPS),
                workout(2, ExerciseCatalogIds.INCLINE_DUMBBELL_PRESS),
            )

        val codes =
            invalidCodes(
                validDraft(workouts = workouts),
                validContext(setOf(1, 2)),
            )

        assertTrue(PlanViolationCode.INSUFFICIENT_MUSCLE_REST in codes)
    }

    @Test
    fun `exact-exercise load progression allows ten percent or two point five kg`() {
        val context =
            validContext(setOf(1))
                .copy(
                    recentExerciseLoads =
                        listOf(
                            RecentExerciseLoad(ExerciseCatalogIds.BARBELL_CURLS, 10.0),
                            RecentExerciseLoad(ExerciseCatalogIds.BARBELL_BENCH_PRESS, 100.0),
                        )
                )

        assertValid(
            validDraft(
                workouts =
                    listOf(
                        workout(1)
                            .copy(
                                exercises =
                                    listOf(
                                        exercise(ExerciseCatalogIds.BARBELL_CURLS, weightKg = 12.5)
                                    )
                            )
                    )
            ),
            context,
        )
        assertValid(
            validDraft(
                workouts =
                    listOf(
                        workout(1)
                            .copy(
                                exercises =
                                    listOf(
                                        exercise(
                                            ExerciseCatalogIds.BARBELL_BENCH_PRESS,
                                            weightKg = 110.0,
                                        )
                                    )
                            )
                    )
            ),
            context,
        )

        val tooHeavy =
            validDraft(
                workouts =
                    listOf(
                        workout(1)
                            .copy(
                                exercises =
                                    listOf(
                                        exercise(
                                            ExerciseCatalogIds.BARBELL_BENCH_PRESS,
                                            weightKg = 110.1,
                                        )
                                    )
                            )
                    )
            )
        assertTrue(PlanViolationCode.UNSAFE_LOAD_PROGRESSION in invalidCodes(tooHeavy, context))
    }

    @Test
    fun `missing exact-exercise history does not invent a progression limit`() {
        val context =
            validContext(setOf(1))
                .copy(
                    recentExerciseLoads =
                        listOf(RecentExerciseLoad(ExerciseCatalogIds.PUSH_UPS, 0.0))
                )
        val draft =
            validDraft(
                workouts =
                    listOf(
                        workout(1)
                            .copy(
                                exercises =
                                    listOf(
                                        exercise(
                                            ExerciseCatalogIds.BARBELL_BENCH_PRESS,
                                            weightKg = 150.0
                                        )
                                    )
                            )
                    )
            )

        assertValid(draft, context)
    }

    @Test
    fun `revalidation issues a fresh token without mutating the draft`() {
        val draft = validDraft()
        val first = validator.validate(draft, validContext()) as PlanValidationResult.Valid
        timeProvider.advanceBy(Duration.ofSeconds(1))

        val second = validator.validate(draft, validContext()) as PlanValidationResult.Valid

        assertEquals(draft, first.validatedPlan.draft)
        assertEquals(draft, second.validatedPlan.draft)
        assertTrue(second.validatedPlan.validatedAt > first.validatedPlan.validatedAt)
    }

    private fun assertValid(
        draft: PlanDraft,
        context: PlanValidationContext,
    ) {
        val result = validator.validate(draft, context)
        assertTrue("Expected valid but was $result", result is PlanValidationResult.Valid)
    }

    private fun invalidCodes(
        draft: PlanDraft,
        context: PlanValidationContext = validContext(),
    ): Set<PlanViolationCode> {
        val result = validator.validate(draft, context)
        assertTrue("Expected invalid but was $result", result is PlanValidationResult.Invalid)
        return (result as PlanValidationResult.Invalid).violations.map { it.code }.toSet()
    }

    private fun validContext(selectedDays: Set<Int> = setOf(1, 3, 5)) =
        PlanValidationContext(
            expectedTargetWeekStart = targetMonday,
            invokedEngineType = PlanningEngineType.ON_DEVICE_AI,
            selectedDays = selectedDays,
            experience = TrainingExperience.INTERMEDIATE,
            availableEquipment = allEquipment,
        )

    private fun validDraft(
        workouts: List<WorkoutDraft> =
            listOf(
                workout(1, ExerciseCatalogIds.HAMMER_CURLS),
                workout(3, ExerciseCatalogIds.PUSH_UPS),
                workout(5, ExerciseCatalogIds.GOBLET_SQUATS),
            ),
        engineType: PlanningEngineType = PlanningEngineType.ON_DEVICE_AI,
    ) =
        PlanDraft(
            targetWeekStart = targetMonday,
            workouts = workouts,
            rationale = "A balanced week.",
            providerMetadata =
                PlanningProviderMetadata(
                    engineType = engineType,
                    generationDurationMillis = 25,
                ),
        )

    private fun workout(
        day: Int,
        exerciseIds: List<ExerciseCatalogId>,
    ) =
        WorkoutDraft(
            dayOfWeek = day,
            scheduledDate = targetMonday.plusDays((day - 1).toLong()),
            title = "Day $day",
            exercises = exerciseIds.map(::exercise),
        )

    private fun workout(day: Int) = workout(day, emptyList())

    private fun workout(
        day: Int,
        exerciseId: ExerciseCatalogId,
    ) = workout(day, listOf(exerciseId))

    private fun exercise(
        id: ExerciseCatalogId,
        sets: Int = 3,
        reps: Int = 10,
        weightKg: Double = 0.0,
    ) =
        ExerciseDraft(
            catalogId = id,
            sets = sets,
            reps = reps,
            targetWeightKg = weightKg,
        )
}
