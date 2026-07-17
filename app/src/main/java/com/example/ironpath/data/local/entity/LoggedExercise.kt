package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "logged_exercises",
    foreignKeys =
        [
            ForeignKey(
                entity = WorkoutLog::class,
                parentColumns = ["id"],
                childColumns = ["workoutLogId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("workoutLogId")],
)
data class LoggedExercise(
    @PrimaryKey val id: String,
    val workoutLogId: String,
    val name: String,
    val plannedSets: Int,
    val plannedReps: Int,
    val plannedWeightKg: Double,
    val orderIndex: Int,
)
