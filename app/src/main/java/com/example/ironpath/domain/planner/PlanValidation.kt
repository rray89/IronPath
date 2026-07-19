package com.example.ironpath.domain.planner

import com.example.ironpath.domain.time.TimeProvider
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

enum class TrainingExperience {
    BEGINNER,
    INTERMEDIATE,
    ADVANCED,
}

data class RecentExerciseLoad(
    val catalogId: ExerciseCatalogId,
    val maxWeightKg: Double,
)

data class PlanValidationContext(
    val expectedTargetWeekStart: LocalDate,
    val invokedEngineType: PlanningEngineType,
    val selectedDays: Set<Int>,
    val experience: TrainingExperience,
    val availableEquipment: Set<Equipment>,
    val forbiddenCautionTags: Set<ExerciseCautionTag> = emptySet(),
    val recentExerciseLoads: List<RecentExerciseLoad> = emptyList(),
)

object PlanValidationLimits {
    const val MIN_TRAINING_DAYS = 1
    const val MAX_TRAINING_DAYS = 6
    const val MIN_EXERCISES_PER_DAY = 1
    const val MAX_EXERCISES_PER_DAY = 8
    const val MIN_SETS_PER_EXERCISE = 1
    const val MAX_SETS_PER_EXERCISE = 6
    const val MIN_REPS_PER_SET = 1
    const val MAX_REPS_PER_SET = 30
    const val MIN_WEIGHT_KG = 0.0
    const val MAX_WEIGHT_KG = 300.0
    const val MAX_WEEKLY_SETS = 120
    const val MAX_WEEKLY_SETS_PER_PRIMARY_MUSCLE = 25
    const val MAX_LOAD_INCREASE_FRACTION = 0.10
    const val MIN_LOAD_INCREASE_ALLOWANCE_KG = 2.5
}

object PlanDraftTextLimits {
    const val MAX_RATIONALE_LENGTH = 300
    const val MAX_WARNING_LENGTH = 180
    const val MAX_WARNING_COUNT = 5
}

enum class PlanViolationCode {
    TARGET_WEEK_NOT_MONDAY,
    TARGET_WEEK_MISMATCH,
    INVALID_REQUESTED_DAYS,
    WORKOUT_COUNT_OUT_OF_RANGE,
    WORKOUT_COUNT_MISMATCH,
    SELECTED_DAYS_MISMATCH,
    DUPLICATE_WORKOUT_DAY,
    DAY_OUT_OF_RANGE,
    DATE_OUTSIDE_TARGET_WEEK,
    DATE_DAY_MISMATCH,
    WORKOUT_IN_PAST,
    BLANK_WORKOUT_TITLE,
    EXERCISE_COUNT_OUT_OF_RANGE,
    DUPLICATE_EXERCISE,
    UNKNOWN_EXERCISE,
    INVALID_SET_COUNT,
    INVALID_REP_COUNT,
    INVALID_WEIGHT,
    MISSING_TARGET_LOAD,
    MISSING_EQUIPMENT,
    AI_EXERCISE_NOT_ALLOWED,
    BEGINNER_EXERCISE_NOT_ALLOWED,
    FORBIDDEN_MOVEMENT,
    WEEKLY_VOLUME_EXCEEDED,
    MUSCLE_VOLUME_EXCEEDED,
    INSUFFICIENT_MUSCLE_REST,
    UNSAFE_LOAD_PROGRESSION,
    INVALID_PROVIDER_METADATA,
}

data class PlanViolation(
    val code: PlanViolationCode,
    val message: String,
    val workoutDay: Int? = null,
    val exerciseCatalogId: ExerciseCatalogId? = null,
)

sealed interface PlanValidationResult {
    data class Valid(val validatedPlan: ValidatedPlanDraft) : PlanValidationResult

    data class Invalid(val violations: List<PlanViolation>) : PlanValidationResult
}

class ValidatedPlanDraft
private constructor(
    val draft: PlanDraft,
    val context: PlanValidationContext,
    val validatedAt: Instant,
) {
    internal companion object {
        fun create(
            draft: PlanDraft,
            context: PlanValidationContext,
            validatedAt: Instant,
        ) = ValidatedPlanDraft(draft, context, validatedAt)
    }
}

