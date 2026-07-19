package com.example.ironpath.domain.planner

import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeSource

@Singleton
class FakeAiPlanningEngine @Inject constructor(private val exerciseCatalog: ExerciseCatalog) :
    PlanningEngine {
    override val type = PlanningEngineType.DEBUG_FAKE_AI

    override suspend fun generate(request: PlanningRequest): PlanningResult {
        requestViolations(request).takeIf(List<String>::isNotEmpty)?.let { violations ->
            return PlanningResult.Failure(PlanningFailure.InvalidRequest(violations))
        }

        val candidates =
            exerciseCatalog.entries.filter { entry ->
                entry.allowedInAiDraft &&
                    entry.requiredEquipment.all(request.intake.availableEquipment::contains) &&
                    (request.intake.experience != TrainingExperience.BEGINNER ||
                        entry.beginnerSuitable) &&
                    entry.cautionTags.none(request.intake.forbiddenCautionTags::contains)
            }
        if (candidates.isEmpty()) {
            return PlanningResult.Failure(
                PlanningFailure.InvalidRequest(
                    listOf("No catalog exercises match the selected equipment and constraints")
                )
            )
        }

        val startedAt = TimeSource.Monotonic.markNow()
        val selectedEntries =
            selectEntries(request.selectedDays.sorted(), candidates)
                ?: return PlanningResult.Failure(
                    PlanningFailure.InvalidRequest(
                        listOf("The selected constraints cannot produce a valid training schedule")
                    )
                )
        val workouts =
            request.selectedDays.sorted().mapIndexed { index, dayOfWeek ->
                val entry = selectedEntries[index]
                WorkoutDraft(
                    dayOfWeek = dayOfWeek,
                    scheduledDate = request.targetWeekStart.plusDays((dayOfWeek - 1).toLong()),
                    title = "${request.goal.displayLabel} ${index + 1}",
                    exercises =
                        listOf(
                            ExerciseDraft(
                                catalogId = entry.id,
                                sets = if (request.goal == PlanningGoal.MAINTENANCE) 2 else 3,
                                reps = if (request.goal == PlanningGoal.STRENGTH) 6 else 10,
                                targetWeightKg = 0.0,
                            )
                        ),
                )
            }

        return PlanningResult.Success(
            PlanDraft(
                targetWeekStart = request.targetWeekStart,
                workouts = workouts,
                rationale = "A balanced draft based on your structured intake.",
                providerMetadata =
                    PlanningProviderMetadata(
                        engineType = type,
                        generationDurationMillis = startedAt.elapsedNow().inWholeMilliseconds,
                    ),
            )
        )
    }

    private fun selectEntries(
        selectedDays: List<Int>,
        candidates: List<ExerciseCatalogEntry>,
    ): List<ExerciseCatalogEntry>? {
        val selected = mutableListOf<ExerciseCatalogEntry>()
        selectedDays.forEachIndexed { index, day ->
            val previousDay = selectedDays.getOrNull(index - 1)
            val previousMuscle = selected.lastOrNull()?.primaryMuscleGroup
            val entry =
                candidates.firstOrNull { candidate ->
                    previousDay == null ||
                        day - previousDay > 1 ||
                        candidate.primaryMuscleGroup in restExemptMuscleGroups ||
                        candidate.primaryMuscleGroup != previousMuscle
                } ?: return null
            selected += entry
        }
        return selected
    }

    private fun requestViolations(request: PlanningRequest): List<String> = buildList {
        if (request.targetWeekStart.dayOfWeek != DayOfWeek.MONDAY) {
            add("Target week must start on Monday")
        }
        if (request.selectedDays.isEmpty()) {
            add("At least one training day is required")
        }
        if (request.selectedDays.size > PlanValidationLimits.MAX_TRAINING_DAYS) {
            add("Choose no more than six training days")
        }
        if (request.selectedDays.any { it !in 1..7 }) {
            add("Training days must use ISO values 1 through 7")
        }
    }

    private companion object {
        val restExemptMuscleGroups = setOf(PrimaryMuscleGroup.CORE, PrimaryMuscleGroup.FULL_BODY)
    }
}
