package com.example.ironpath.di

import com.example.ironpath.data.ai.MlKitOnDeviceModelClient
import com.example.ironpath.data.repository.LocalPlanningHistoryProvider
import com.example.ironpath.domain.planner.DefaultExerciseCatalog
import com.example.ironpath.domain.planner.ExerciseCatalog
import com.example.ironpath.domain.planner.OnDeviceAiPlanningEngine
import com.example.ironpath.domain.planner.OnDeviceModelClient
import com.example.ironpath.domain.planner.PlanningEngine
import com.example.ironpath.domain.planner.PlanningEngineKey
import com.example.ironpath.domain.planner.PlanningEngineType
import com.example.ironpath.domain.planner.PlanningHistoryProvider
import com.example.ironpath.domain.planner.RuleBasedPlanningEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlanningBindingsModule {
    @Binds
    @Singleton
    abstract fun bindExerciseCatalog(implementation: DefaultExerciseCatalog): ExerciseCatalog

    @Binds
    @Singleton
    abstract fun bindPlanningHistoryProvider(
        implementation: LocalPlanningHistoryProvider
    ): PlanningHistoryProvider

    @Binds
    @Singleton
    abstract fun bindOnDeviceModelClient(
        implementation: MlKitOnDeviceModelClient
    ): OnDeviceModelClient

    @Binds
    @IntoMap
    @PlanningEngineKey(PlanningEngineType.RULE_BASED)
    abstract fun bindRuleBasedPlanningEngine(
        implementation: RuleBasedPlanningEngine
    ): PlanningEngine

    @Binds
    @IntoMap
    @PlanningEngineKey(PlanningEngineType.ON_DEVICE_AI)
    abstract fun bindOnDeviceAiPlanningEngine(
        implementation: OnDeviceAiPlanningEngine
    ): PlanningEngine
}
