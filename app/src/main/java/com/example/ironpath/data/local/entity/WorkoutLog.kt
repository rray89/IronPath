package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "workout_logs")
data class WorkoutLog(
  @PrimaryKey val id: String = UUID.randomUUID().toString(),
  val title: String,
  val sourcePlannedWorkoutId: String? = null,
  val startedAt: Long,
  val completedAt: Long,
  val durationMinutes: Int,
  val exerciseCount: Int,
)
