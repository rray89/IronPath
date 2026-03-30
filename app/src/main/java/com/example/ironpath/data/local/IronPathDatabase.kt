package com.example.ironpath.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.dao.PlanDao
import com.example.ironpath.data.local.dao.RecordDao
import com.example.ironpath.data.local.dao.SessionDao
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutLog

@Database(
    entities = [
        WeeklyPlan::class,
        PlannedWorkout::class,
        PlannedExercise::class,
        ActiveSession::class,
        SessionExercise::class,
        SessionSet::class,
        WorkoutLog::class,
        PersonalRecord::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class IronPathDatabase : RoomDatabase() {
    abstract fun planDao(): PlanDao
    abstract fun sessionDao(): SessionDao
    abstract fun historyDao(): HistoryDao
    abstract fun recordDao(): RecordDao
}
