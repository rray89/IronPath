package com.example.ironpath.ui.screens

/** Transient feedback shared by the two workout-start entry points. */
data class WorkoutStartUiState(
    val isStarting: Boolean = false,
    val error: String? = null,
    val hasExistingSession: Boolean = false,
)
