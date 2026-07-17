package com.example.ironpath.domain

import com.example.ironpath.domain.identity.UuidIdProvider
import com.example.ironpath.domain.time.SystemTimeProvider
import com.example.ironpath.testutil.FakeTimeProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
}
