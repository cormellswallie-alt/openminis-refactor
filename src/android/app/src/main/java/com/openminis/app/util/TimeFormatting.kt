package com.openminis.app.util

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Retain the calendar and wire format used by existing exports and backups.
private val utcTimestampFormatter = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}

internal fun formatUtcTimestamp(epochMillis: Long): String =
    utcTimestampFormatter.get()!!.format(epochMillis)

internal fun parseUtcTimestamp(value: String): Long? =
    utcTimestampFormatter.get()!!.parse(value)?.time

internal fun formatMinuteSecondClock(millis: Int): String {
    val seconds = millis / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
