package com.example.ironpath.domain.planner

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebugPlanningEngineRegistry
@Inject
constructor(private val registry: PlanningEngineRegistry) {
    val availableTypes: Set<PlanningEngineType>
        get() = registry.availableTypes.filterTo(mutableSetOf()) { it.debugOnly }
}
