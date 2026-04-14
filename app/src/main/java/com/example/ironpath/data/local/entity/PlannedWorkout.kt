package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "planned_workouts",
    foreignKeys =
        [
            ForeignKey(
                entity = WeeklyPlan::class,
                parentColumns = ["id"],
                childColumns = ["weeklyPlanId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("weeklyPlanId")],
)
data class PlannedWorkout(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val weeklyPlanId: String,
    val dayOfWeek: Int, // 1 = Monday .. 7 = Sunday (ISO)
    val scheduledDate: String, // ISO date yyyy-MM-dd
    val title: String, // e.g. "Chest/Tris"
    val status: WorkoutStatus = WorkoutStatus.Upcoming,
)

enum class WorkoutStatus {
    Upcoming,
    Completed,
    Skipped
}
