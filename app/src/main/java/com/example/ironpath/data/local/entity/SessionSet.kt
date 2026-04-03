package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
  tableName = "session_sets",
  foreignKeys =
    [
      ForeignKey(
        entity = SessionExercise::class,
        parentColumns = ["id"],
        childColumns = ["sessionExerciseId"],
        onDelete = ForeignKey.CASCADE,
      ),
    ],
  indices = [Index("sessionExerciseId")],
)
data class SessionSet(
  @PrimaryKey val id: String = UUID.randomUUID().toString(),
  val sessionExerciseId: String,
  val setNumber: Int,
  val reps: Int? = null,
  val weightKg: Double? = null,
  val isExtra: Boolean = false,
  val completedAt: Long? = null,
)
