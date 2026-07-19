package com.example.ironpath.domain.planner

enum class PlanningGoal(
    val slug: String,
    val displayLabel: String,
) {
    STRENGTH("strength", "Strength"),
    HYPERTROPHY("hypertrophy", "Hypertrophy"),
    GENERAL_FITNESS("general-fitness", "General fitness"),
    RETURN_TO_ROUTINE("return-to-routine", "Return to routine"),
    MAINTENANCE("maintenance", "Maintenance"),
}

data class PlanningIntake(
    val goal: PlanningGoal,
    val selectedDays: Set<Int>,
    val experience: TrainingExperience = TrainingExperience.INTERMEDIATE,
    val availableEquipment: Set<Equipment> = Equipment.entries.toSet(),
    val forbiddenCautionTags: Set<ExerciseCautionTag> = emptySet(),
    val injuryNotes: String = "",
    val exercisePreferences: String = "",
    val exerciseDislikes: String = "",
    val recentTraining: RecentTrainingSummary = RecentTrainingSummary.EMPTY,
)
