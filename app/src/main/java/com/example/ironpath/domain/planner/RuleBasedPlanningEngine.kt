package com.example.ironpath.domain.planner

import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeSource

@Singleton
class RuleBasedPlanningEngine @Inject constructor(private val planFactory: RuleBasedPlanFactory) :
    PlanningEngine {
    override val type = PlanningEngineType.RULE_BASED

    override suspend fun generate(request: PlanningRequest): PlanningResult {
        val violations = request.basicViolations()
        if (violations.isNotEmpty()) {
            return PlanningResult.Failure(PlanningFailure.InvalidRequest(violations))
        }

        val startedAt = TimeSource.Monotonic.markNow()
        val draft =
            planFactory.create(
                request = request,
                providerMetadata =
                    PlanningProviderMetadata(
                        engineType = type,
                        generationDurationMillis = 0,
                    ),
            )
        val generationDurationMillis = startedAt.elapsedNow().inWholeMilliseconds

        return PlanningResult.Success(
            draft.copy(
                providerMetadata =
                    draft.providerMetadata.copy(
                        generationDurationMillis = generationDurationMillis,
                    )
            )
        )
    }
}

@Singleton
class RuleBasedPlanFactory @Inject constructor(private val exerciseCatalog: ExerciseCatalog) {
    /** Creates a draft from trusted input; public engines validate requests before calling this. */
    fun create(
        request: PlanningRequest,
        providerMetadata: PlanningProviderMetadata,
    ): PlanDraft {
        val templates = RuleBasedWorkoutTemplates.forGoal(request.goal, request.selectedDays.size)
        val workouts =
            request.selectedDays.sorted().mapIndexed { index, dayOfWeek ->
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
}

private fun PlanningRequest.basicViolations(): List<String> = buildList {
    if (targetWeekStart.dayOfWeek != DayOfWeek.MONDAY) {
        add("Target week must start on Monday")
    }
    if (selectedDays.isEmpty()) {
        add("At least one training day is required")
    }
    if (selectedDays.any { it !in 1..7 }) {
        add("Training days must use ISO values 1 through 7")
    }
}
