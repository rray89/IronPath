package com.example.ironpath.di

import com.example.ironpath.domain.planner.AiPlanningCandidate
import com.example.ironpath.domain.planner.MainAiPlanningChain
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet

@Module
@InstallIn(SingletonComponent::class)
object PlanningCandidatesModule {
    @Provides
    @ElementsIntoSet
    fun provideMainAiPlanningCandidates(): Set<AiPlanningCandidate> = MainAiPlanningChain.candidates
}
