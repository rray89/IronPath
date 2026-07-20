package com.example.ironpath.domain.planner

import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeSource

@Singleton
class RuleBasedPlanningEngine
@Inject
constructor(
    private val planFactory: RuleBasedPlanFactory,
    private val planValidator: PlanValidator,
) : PlanningEngine {
    override val type = PlanningEngineType.RULE_BASED

    override suspend fun generate(request: PlanningRequest): PlanningResult {
        val violations = request.basicViolations()
        if (violations.isNotEmpty()) {
            return PlanningResult.Failure(PlanningFailure.InvalidRequest(violations))
        }

        val startedAt = TimeSource.Monotonic.markNow()
        val draftResult =
            planFactory.createConstraintAware(
                request = request,
                providerMetadata =
                    PlanningProviderMetadata(
                        engineType = type,
                        generationDurationMillis = 0,
                    ),
            )
        if (draftResult is RuleBasedDraftResult.Failure) {
            return PlanningResult.Failure(PlanningFailure.InvalidRequest(draftResult.violations))
        }
        val draft = (draftResult as RuleBasedDraftResult.Success).draft
        val generationDurationMillis = startedAt.elapsedNow().inWholeMilliseconds

        val completedDraft =
            draft.copy(
                providerMetadata =
                    draft.providerMetadata.copy(
                        generationDurationMillis = generationDurationMillis,
                    )
            )
        return when (
            val validation = planValidator.validate(completedDraft, request.validationContext(type))
        ) {
            is PlanValidationResult.Valid -> PlanningResult.Success(validation.validatedPlan.draft)
            is PlanValidationResult.Invalid ->
                PlanningResult.Failure(
                    PlanningFailure.InvalidRequest(
                        validation.violations.map(PlanViolation::message).distinct()
                    )
                )
        }
    }
}

internal sealed interface RuleBasedDraftResult {
    data class Success(val draft: PlanDraft) : RuleBasedDraftResult

    data class Failure(val violations: List<String>) : RuleBasedDraftResult
}

