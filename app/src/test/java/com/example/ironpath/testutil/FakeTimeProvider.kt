package com.example.ironpath.testutil

import com.example.ironpath.domain.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class FakeTimeProvider(
    private var instant: Instant = Instant.parse("2026-07-16T19:00:00Z"),
    override val zoneId: ZoneId = ZoneId.of("America/Vancouver"),
) : TimeProvider {
    override fun now(): Instant = instant

    fun advanceBy(duration: Duration) {
        instant = instant.plus(duration)
    }
}
