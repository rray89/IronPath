package com.example.ironpath.domain.planner

import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID

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

class PlanGenerator {

    fun generate(
        goal: TrainingGoal,
        selectedDays: Set<Int>, // 1=Mon..7=Sun (ISO)
    ): GeneratedPlan {
        val today = LocalDate.now()
        // Always generate for the upcoming Monday-Sunday week, never the current week
        val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        val nextSunday = nextMonday.plusDays(6)

        val planId = UUID.randomUUID().toString()
        val plan =
            WeeklyPlan(
                id = planId,
                startDate = nextMonday.toString(),
                endDate = nextSunday.toString(),
            )

        val sortedDays = selectedDays.sorted()
        val templates = pickTemplates(goal, sortedDays.size)

        val workouts = mutableListOf<PlannedWorkout>()
        val exercises = mutableListOf<PlannedExercise>()

        sortedDays.forEachIndexed { index, dow ->
            val template = templates[index % templates.size]
            val workoutId = UUID.randomUUID().toString()
            val scheduledDate = nextMonday.plusDays((dow - 1).toLong())

            workouts.add(
                PlannedWorkout(
                    id = workoutId,
                    weeklyPlanId = planId,
                    dayOfWeek = dow,
                    scheduledDate = scheduledDate.toString(),
                    title = template.title,
                )
            )

            template.exercises.forEachIndexed { exIndex, ex ->
                exercises.add(
                    PlannedExercise(
                        plannedWorkoutId = workoutId,
                        name = ex.name,
                        sets = ex.sets,
                        reps = ex.reps,
                        weightKg = ex.weightKg,
                        orderIndex = exIndex,
                    )
                )
            }
        }

        return GeneratedPlan(plan, workouts, exercises)
    }
}

// -- Exercise templates --

private data class ExerciseTemplate(
    val name: String,
    val sets: Int,
    val reps: Int,
    val weightKg: Double
)

private data class WorkoutTemplate(val title: String, val exercises: List<ExerciseTemplate>)

private fun pickTemplates(goal: TrainingGoal, dayCount: Int): List<WorkoutTemplate> {
    val pool =
        when (goal) {
            TrainingGoal.Strength -> strengthTemplates
            TrainingGoal.Hypertrophy -> hypertrophyTemplates
            TrainingGoal.Endurance -> enduranceTemplates
            TrainingGoal.Rehab -> rehabTemplates
        }
    // Cycle through templates if more days than templates
    return List(dayCount) { pool[it % pool.size] }
}

private val strengthTemplates =
    listOf(
        WorkoutTemplate(
            "Push A",
            listOf(
                ExerciseTemplate("Barbell Bench Press", 5, 5, 60.0),
                ExerciseTemplate("Overhead Press", 4, 6, 40.0),
                ExerciseTemplate("Tricep Dips", 3, 8, 0.0),
            )
        ),
        WorkoutTemplate(
            "Pull A",
            listOf(
                ExerciseTemplate("Barbell Rows", 5, 5, 60.0),
                ExerciseTemplate("Weighted Pull-ups", 4, 6, 10.0),
                ExerciseTemplate("Barbell Curls", 3, 8, 25.0),
            )
        ),
        WorkoutTemplate(
            "Legs",
            listOf(
                ExerciseTemplate("Barbell Squats", 5, 5, 80.0),
                ExerciseTemplate("Romanian Deadlift", 4, 6, 60.0),
                ExerciseTemplate("Calf Raises", 3, 12, 40.0),
            )
        ),
        WorkoutTemplate(
            "Push B",
            listOf(
                ExerciseTemplate("Incline Dumbbell Press", 4, 8, 25.0),
                ExerciseTemplate("Dumbbell Lateral Raises", 3, 12, 8.0),
                ExerciseTemplate("Tricep Rope Pushdowns", 3, 10, 20.0),
            )
        ),
        WorkoutTemplate(
            "Pull B",
            listOf(
                ExerciseTemplate("Deadlift", 3, 5, 100.0),
                ExerciseTemplate("Lat Pulldowns", 4, 8, 50.0),
                ExerciseTemplate("Face Pulls", 3, 15, 15.0),
            )
        ),
    )

private val hypertrophyTemplates =
    listOf(
        WorkoutTemplate(
            "Chest/Tris",
            listOf(
                ExerciseTemplate("Barbell Bench Press", 4, 10, 50.0),
                ExerciseTemplate("Dumbbell Incline Flys", 3, 12, 12.0),
                ExerciseTemplate("Tricep Rope Pushdowns", 3, 12, 15.0),
            )
        ),
        WorkoutTemplate(
            "Back/Bis",
            listOf(
                ExerciseTemplate("Barbell Rows", 4, 10, 50.0),
                ExerciseTemplate("Lat Pulldowns", 3, 12, 45.0),
                ExerciseTemplate("Barbell Curls", 3, 12, 20.0),
            )
        ),
        WorkoutTemplate(
            "Legs",
            listOf(
                ExerciseTemplate("Barbell Squats", 4, 10, 60.0),
                ExerciseTemplate("Leg Press", 3, 12, 100.0),
                ExerciseTemplate("Calf Raises", 4, 15, 30.0),
            )
        ),
        WorkoutTemplate(
            "Shoulders/Arms",
            listOf(
                ExerciseTemplate("Overhead Press", 4, 10, 30.0),
                ExerciseTemplate("Dumbbell Lateral Raises", 3, 15, 8.0),
                ExerciseTemplate("Hammer Curls", 3, 12, 12.0),
            )
        ),
    )

private val enduranceTemplates =
    listOf(
        WorkoutTemplate(
            "Upper Circuit",
            listOf(
                ExerciseTemplate("Push-ups", 3, 20, 0.0),
                ExerciseTemplate("Dumbbell Rows", 3, 15, 12.0),
                ExerciseTemplate("Shoulder Press", 3, 15, 10.0),
            )
        ),
        WorkoutTemplate(
            "Lower Circuit",
            listOf(
                ExerciseTemplate("Bodyweight Squats", 3, 25, 0.0),
                ExerciseTemplate("Walking Lunges", 3, 20, 0.0),
                ExerciseTemplate("Calf Raises", 3, 20, 0.0),
            )
        ),
        WorkoutTemplate(
            "Full Body",
            listOf(
                ExerciseTemplate("Kettlebell Swings", 3, 20, 16.0),
                ExerciseTemplate("Burpees", 3, 15, 0.0),
                ExerciseTemplate("Plank Hold", 3, 1, 0.0),
            )
        ),
    )

private val rehabTemplates =
    listOf(
        WorkoutTemplate(
            "Upper Mobility",
            listOf(
                ExerciseTemplate("Band Pull-aparts", 3, 15, 0.0),
                ExerciseTemplate("Wall Slides", 3, 12, 0.0),
                ExerciseTemplate("Light Dumbbell Press", 3, 12, 5.0),
            )
        ),
        WorkoutTemplate(
            "Lower Mobility",
            listOf(
                ExerciseTemplate("Goblet Squats", 3, 12, 8.0),
                ExerciseTemplate("Glute Bridges", 3, 15, 0.0),
                ExerciseTemplate("Calf Raises", 3, 15, 0.0),
            )
        ),
    )
