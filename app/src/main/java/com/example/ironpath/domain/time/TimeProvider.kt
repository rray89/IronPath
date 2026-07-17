package com.example.ironpath.domain.time

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

interface TimeProvider {
    val zoneId: ZoneId

    fun now(): Instant

    fun today(): LocalDate = now().atZone(zoneId).toLocalDate()

    fun epochMillis(): Long = now().toEpochMilli()
}

class SystemTimeProvider @Inject constructor() : TimeProvider {
    private val clock: Clock = Clock.systemDefaultZone()

    override val zoneId: ZoneId
        get() = clock.zone

    override fun now(): Instant = clock.instant()
}
