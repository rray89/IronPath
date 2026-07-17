package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
    @PrimaryKey val id: String,
    val sessionExerciseId: String,
    val setNumber: Int,
    val reps: Int? = null,
    val weightKg: Double? = null,
    val isExtra: Boolean = false,
    val completedAt: Long? = null,
)