@Singleton
class RuleBasedPlanFactory
@Inject
constructor(
    private val exerciseCatalog: ExerciseCatalog,
    private val exerciseEligibilityPolicy: ExerciseEligibilityPolicy,
) {
    internal constructor(
        exerciseCatalog: ExerciseCatalog
    ) : this(exerciseCatalog, ExerciseEligibilityPolicy(exerciseCatalog))

    /** Legacy local planner path. AI/fallback callers must use [createConstraintAware]. */
    fun create(
        request: PlanningRequest,
        providerMetadata: PlanningProviderMetadata,
    ): PlanDraft {
        val sortedDays = request.selectedDays.sorted()
        val templates = RuleBasedWorkoutTemplates.forGoal(request.goal, sortedDays.size)
        val workouts =
            sortedDays.mapIndexed { index, dayOfWeek ->
                val template = templates[index]
                WorkoutDraft(
                    dayOfWeek = dayOfWeek,
                    scheduledDate = request.targetWeekStart.plusDays((dayOfWeek - 1).toLong()),
                    title = template.title,
                    exercises =
                        template.exercises.map { exercise ->
                            // Trusted templates must never drift away from the canonical catalog.
                            exerciseCatalog.require(exercise.catalogId)
                            ExerciseDraft(
                                catalogId = exercise.catalogId,
                                sets = exercise.sets,
                                reps = exercise.reps,
                                targetWeightKg = exercise.weightKg,
                            )
                        },
                )
            }

        return PlanDraft(
            targetWeekStart = request.targetWeekStart,
            workouts = workouts,
            providerMetadata = providerMetadata,
        )
    }

    internal fun createConstraintAware(
        request: PlanningRequest,
        providerMetadata: PlanningProviderMetadata,
    ): RuleBasedDraftResult {
        val context = request.validationContext(PlanningEngineType.RULE_BASED)
        val eligibleEntries = exerciseEligibilityPolicy.eligibleEntries(context)
        val warnings = mutableListOf<String>()
        val emptyDays = mutableListOf<Int>()
        val sortedDays = request.selectedDays.sorted()
        val templates = selectRecoveryAwareTemplates(request.goal, sortedDays)
        val workouts =
            sortedDays.mapIndexed { index, dayOfWeek ->
                val template = templates[index]
                val usedIds = mutableSetOf<ExerciseCatalogId>()
                val exercises =
                    template.exercises.mapNotNull { prescription ->
                        val original = exerciseCatalog.require(prescription.catalogId)
                        val originalIsEligible =
                            exerciseEligibilityPolicy.evaluate(original, context).isEligible &&
                                original.id !in usedIds
                        val selected =
                            if (originalIsEligible) {
                                original
                            } else {
                                eligibleEntries.firstOrNull { candidate ->
                                    candidate.primaryMuscleGroup == original.primaryMuscleGroup &&
                                        candidate.id !in usedIds
                                }
                            }

                        if (selected == null) {
                            warnings +=
                                "Removed ${original.displayName} because no catalog exercise " +
                                    "matched this intake."
                            null
                        } else {
                            usedIds += selected.id
                            if (selected.id != original.id) {
                                warnings +=
                                    "Replaced ${original.displayName} with ${selected.displayName} " +
                                        "to match this intake."
                            }
                            ExerciseDraft(
                                catalogId = selected.id,
                                sets = prescription.sets,
                                reps = prescription.reps,
                                targetWeightKg =
                                    prescription.targetWeightFor(original, selected, request),
                            )
                        }
                    }
                if (exercises.isEmpty()) emptyDays += dayOfWeek
                WorkoutDraft(
                    dayOfWeek = dayOfWeek,
                    scheduledDate = request.targetWeekStart.plusDays((dayOfWeek - 1).toLong()),
                    title = template.title,
                    exercises = exercises,
                )
            }

        if (emptyDays.isNotEmpty()) {
            return RuleBasedDraftResult.Failure(
                listOf(
                    "No catalog exercises satisfy the intake for workout day " +
                        emptyDays.joinToString().plus(".")
                )
            )
        }

        val volumeAdjustedWorkouts = capRepeatedMuscleVolume(workouts, warnings)
        return RuleBasedDraftResult.Success(
            PlanDraft(
                targetWeekStart = request.targetWeekStart,
                workouts = volumeAdjustedWorkouts,
                warnings = warnings,
                providerMetadata = providerMetadata,
            )
        )
    }

    private fun selectRecoveryAwareTemplates(
        goal: PlanningGoal,
        sortedDays: List<Int>,
    ): List<RuleBasedWorkoutTemplate> {
        val pool = RuleBasedWorkoutTemplates.poolForGoal(goal)
        val selected = mutableListOf<RuleBasedWorkoutTemplate>()

        fun search(index: Int): Boolean {
            if (index == sortedDays.size) return true
            val previous = selected.lastOrNull()
            val isAdjacent = index > 0 && sortedDays[index] - sortedDays[index - 1] == 1
            val orderedCandidates =
                pool.indices.map { offset -> pool[(index + offset) % pool.size] }
            orderedCandidates.forEach { candidate ->
                if (
                    isAdjacent &&
                        previous != null &&
                        previous
                            .trainingMuscles()
                            .intersect(candidate.trainingMuscles())
                            .isNotEmpty()
                ) {
                    return@forEach
                }
                selected += candidate
                if (search(index + 1)) return true
                selected.removeAt(selected.lastIndex)
            }
            return false
        }

        check(search(0)) { "No recovery-aware template ordering exists for $goal and $sortedDays" }
        return selected.toList()
    }

    private fun RuleBasedWorkoutTemplate.trainingMuscles(): Set<PrimaryMuscleGroup> =
        exercises
            .map { exerciseCatalog.require(it.catalogId).primaryMuscleGroup }
            .filterNot { it == PrimaryMuscleGroup.CORE || it == PrimaryMuscleGroup.FULL_BODY }
            .toSet()

    private fun capRepeatedMuscleVolume(
        workouts: List<WorkoutDraft>,
        warnings: MutableList<String>,
    ): List<WorkoutDraft> {
        val adjusted = workouts.toMutableList()
        val setsByMuscle =
            workouts
                .flatMap(WorkoutDraft::exercises)
                .groupBy { exerciseCatalog.require(it.catalogId).primaryMuscleGroup }
                .mapValues { (_, exercises) -> exercises.sumOf(ExerciseDraft::sets) }

        setsByMuscle.forEach { (muscle, totalSets) ->
            var excess = totalSets - PlanValidationLimits.MAX_WEEKLY_SETS_PER_PRIMARY_MUSCLE
            if (excess <= 0) return@forEach

            for (workoutIndex in adjusted.indices.reversed()) {
                val workout = adjusted[workoutIndex]
                val exercises = workout.exercises.toMutableList()
                for (exerciseIndex in exercises.indices.reversed()) {
                    val exercise = exercises[exerciseIndex]
                    if (exerciseCatalog.require(exercise.catalogId).primaryMuscleGroup != muscle) {
                        continue
                    }
                    val reduction = minOf(excess, exercise.sets - 1)
                    if (reduction > 0) {
                        exercises[exerciseIndex] = exercise.copy(sets = exercise.sets - reduction)
                        excess -= reduction
                    }
                    if (excess == 0) break
                }
                adjusted[workoutIndex] = workout.copy(exercises = exercises)
                if (excess == 0) break
            }

            if (excess == 0) {
                warnings +=
                    "Reduced repeated ${muscle.name.lowercase()} volume to preserve recovery."
            }
        }
        return adjusted
    }

    private fun RuleBasedExerciseTemplate.targetWeightFor(
        original: ExerciseCatalogEntry,
        selected: ExerciseCatalogEntry,
        request: PlanningRequest,
    ): Double =
        selected.seedTargetLoadKg(
            recentLoads = request.intake.recentTraining.exerciseLoads,
            preferredWeightKg =
                weightKg.takeIf { original.requiredEquipment == selected.requiredEquipment },
        )
}

private fun PlanningRequest.basicViolations(): List<String> = buildList {
    if (targetWeekStart.dayOfWeek != DayOfWeek.MONDAY) {
        add("Target week must start on Monday")
    }
    if (selectedDays.isEmpty()) {
        add("At least one training day is required")
    }
    if (selectedDays.size > PlanValidationLimits.MAX_TRAINING_DAYS) {
        add("Choose no more than six training days")
    }
    if (selectedDays.any { it !in 1..7 }) {
        add("Training days must use ISO values 1 through 7")
    }
}