@Singleton
class PlanValidator
@Inject
constructor(
    private val exerciseCatalog: ExerciseCatalog,
    private val timeProvider: TimeProvider,
    private val exerciseEligibilityPolicy: ExerciseEligibilityPolicy,
) {
    internal constructor(
        exerciseCatalog: ExerciseCatalog,
        timeProvider: TimeProvider,
    ) : this(exerciseCatalog, timeProvider, ExerciseEligibilityPolicy(exerciseCatalog))

    fun validate(
        draft: PlanDraft,
        context: PlanValidationContext,
    ): PlanValidationResult {
        val draftSnapshot = draft.validationSnapshot()
        val contextSnapshot = context.validationSnapshot()
        val violations = mutableListOf<PlanViolation>()

        validatePlanShape(draftSnapshot, contextSnapshot, violations)
        draftSnapshot.workouts.forEach { workout ->
            validateWorkout(draftSnapshot, workout, contextSnapshot, violations)
        }
        validateWeeklyVolume(draftSnapshot, violations)
        validateMuscleRest(draftSnapshot, contextSnapshot, violations)

        return if (violations.isEmpty()) {
            PlanValidationResult.Valid(
                ValidatedPlanDraft.create(draftSnapshot, contextSnapshot, timeProvider.now())
            )
        } else {
            PlanValidationResult.Invalid(violations.distinct())
        }
    }

    private fun validatePlanShape(
        draft: PlanDraft,
        context: PlanValidationContext,
        violations: MutableList<PlanViolation>,
    ) {
        if (draft.targetWeekStart.dayOfWeek != DayOfWeek.MONDAY) {
            violations.add(
                violation(
                    PlanViolationCode.TARGET_WEEK_NOT_MONDAY,
                    "The target week must start on Monday.",
                )
            )
        }
        if (draft.targetWeekStart != context.expectedTargetWeekStart) {
            violations.add(
                violation(
                    PlanViolationCode.TARGET_WEEK_MISMATCH,
                    "The draft does not match the week selected by the app.",
                )
            )
        }

        val selectedDaysAreValid =
            context.selectedDays.size in
                PlanValidationLimits.MIN_TRAINING_DAYS..PlanValidationLimits.MAX_TRAINING_DAYS &&
                context.selectedDays.all { it in 1..7 }
        if (!selectedDaysAreValid) {
            violations.add(
                violation(
                    PlanViolationCode.INVALID_REQUESTED_DAYS,
                    "Choose between one and six training days in the target week.",
                )
            )
        }

        if (
            draft.workouts.size !in
                PlanValidationLimits.MIN_TRAINING_DAYS..PlanValidationLimits.MAX_TRAINING_DAYS
        ) {
            violations.add(
                violation(
                    PlanViolationCode.WORKOUT_COUNT_OUT_OF_RANGE,
                    "A plan must contain between one and six workouts.",
                )
            )
        }
        if (draft.workouts.size != context.selectedDays.size) {
            violations.add(
                violation(
                    PlanViolationCode.WORKOUT_COUNT_MISMATCH,
                    "The workout count does not match the requested training-day count.",
                )
            )
        }

        val workoutDays = draft.workouts.map(WorkoutDraft::dayOfWeek)
        if (workoutDays.toSet() != context.selectedDays) {
            violations.add(
                violation(
                    PlanViolationCode.SELECTED_DAYS_MISMATCH,
                    "The workout days do not match the selected training days.",
                )
            )
        }
        if (workoutDays.size != workoutDays.toSet().size) {
            violations.add(
                violation(
                    PlanViolationCode.DUPLICATE_WORKOUT_DAY,
                    "Only one workout may be scheduled per day.",
                )
            )
        }
        if (draft.providerMetadata.generationDurationMillis < 0) {
            violations.add(
                violation(
                    PlanViolationCode.INVALID_PROVIDER_METADATA,
                    "Generation duration cannot be negative.",
                )
            )
        }
        if (draft.providerMetadata.engineType != context.invokedEngineType) {
            violations.add(
                violation(
                    PlanViolationCode.INVALID_PROVIDER_METADATA,
                    "The draft provider does not match the invoked planning engine.",
                )
            )
        }
    }

    private fun validateWorkout(
        draft: PlanDraft,
        workout: WorkoutDraft,
        context: PlanValidationContext,
        violations: MutableList<PlanViolation>,
    ) {
        if (workout.dayOfWeek !in 1..7) {
            violations.add(
                violation(
                    PlanViolationCode.DAY_OUT_OF_RANGE,
                    "Workout day must be between Monday and Sunday.",
                    workout,
                )
            )
        }

        val weekEnd = context.expectedTargetWeekStart.plusDays(6)
        if (workout.scheduledDate !in context.expectedTargetWeekStart..weekEnd) {
            violations.add(
                violation(
                    PlanViolationCode.DATE_OUTSIDE_TARGET_WEEK,
                    "Workout date must be inside the target week.",
                    workout,
                )
            )
        }
        if (
            workout.dayOfWeek in 1..7 &&
                workout.scheduledDate !=
                    context.expectedTargetWeekStart.plusDays((workout.dayOfWeek - 1).toLong())
        ) {
            violations.add(
                violation(
                    PlanViolationCode.DATE_DAY_MISMATCH,
                    "Workout date does not match its day of week.",
                    workout,
                )
            )
        }
        if (workout.scheduledDate.isBefore(timeProvider.today())) {
            violations.add(
                violation(
                    PlanViolationCode.WORKOUT_IN_PAST,
                    "Workout dates cannot be in the past.",
                    workout,
                )
            )
        }
        if (workout.title.isBlank()) {
            violations.add(
                violation(
                    PlanViolationCode.BLANK_WORKOUT_TITLE,
                    "Every workout needs a title.",
                    workout,
                )
            )
        }
        if (
            workout.exercises.size !in
                PlanValidationLimits.MIN_EXERCISES_PER_DAY..PlanValidationLimits
                        .MAX_EXERCISES_PER_DAY
        ) {
            violations.add(
                violation(
                    PlanViolationCode.EXERCISE_COUNT_OUT_OF_RANGE,
                    "A workout must contain between one and eight exercises.",
                    workout,
                )
            )
        }

        val exerciseIds = workout.exercises.map(ExerciseDraft::catalogId)
        if (exerciseIds.size != exerciseIds.toSet().size) {
            violations.add(
                violation(
                    PlanViolationCode.DUPLICATE_EXERCISE,
                    "The same exercise cannot appear twice in one workout.",
                    workout,
                )
            )
        }

        workout.exercises.forEach { exercise ->
            validateExercise(draft, workout, exercise, context, violations)
        }
    }

    private fun validateExercise(
        draft: PlanDraft,
        workout: WorkoutDraft,
        exercise: ExerciseDraft,
        context: PlanValidationContext,
        violations: MutableList<PlanViolation>,
    ) {
        val catalogEntry = exerciseCatalog.find(exercise.catalogId)
        if (catalogEntry == null) {
            violations.add(
                violation(
                    PlanViolationCode.UNKNOWN_EXERCISE,
                    "The draft contains an exercise that is not in the catalog.",
                    workout,
                    exercise,
                )
            )
        }

        if (
            exercise.sets !in
                PlanValidationLimits.MIN_SETS_PER_EXERCISE..PlanValidationLimits
                        .MAX_SETS_PER_EXERCISE
        ) {
            violations.add(
                violation(
                    PlanViolationCode.INVALID_SET_COUNT,
                    "Sets must be between 1 and 6.",
                    workout,
                    exercise,
                )
            )
        }
        if (
            exercise.reps !in
                PlanValidationLimits.MIN_REPS_PER_SET..PlanValidationLimits.MAX_REPS_PER_SET
        ) {
            violations.add(
                violation(
                    PlanViolationCode.INVALID_REP_COUNT,
                    "Reps must be between 1 and 30.",
                    workout,
                    exercise,
                )
            )
        }
        if (
            !exercise.targetWeightKg.isFinite() ||
                exercise.targetWeightKg !in
                    PlanValidationLimits.MIN_WEIGHT_KG..PlanValidationLimits.MAX_WEIGHT_KG
        ) {
            violations.add(
                violation(
                    PlanViolationCode.INVALID_WEIGHT,
                    "Target weight must be a finite value from 0 to 300 kg.",
                    workout,
                    exercise,
                )
            )
        }

        if (catalogEntry == null) return

        if (catalogEntry.requiresTargetLoad() && exercise.targetWeightKg == 0.0) {
            violations.add(
                violation(
                    PlanViolationCode.MISSING_TARGET_LOAD,
                    "Set a target load for ${catalogEntry.displayName} before accepting.",
                    workout,
                    exercise,
                )
            )
        }

        exerciseEligibilityPolicy.evaluate(catalogEntry, context).reasons.forEach { reason ->
            val (code, message) = reason.violationFor(catalogEntry)
            violations.add(
                violation(
                    code,
                    message,
                    workout,
                    exercise,
                )
            )
        }

        validateLoadProgression(workout, exercise, context, violations)
    }

    private fun validateWeeklyVolume(
        draft: PlanDraft,
        violations: MutableList<PlanViolation>,
    ) {
        var totalSets = 0
        val setsByMuscle = mutableMapOf<PrimaryMuscleGroup, Int>()

        draft.workouts.flatMap(WorkoutDraft::exercises).forEach { exercise ->
            if (
                exercise.sets !in
                    PlanValidationLimits.MIN_SETS_PER_EXERCISE..PlanValidationLimits
                            .MAX_SETS_PER_EXERCISE
            ) {
                return@forEach
            }
            totalSets += exercise.sets
            exerciseCatalog.find(exercise.catalogId)?.let { entry ->
                setsByMuscle[entry.primaryMuscleGroup] =
                    setsByMuscle.getOrDefault(entry.primaryMuscleGroup, 0) + exercise.sets
            }
        }

        if (totalSets > PlanValidationLimits.MAX_WEEKLY_SETS) {
            violations.add(
                violation(
                    PlanViolationCode.WEEKLY_VOLUME_EXCEEDED,
                    "The draft exceeds the weekly hard-set limit.",
                )
            )
        }
        setsByMuscle
            .filterValues { it > PlanValidationLimits.MAX_WEEKLY_SETS_PER_PRIMARY_MUSCLE }
            .forEach { (muscle, _) ->
                violations.add(
                    violation(
                        PlanViolationCode.MUSCLE_VOLUME_EXCEEDED,
                        "The draft has too many weekly sets for ${muscle.name.lowercase()}.",
                    )
                )
            }
    }

    private fun validateMuscleRest(
        draft: PlanDraft,
        context: PlanValidationContext,
        violations: MutableList<PlanViolation>,
    ) {
        val validWeek = context.expectedTargetWeekStart..context.expectedTargetWeekStart.plusDays(6)
        val workoutsWithMuscles =
            draft.workouts
                .filter { it.dayOfWeek in 1..7 && it.scheduledDate in validWeek }
                .sortedBy(WorkoutDraft::scheduledDate)
                .map { workout ->
                    workout to
                        workout.exercises
                            .mapNotNull { exerciseCatalog.find(it.catalogId)?.primaryMuscleGroup }
                            .filterNot {
                                it == PrimaryMuscleGroup.CORE || it == PrimaryMuscleGroup.FULL_BODY
                            }
                            .toSet()
                }

        workoutsWithMuscles.forEachIndexed { index, (workout, muscles) ->
            workoutsWithMuscles.drop(index + 1).forEach { (laterWorkout, laterMuscles) ->
                val daysApart =
                    ChronoUnit.DAYS.between(workout.scheduledDate, laterWorkout.scheduledDate)
                if (daysApart > 1L) return@forEach

                muscles.intersect(laterMuscles).forEach { muscle ->
                    violations.add(
                        violation(
                            PlanViolationCode.INSUFFICIENT_MUSCLE_REST,
                            "Schedule a full rest day between ${muscle.name.lowercase()} workouts.",
                            laterWorkout,
                        )
                    )
                }
            }
        }
    }

    private fun validateLoadProgression(
        workout: WorkoutDraft,
        exercise: ExerciseDraft,
        context: PlanValidationContext,
        violations: MutableList<PlanViolation>,
    ) {
        if (!exercise.targetWeightKg.isFinite() || exercise.targetWeightKg < 0) return

        val recentMax =
            context.recentExerciseLoads
                .asSequence()
                .filter { it.catalogId == exercise.catalogId }
                .map(RecentExerciseLoad::maxWeightKg)
                .filter { it.isFinite() && it >= 0 }
                .maxOrNull() ?: return
        val allowedIncrease =
            max(
                PlanValidationLimits.MIN_LOAD_INCREASE_ALLOWANCE_KG,
                recentMax * PlanValidationLimits.MAX_LOAD_INCREASE_FRACTION,
            )
        if (exercise.targetWeightKg > recentMax + allowedIncrease + DOUBLE_TOLERANCE) {
            violations.add(
                violation(
                    PlanViolationCode.UNSAFE_LOAD_PROGRESSION,
                    "Target weight increases too quickly from recent history.",
                    workout,
                    exercise,
                )
            )
        }
    }

    private fun violation(
        code: PlanViolationCode,
        message: String,
        workout: WorkoutDraft? = null,
        exercise: ExerciseDraft? = null,
    ) =
        PlanViolation(
            code = code,
            message = message,
            workoutDay = workout?.dayOfWeek,
            exerciseCatalogId = exercise?.catalogId,
        )

    private companion object {
        const val DOUBLE_TOLERANCE = 0.000_001
    }
}

