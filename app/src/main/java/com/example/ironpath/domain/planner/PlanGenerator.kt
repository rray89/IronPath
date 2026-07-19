package com.example.ironpath.domain.planner

import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.domain.time.TimeProvider
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

data class GeneratedPlan(
    val plan: WeeklyPlan,
    val workouts: List<PlannedWorkout>,
    val exercises: List<PlannedExercise>,
)

@Singleton
class PlanGenerator
@Inject
internal constructor(
    private val timeProvider: TimeProvider,
    private val planFactory: RuleBasedPlanFactory,
    private val entityMapper: PlanEntityMapper,
) {

    fun generate(
        goal: PlanningGoal,
        selectedDays: Set<Int>, // 1=Mon..7=Sun (ISO)
    ): GeneratedPlan {
        val today = timeProvider.today()
        // Always generate for the upcoming Monday-Sunday week, never the current week
        val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        val draft =
            planFactory.create(
                request =
                    PlanningRequest(
                        targetWeekStart = nextMonday,
                        intake = PlanningIntake(goal = goal, selectedDays = selectedDays),
                    ),
                providerMetadata =
                    PlanningProviderMetadata(
                        engineType = PlanningEngineType.RULE_BASED,
                        generationDurationMillis = 0,
                    ),
            )

        return entityMapper.mapLegacyRuleBasedDraft(draft)
    }
}
