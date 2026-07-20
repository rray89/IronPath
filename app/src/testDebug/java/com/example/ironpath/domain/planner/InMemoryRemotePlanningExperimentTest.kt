package com.example.ironpath.domain.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryRemotePlanningExperimentTest {
    @Test
    fun `debug experiment requires both opt in and a key`() {
        val experiment = InMemoryRemotePlanningExperiment()

        assertTrue(experiment.state.value.available)
        assertFalse(experiment.state.value.configured)

        experiment.setApiKey("  test-key  ")
        assertEquals("test-key", experiment.state.value.apiKey)
        assertFalse(experiment.state.value.configured)

        experiment.setEnabled(true)
        assertTrue(experiment.state.value.configured)
        assertFalse(experiment.state.value.toString().contains("test-key"))
    }

    @Test
    fun `disabling clears the in-memory secret and a new process state starts empty`() {
        val experiment = InMemoryRemotePlanningExperiment()
        experiment.setApiKey("secret-key")
        experiment.setEnabled(true)

        experiment.setEnabled(false)

        assertFalse(experiment.state.value.enabled)
        assertEquals("", experiment.state.value.apiKey)
        assertFalse(experiment.state.value.configured)
        assertEquals("", InMemoryRemotePlanningExperiment().state.value.apiKey)
    }

    @Test
    fun `api key input is bounded`() {
        val experiment = InMemoryRemotePlanningExperiment()

        experiment.setApiKey("x".repeat(600))

        assertEquals(512, experiment.state.value.apiKey.length)
    }
}
