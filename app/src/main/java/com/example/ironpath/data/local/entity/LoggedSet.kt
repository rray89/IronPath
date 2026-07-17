package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "logged_sets",
    foreignKeys =
        [
            ForeignKey(
                entity = LoggedExercise::class,
                parentColumns = ["id"],
                childColumns = ["loggedExerciseId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("loggedExerciseId")],
)
data class LoggedSet(
    @PrimaryKey val id: String,
    val loggedExerciseId: String,
    val setNumber: Int,
    val reps: Int? = null,
    val weightKg: Double? = null,
    val isExtra: Boolean = false,
    val completedAt: Long? = null,
)
