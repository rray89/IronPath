package com.example.ironpath.domain.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    fun `each advertised route requires a fresh key and opt in after selection`() {
        val experiment = InMemoryRemotePlanningExperiment()
        assertEquals(RemotePlanningRoute.GEMINI.name, experiment.state.value.optionId)
        assertEquals(
            listOf("GEMINI", "DEEPSEEK", "OPENROUTER_OPENAI", "OPENROUTER_QWEN"),
            experiment.state.value.options.map { it.id },
        )
        assertTrue(experiment.state.value.options.all { it.label.isNotBlank() })

        RemotePlanningRoute.entries.drop(1).forEach { route ->
            experiment.setApiKey("synthetic-${experiment.state.value.optionId}")
            experiment.setEnabled(true)
            val previous = experiment.state.value

            experiment.setOption(route.name)

            val selected = experiment.state.value
            assertEquals(route.name, selected.optionId)
            assertEquals(previous.revision + 1, selected.revision)
            assertEquals("", selected.apiKey)
            assertFalse(selected.enabled)
            assertFalse(selected.configured)
        }
        experiment.setOption(RemotePlanningRoute.GEMINI.name)
        assertEquals("", experiment.state.value.apiKey)
        assertFalse(experiment.state.value.configured)
    }

    @Test
    fun `same route and invalid routes preserve the configured snapshot`() {
        val experiment = InMemoryRemotePlanningExperiment()
        experiment.setApiKey("synthetic-key")
        experiment.setEnabled(true)
        val configured = experiment.state.value

        listOf(configured.optionId, "", "UNKNOWN", "deepseek", " GEMINI ").forEach {
            experiment.setOption(it)
            assertSame(configured, experiment.state.value)
        }
    }

    @Test
    fun `revision advances only when normalized configuration actually changes`() {
        val experiment = InMemoryRemotePlanningExperiment()
        assertEquals(0L, experiment.state.value.revision)
        experiment.setEnabled(false)
        experiment.setApiKey("   ")
        assertEquals(0L, experiment.state.value.revision)

        experiment.setApiKey("  synthetic-key  ")
        assertEquals(1L, experiment.state.value.revision)
        experiment.setApiKey("synthetic-key")
        assertEquals(1L, experiment.state.value.revision)
        experiment.setEnabled(true)
        assertEquals(2L, experiment.state.value.revision)
        experiment.setEnabled(true)
        assertEquals(2L, experiment.state.value.revision)
        experiment.setEnabled(false)
        assertEquals(3L, experiment.state.value.revision)
        assertEquals("", experiment.state.value.apiKey)
        experiment.setEnabled(false)
        assertEquals(3L, experiment.state.value.revision)
    }

    @Test
    fun `disabling clears a key even before the experiment was enabled`() {
        val experiment = InMemoryRemotePlanningExperiment()
        experiment.setApiKey("synthetic-key")
        val keyedRevision = experiment.state.value.revision

        experiment.setEnabled(false)

        assertEquals("", experiment.state.value.apiKey)
        assertEquals(keyedRevision + 1, experiment.state.value.revision)
        assertFalse(experiment.state.value.configured)
    }

    @Test
    fun `equivalent truncated keys do not invalidate a configuration`() {
        val experiment = InMemoryRemotePlanningExperiment()
        val prefix = "x".repeat(512)
        experiment.setApiKey(prefix + "first suffix")
        val snapshot = experiment.state.value

        experiment.setApiKey(prefix + "different suffix")

        assertSame(snapshot, experiment.state.value)
    }

    @Test
    fun `api key input is bounded`() {
        val experiment = InMemoryRemotePlanningExperiment()

        experiment.setApiKey("x".repeat(600))

        assertEquals(512, experiment.state.value.apiKey.length)
    }
}
