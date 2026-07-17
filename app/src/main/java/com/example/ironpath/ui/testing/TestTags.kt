package com.example.ironpath.ui.testing

import java.util.Locale

/** Stable semantics identifiers for fields and repeated rows whose visible text is ambiguous. */
object TestTags {
    const val ENTRY_GET_STARTED = "entry_get_started"

    const val RECORD_NAME = "record_name"
    const val RECORD_WEIGHT = "record_weight"
    const val RECORD_DATE = "record_date"
    const val RECORD_NOTE = "record_note"

    const val PLAN_LOADING = "plan_loading"
    const val PLAN_GENERATE = "plan_generate"
    const val PLAN_GOAL_GROUP = "plan_goal_group"
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

    fun planDay(dayOfWeek: Int) = "plan_day_$dayOfWeek"

    fun planReviewDay(workoutId: String) = "plan_review_day_$workoutId"

    fun planExercise(exerciseId: String) = "plan_exercise_$exerciseId"
}
