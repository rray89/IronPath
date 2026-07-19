package com.example.ironpath.ui.screens.history

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryDateFormatterTest {

    @Test
    fun `boundary instant formats as previous date in Los Angeles`() {
        val millis = Instant.parse("2026-01-01T00:30:00Z").toEpochMilli()

        assertEquals(
            "Dec 31, 2025",
            formatHistoryEpochDate(millis, ZoneId.of("America/Los_Angeles")),
        )
    }

    @Test
    fun `same boundary instant formats as next date in Tokyo`() {
        val millis = Instant.parse("2026-01-01T00:30:00Z").toEpochMilli()

        assertEquals(
            "Jan 01, 2026",
            formatHistoryEpochDate(millis, ZoneId.of("Asia/Tokyo")),
        )
    }
}
