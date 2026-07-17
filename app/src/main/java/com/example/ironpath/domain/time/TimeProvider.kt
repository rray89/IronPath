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

class SystemTimeProvider
internal constructor(
    private val clock: Clock,
    private val zoneIdProvider: () -> ZoneId,
) : TimeProvider {
    @Inject constructor() : this(Clock.systemUTC(), ZoneId::systemDefault)

    override val zoneId: ZoneId
        get() = zoneIdProvider()

    override fun now(): Instant = clock.instant()
}