private fun ExerciseIneligibilityReason.violationFor(
    entry: ExerciseCatalogEntry
): Pair<PlanViolationCode, String> =
    when (this) {
        ExerciseIneligibilityReason.MISSING_EQUIPMENT ->
            PlanViolationCode.MISSING_EQUIPMENT to
                "Required equipment is not available for ${entry.displayName}."
        ExerciseIneligibilityReason.AI_NOT_ALLOWED ->
            PlanViolationCode.AI_EXERCISE_NOT_ALLOWED to
                "${entry.displayName} cannot be represented safely in an AI draft."
        ExerciseIneligibilityReason.BEGINNER_NOT_SUITABLE ->
            PlanViolationCode.BEGINNER_EXERCISE_NOT_ALLOWED to
                "${entry.displayName} is not available in beginner drafts."
        ExerciseIneligibilityReason.FORBIDDEN_MOVEMENT ->
            PlanViolationCode.FORBIDDEN_MOVEMENT to
                "${entry.displayName} conflicts with a forbidden movement."
    }

private fun PlanDraft.validationSnapshot() =
    copy(
        workouts =
            workouts.map { workout ->
                workout.copy(exercises = workout.exercises.map { it.copy() })
            },
        rationale = rationale?.normalizedModelText(PlanDraftTextLimits.MAX_RATIONALE_LENGTH),
        warnings =
            warnings
                .asSequence()
                .map { it.normalizedModelText(PlanDraftTextLimits.MAX_WARNING_LENGTH) }
                .filter(String::isNotBlank)
                .take(PlanDraftTextLimits.MAX_WARNING_COUNT)
                .toList(),
        providerMetadata = providerMetadata.copy(),
    )

private fun String.normalizedModelText(maxLength: Int): String =
    map { character -> if (character.isISOControl()) ' ' else character }
        .joinToString(separator = "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(maxLength)

private fun PlanValidationContext.validationSnapshot() =
    copy(
        selectedDays = selectedDays.toSet(),
        availableEquipment = availableEquipment.toSet(),
        forbiddenCautionTags = forbiddenCautionTags.toSet(),
        recentExerciseLoads = recentExerciseLoads.map { it.copy() },
    )
