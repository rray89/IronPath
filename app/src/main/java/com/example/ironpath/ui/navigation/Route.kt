package com.example.ironpath.ui.navigation

import android.net.Uri

object Route {
    const val ENTRY = "entry"
    const val HOME = "home"
    const val PLAN = "plan"
    const val ACTIVE = "active"
    const val HISTORY = "history"
    const val DEV_TOOLS = "dev_tools"
    const val WORKOUT_ID_ARG = "workoutId"
    const val WORKOUT_PREVIEW = "workout_preview/{$WORKOUT_ID_ARG}"
    const val WORKOUT_LOG_ID_ARG = "workoutLogId"
    const val WORKOUT_LOG_READ_ONLY_ARG = "readOnly"
    const val WORKOUT_LOG_DETAIL =
        "workout_log/{$WORKOUT_LOG_ID_ARG}?$WORKOUT_LOG_READ_ONLY_ARG={$WORKOUT_LOG_READ_ONLY_ARG}"

    fun workoutPreview(workoutId: String): String = "workout_preview/${Uri.encode(workoutId)}"

    fun workoutLogDetail(
        logId: String,
        readOnly: Boolean = false,
    ): String = "workout_log/${Uri.encode(logId)}?$WORKOUT_LOG_READ_ONLY_ARG=$readOnly"
}
