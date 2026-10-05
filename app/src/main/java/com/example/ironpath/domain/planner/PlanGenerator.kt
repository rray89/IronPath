package com.example.ironpath.domain.planner

import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.domain.time.TimeProvider
import java.time.LocalDate
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
        targetWeekStart: LocalDate = nextPlanningWeekStart(timeProvider.today()),
    ): GeneratedPlan {
        require(targetWeekStart.dayOfWeek == java.time.DayOfWeek.MONDAY)
        val draft =
            planFactory.create(
                request =
                    PlanningRequest(
                        targetWeekStart = targetWeekStart,
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
