package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workout_logs")
data class WorkoutLog(
    @PrimaryKey val id: String,
    val title: String,
    val sourcePlannedWorkoutId: String? = null,
    val startedAt: Long,
    val completedAt: Long,
    val durationMinutes: Int,
    val exerciseCount: Int,
)
