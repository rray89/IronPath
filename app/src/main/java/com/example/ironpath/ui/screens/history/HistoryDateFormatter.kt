package com.example.ironpath.ui.screens.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val historyDateFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US)

internal fun formatHistoryEpochDate(millis: Long, zoneId: ZoneId): String =
    historyDateFormatter.format(Instant.ofEpochMilli(millis).atZone(zoneId))
