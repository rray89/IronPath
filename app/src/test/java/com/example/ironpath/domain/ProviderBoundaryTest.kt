package com.example.ironpath.domain

import com.example.ironpath.domain.identity.UuidIdProvider
import com.example.ironpath.domain.time.SystemTimeProvider
import com.example.ironpath.testutil.FakeTimeProvider
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderBoundaryTest {

    @Test
    fun `time provider derives epoch and local date from one instant and zone`() {
        val instant = Instant.parse("2026-07-17T06:30:00Z")
        val provider = FakeTimeProvider(instant, ZoneId.of("America/Vancouver"))

        assertEquals(instant.toEpochMilli(), provider.epochMillis())
        assertEquals(LocalDate.parse("2026-07-16"), provider.today())
    }

    @Test
    fun `system time and id providers return structurally valid values`() {
        val timeProvider = SystemTimeProvider()
        val id = UuidIdProvider().newId()

        assertTrue(timeProvider.zoneId.id.isNotBlank())
        assertNotNull(timeProvider.now())
        assertEquals(id, UUID.fromString(id).toString())
    }

    @Test
    fun `system time provider observes timezone changes after construction`() {
        var currentZone = ZoneId.of("UTC")
        val provider =
            SystemTimeProvider(
                clock = Clock.fixed(Instant.parse("2026-07-16T19:00:00Z"), ZoneOffset.UTC),
                zoneIdProvider = { currentZone },
            )
        assertEquals(ZoneId.of("UTC"), provider.zoneId)

        currentZone = ZoneId.of("America/Vancouver")

        assertEquals(ZoneId.of("America/Vancouver"), provider.zoneId)
    }
}
