package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "active_sessions")
data class ActiveSession(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sourcePlannedWorkoutId: String,
    val workoutTitle: String,
    val startedAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis(),
)
