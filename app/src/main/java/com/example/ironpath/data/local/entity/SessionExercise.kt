package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
  tableName = "session_exercises",
  foreignKeys =
    [
      ForeignKey(
        entity = ActiveSession::class,
        parentColumns = ["id"],
        childColumns = ["activeSessionId"],
        onDelete = ForeignKey.CASCADE,
      ),
    ],
  indices = [Index("activeSessionId")],
)
data class SessionExercise(
  @PrimaryKey val id: String = UUID.randomUUID().toString(),
  val activeSessionId: String,
  val name: String,
  val plannedSets: Int,
  val plannedReps: Int,
  val plannedWeightKg: Double,
  val orderIndex: Int,
)
