package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "weekly_plans")
data class WeeklyPlan(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val status: PlanStatus = PlanStatus.Active,
    val startDate: String, // ISO date yyyy-MM-dd (Monday)
    val endDate: String, // ISO date yyyy-MM-dd (Sunday)
    val createdAt: Long = System.currentTimeMillis(),
)

enum class PlanStatus {
    Active,
    Archived
}
