package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "planned_exercises",
    foreignKeys =
        [
            ForeignKey(
                entity = PlannedWorkout::class,
                parentColumns = ["id"],
                childColumns = ["plannedWorkoutId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("plannedWorkoutId")],
)
data class PlannedExercise(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val plannedWorkoutId: String,
    val name: String,
    val sets: Int,
    val reps: Int,
    val weightKg: Double,
    val orderIndex: Int,
)
