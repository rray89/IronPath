package com.example.ironpath.data.repository

import androidx.room.withTransaction
import com.example.ironpath.data.backup.BackupChangeTracker
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.PlanDao
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class PlanRepository
@Inject
constructor(
    private val planDao: PlanDao,
    private val database: IronPathDatabase,
    private val backupChangeTracker: BackupChangeTracker,
) {

    fun observeActivePlan(): Flow<WeeklyPlan?> = planDao.observeActivePlan()

    suspend fun getActivePlan(): WeeklyPlan? = planDao.getActivePlan()

    fun observeWorkoutsForPlan(planId: String): Flow<List<PlannedWorkout>> =
        planDao.observeWorkoutsForPlan(planId)

    suspend fun getWorkoutsForPlan(planId: String): List<PlannedWorkout> =
        planDao.getWorkoutsForPlan(planId)

    suspend fun getWorkoutById(id: String): PlannedWorkout? = planDao.getWorkoutById(id)

    fun observeExercisesForWorkout(workoutId: String): Flow<List<PlannedExercise>> =
        planDao.observeExercisesForWorkout(workoutId)

    suspend fun getExercisesForWorkout(workoutId: String): List<PlannedExercise> =
        planDao.getExercisesForWorkout(workoutId)

    suspend fun getAllExerciseNames(): List<String> = planDao.getAllExerciseNames()

    /**
     * Archives any existing active plan, then inserts the new plan with its workouts and exercises
     * in a single transaction.
     */
    suspend fun createPlan(
        plan: WeeklyPlan,
        workouts: List<PlannedWorkout>,
        exercises: List<PlannedExercise>,
    ) =
        database.withTransaction {
            planDao.createPlanWithWorkouts(plan, workouts, exercises)
            backupChangeTracker.markIncludedDataChanged()
        }

    suspend fun updateWorkout(workout: PlannedWorkout) =
        database.withTransaction {
            planDao.updateWorkout(workout)
            backupChangeTracker.markIncludedDataChanged()
        }

    suspend fun deleteWorkout(id: String) =
        database.withTransaction {
            planDao.deleteWorkout(id)
            backupChangeTracker.markIncludedDataChanged()
        }
}
