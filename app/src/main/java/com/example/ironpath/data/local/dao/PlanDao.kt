package com.example.ironpath.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {

    // WeeklyPlan
    @Insert
    suspend fun insertPlan(plan: WeeklyPlan)

    @Update
    suspend fun updatePlan(plan: WeeklyPlan)

    @Query("SELECT * FROM weekly_plans WHERE status = 'Active' LIMIT 1")
    fun observeActivePlan(): Flow<WeeklyPlan?>

    @Query("SELECT * FROM weekly_plans WHERE status = 'Active' LIMIT 1")
    suspend fun getActivePlan(): WeeklyPlan?

    @Query("UPDATE weekly_plans SET status = 'Archived' WHERE status = 'Active'")
    suspend fun archiveAllActivePlans()

    // PlannedWorkout
    @Insert
    suspend fun insertWorkouts(workouts: List<PlannedWorkout>)

    @Update
    suspend fun updateWorkout(workout: PlannedWorkout)

    @Query("SELECT * FROM planned_workouts WHERE weeklyPlanId = :planId ORDER BY dayOfWeek")
    fun observeWorkoutsForPlan(planId: String): Flow<List<PlannedWorkout>>

    @Query("SELECT * FROM planned_workouts WHERE weeklyPlanId = :planId ORDER BY dayOfWeek")
    suspend fun getWorkoutsForPlan(planId: String): List<PlannedWorkout>

    @Query("SELECT * FROM planned_workouts WHERE id = :id")
    suspend fun getWorkoutById(id: String): PlannedWorkout?

    @Query("DELETE FROM planned_workouts WHERE id = :id")
    suspend fun deleteWorkout(id: String)

    // PlannedExercise
    @Insert
    suspend fun insertExercises(exercises: List<PlannedExercise>)

    @Query("SELECT * FROM planned_exercises WHERE plannedWorkoutId = :workoutId ORDER BY orderIndex")
    fun observeExercisesForWorkout(workoutId: String): Flow<List<PlannedExercise>>

    @Query("SELECT * FROM planned_exercises WHERE plannedWorkoutId = :workoutId ORDER BY orderIndex")
    suspend fun getExercisesForWorkout(workoutId: String): List<PlannedExercise>

    @Query("SELECT DISTINCT name FROM planned_exercises")
    suspend fun getAllExerciseNames(): List<String>
}
