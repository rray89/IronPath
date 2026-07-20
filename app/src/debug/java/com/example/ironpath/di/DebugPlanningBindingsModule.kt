package com.example.ironpath.di

import com.example.ironpath.data.ai.GeminiRemotePlanningTransport
import com.example.ironpath.data.ai.RemoteHttpClient
import com.example.ironpath.data.ai.UrlConnectionRemoteHttpClient
import com.example.ironpath.domain.planner.AiPlanningCandidate
import com.example.ironpath.domain.planner.DebugRemotePlanningEngine
import com.example.ironpath.domain.planner.FakeAiPlanningEngine
import com.example.ironpath.domain.planner.InMemoryRemotePlanningExperiment
import com.example.ironpath.domain.planner.PlanningEngine
import com.example.ironpath.domain.planner.PlanningEngineKey
import com.example.ironpath.domain.planner.PlanningEngineType
import com.example.ironpath.domain.planner.RemotePlanningExperiment
import com.example.ironpath.domain.planner.RemotePlanningTransport
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugPlanningBindingsModule {
    @Binds
    @Singleton
    abstract fun bindRemotePlanningExperiment(
        implementation: InMemoryRemotePlanningExperiment
    ): RemotePlanningExperiment

    @Binds
    @Singleton
    abstract fun bindRemoteHttpClient(
        implementation: UrlConnectionRemoteHttpClient
    ): RemoteHttpClient

    @Binds
    @Singleton
    abstract fun bindRemotePlanningTransport(
        implementation: GeminiRemotePlanningTransport
    ): RemotePlanningTransport

    @Binds
    @IntoMap
    @PlanningEngineKey(PlanningEngineType.DEBUG_FAKE_AI)
    abstract fun bindFakeAiPlanningEngine(implementation: FakeAiPlanningEngine): PlanningEngine

    @Binds
    @IntoMap
    @PlanningEngineKey(PlanningEngineType.DEBUG_REMOTE_AI)
    abstract fun bindDebugRemotePlanningEngine(
        implementation: DebugRemotePlanningEngine
    ): PlanningEngine

    companion object {
        @Provides
        @IntoSet
        fun provideDebugFakeAiCandidate() =
            AiPlanningCandidate(PlanningEngineType.DEBUG_FAKE_AI, priority = 50)

        @Provides
        @IntoSet
        fun provideDebugRemoteAiCandidate() =
            AiPlanningCandidate(PlanningEngineType.DEBUG_REMOTE_AI, priority = 25)
    }
}
