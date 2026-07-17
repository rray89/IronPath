package com.example.ironpath.testutil

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TestCoreProvidersTest {
    @Test
    fun mutableTimeProvider_canAdvanceAndResetDeterministically() {
        val initial = Instant.parse("2026-07-13T17:00:00Z")
        val later = Instant.parse("2026-07-13T19:30:00Z")
        val provider = MutableTimeProvider(initial, ZoneId.of("America/Vancouver"))

        provider.setInstant(later)
        assertEquals(later, provider.now())

        provider.reset()
        assertEquals(initial, provider.now())
        assertEquals(ZoneId.of("America/Vancouver"), provider.zoneId)
    }

    @Test
    fun sequenceIdProvider_isUniqueAcrossThreadsAndResettable() {
        val provider = SequenceIdProvider("journey")
        val executor = Executors.newFixedThreadPool(8)

        val ids =
            try {
                executor.invokeAll((1..100).map { Callable { provider.newId() } }).map { it.get() }
            } finally {
                executor.shutdownNow()
            }

        assertEquals(100, ids.toSet().size)
        assertEquals((1..100).map { "journey-$it" }.toSet(), ids.toSet())

        provider.reset()
        assertEquals("journey-1", provider.newId())
    }
}
