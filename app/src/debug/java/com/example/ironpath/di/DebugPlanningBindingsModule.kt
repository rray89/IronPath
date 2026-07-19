package com.example.ironpath.di

import com.example.ironpath.domain.planner.FakeAiPlanningEngine
import com.example.ironpath.domain.planner.PlanningEngine
import com.example.ironpath.domain.planner.PlanningEngineKey
import com.example.ironpath.domain.planner.PlanningEngineType
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugPlanningBindingsModule {
    @Binds
    @IntoMap
    @PlanningEngineKey(PlanningEngineType.DEBUG_FAKE_AI)
    abstract fun bindFakeAiPlanningEngine(implementation: FakeAiPlanningEngine): PlanningEngine
}
