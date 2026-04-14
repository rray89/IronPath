package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "personal_records",
    indices =
        [
            Index(
                value = ["normalizedExerciseName", "achievedOn", "weightKg"],
                unique = true,
            ),
        ],
)
data class PersonalRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val exerciseName: String,
    val normalizedExerciseName: String, // lowercase trimmed, for dedup
    val weightKg: Double,
    val achievedOn: String, // ISO date yyyy-MM-dd
    val note: String? = null,
    val sourceType: RecordSource = RecordSource.Manual,
    val sourceWorkoutLogId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class RecordSource {
    Manual,
    Logged
}
