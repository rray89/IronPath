package com.example.ironpath.domain.planner

import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

enum class TrainingGoal {
    Strength,
    Hypertrophy,
    Endurance,
    Rehab
}

data class GeneratedPlan(
    val plan: WeeklyPlan,
    val workouts: List<PlannedWorkout>,
    val exercises: List<PlannedExercise>,
)

@Singleton
class PlanGenerator
@Inject
constructor(
    private val timeProvider: TimeProvider,
    private val idProvider: IdProvider,
    private val planFactory: RuleBasedPlanFactory,
    private val exerciseCatalog: ExerciseCatalog,
) {

    fun generate(
        goal: TrainingGoal,
        selectedDays: Set<Int>, // 1=Mon..7=Sun (ISO)
    ): GeneratedPlan {
        val today = timeProvider.today()
        // Always generate for the upcoming Monday-Sunday week, never the current week
        val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        val nextSunday = nextMonday.plusDays(6)

        val planId = idProvider.newId()
        val plan =
            WeeklyPlan(
                id = planId,
                startDate = nextMonday.toString(),
                endDate = nextSunday.toString(),
                createdAt = timeProvider.epochMillis(),
            )

        val draft =
            planFactory.create(
                request =
                    PlanningRequest(
                        targetWeekStart = nextMonday,
                        goal = goal,
                        selectedDays = selectedDays,
                    ),
                providerMetadata =
                    PlanningProviderMetadata(
                        engineType = PlanningEngineType.RULE_BASED,
                        generationDurationMillis = 0,
                    ),
            )

        val workouts = mutableListOf<PlannedWorkout>()
        val exercises = mutableListOf<PlannedExercise>()

        draft.workouts.forEach { workoutDraft ->
            val workoutId = idProvider.newId()

            workouts.add(
                PlannedWorkout(
                    id = workoutId,
                    weeklyPlanId = planId,
                    dayOfWeek = workoutDraft.dayOfWeek,
                    scheduledDate = workoutDraft.scheduledDate.toString(),
                    title = workoutDraft.title,
                )
            )

            workoutDraft.exercises.forEachIndexed { exerciseIndex, exerciseDraft ->
                val catalogEntry = exerciseCatalog.require(exerciseDraft.catalogId)
                exercises.add(
                    PlannedExercise(
                        id = idProvider.newId(),
                        plannedWorkoutId = workoutId,
                        name = catalogEntry.displayName,
                        sets = exerciseDraft.sets,
                        reps = exerciseDraft.reps,
                        weightKg = exerciseDraft.targetWeightKg,
                        orderIndex = exerciseIndex,
                    )
                )
            }
        }

        return GeneratedPlan(plan, workouts, exercises)
    }
}
