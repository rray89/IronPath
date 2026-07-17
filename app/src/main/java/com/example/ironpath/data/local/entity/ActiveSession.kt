package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "active_sessions")
data class ActiveSession(
    @PrimaryKey val id: String,
    val sourcePlannedWorkoutId: String,
    val workoutTitle: String,
    val startedAt: Long,
    val lastUpdatedAt: Long,
)
