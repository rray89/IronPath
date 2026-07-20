package com.example.ironpath.di

import com.example.ironpath.domain.planner.DisabledRemotePlanningExperiment
import com.example.ironpath.domain.planner.RemotePlanningExperiment
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ReleaseRemotePlanningModule {
    @Binds
    @Singleton
    abstract fun bindRemotePlanningExperiment(
        implementation: DisabledRemotePlanningExperiment
    ): RemotePlanningExperiment
}
