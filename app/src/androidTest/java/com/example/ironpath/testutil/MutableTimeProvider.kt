package com.example.ironpath.testutil

import com.example.ironpath.domain.time.TimeProvider
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

class MutableTimeProvider(
    private val initialInstant: Instant,
    override val zoneId: ZoneId,
) : TimeProvider {
    private val currentInstant = AtomicReference(initialInstant)

    override fun now(): Instant = currentInstant.get()

    fun setInstant(value: Instant) {
        currentInstant.set(value)
    }

    fun reset() {
        currentInstant.set(initialInstant)
    }
}
