package com.example.ironpath.ui.testing

import java.util.Locale

/** Stable semantics identifiers for fields and repeated rows whose visible text is ambiguous. */
object TestTags {
    const val APP_CONTENT = "app_content"
    const val ENTRY_GET_STARTED = "entry_get_started"

    const val RECORD_NAME = "record_name"
    const val RECORD_WEIGHT = "record_weight"
    const val RECORD_DATE = "record_date"
    const val RECORD_NOTE = "record_note"

    const val PLAN_LOADING = "plan_loading"
    const val PLAN_GENERATE = "plan_generate"
    const val PLAN_GOAL_GROUP = "plan_goal_group"
    const val PLAN_GENERATE_AI = "plan_generate_ai"
    const val PLAN_AI_GENERATING = "plan_ai_generating"
    const val PLAN_AI_REVIEW = "plan_ai_review"
    const val PLAN_AI_RATIONALE = "plan_ai_rationale"
    const val PLAN_AI_WARNING = "plan_ai_warning"
    const val PLAN_AI_DISCLAIMER = "plan_ai_disclaimer"
    const val PLAN_AI_FALLBACK_REASON = "plan_ai_fallback_reason"
    const val PLAN_AI_ACCEPT = "plan_ai_accept"
    const val PLAN_AI_REGENERATE = "plan_ai_regenerate"
    const val PLAN_AI_RULE_FALLBACK = "plan_ai_rule_fallback"
    const val PLAN_AI_EDITOR = "plan_ai_editor"
    const val PLAN_AI_EDITOR_LIST = "plan_ai_editor_list"
    const val PLAN_AI_EDITOR_SETS = "plan_ai_editor_sets"
    const val PLAN_AI_EDITOR_REPS = "plan_ai_editor_reps"
    const val PLAN_AI_EDITOR_WEIGHT = "plan_ai_editor_weight"
    const val PLAN_AI_EDITOR_CONFIRM = "plan_ai_editor_confirm"
    const val PLAN_REMOTE_AI_LAB = "plan_remote_ai_lab"
    const val PLAN_REMOTE_AI_TOGGLE = "plan_remote_ai_toggle"
    const val PLAN_REMOTE_AI_KEY = "plan_remote_ai_key"
    const val PLAN_INJURY_NOTES = "plan_injury_notes"
    const val PLAN_PREFERENCES = "plan_preferences"
    const val PLAN_DISLIKES = "plan_dislikes"
    const val HOME_LOADING = "home_loading"
    const val HOME_WEEK_COMPLETE = "home_week_complete"
    const val ACTIVE_LOADING = "active_loading"
    const val ACTIVE_COMPLETE = "active_complete"
    const val WORKOUT_PREVIEW_LOADING = "workout_preview_loading"

    fun bottomNav(route: String) = "bottom_nav_$route"

    fun historyTab(tab: String) = "history_tab_${tab.lowercase(Locale.ROOT)}"

    fun setWeight(id: String) = "set_weight_$id"

    fun setReps(id: String) = "set_reps_$id"

    fun set(id: String) = "set_$id"

    fun workout(id: String) = "workout_$id"

    fun record(id: String) = "record_$id"

    fun log(id: String) = "log_$id"

    fun planGoal(goal: String) = "plan_goal_$goal"

    fun planExperience(experience: String) = "plan_experience_$experience"

    fun planEquipment(equipment: String) = "plan_equipment_$equipment"

    fun planCaution(caution: String) = "plan_caution_$caution"

    fun planDay(dayOfWeek: Int) = "plan_day_$dayOfWeek"

    fun planReviewDay(workoutId: String) = "plan_review_day_$workoutId"

    fun planExercise(exerciseId: String) = "plan_exercise_$exerciseId"

    fun planAiExercise(dayOfWeek: Int, exerciseId: String) =
        "plan_ai_exercise_${dayOfWeek}_$exerciseId"

    fun planAiAddExercise(dayOfWeek: Int) = "plan_ai_add_exercise_$dayOfWeek"

    fun planAiCatalogExercise(exerciseId: String) = "plan_ai_catalog_exercise_$exerciseId"
}
