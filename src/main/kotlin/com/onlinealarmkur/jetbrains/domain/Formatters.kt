package com.onlinealarmkur.jetbrains.domain

import com.onlinealarmkur.jetbrains.AlarmTimerBundle
import com.onlinealarmkur.jetbrains.AlarmTimerLocale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val LIVE_NEAR_THRESHOLD_MS = 60 * 60_000L
private const val MILLIS_PER_MINUTE = 60_000L

object Formatters {
    fun editableDuration(millis: Long): String {
        require(millis in 1_000..DurationParser.MAX_DURATION_MS) {
            "Editable durations must be between one second and 30 days."
        }
        val totalSeconds = ((millis + 999) / 1_000)
            .coerceAtMost(DurationParser.MAX_DURATION_MS / 1_000)
        val hours = totalSeconds / 3_600
        val minutes = totalSeconds % 3_600 / 60
        val seconds = totalSeconds % 60
        return buildList {
            if (hours > 0) add("${hours}h")
            if (minutes > 0) add("${minutes}m")
            if (seconds > 0) add("${seconds}s")
        }.joinToString(" ")
    }

    fun duration(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0) + 999) / 1_000
        val hours = seconds / 3_600
        val minutes = seconds % 3_600 / 60
        val remainder = seconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder) else "%02d:%02d".format(minutes, remainder)
    }

    fun liveDuration(millis: Long): String = liveDuration(millis, AlarmTimerBundle.locale())

    fun liveDuration(millis: Long, locale: Locale): String {
        val remainingMs = millis.coerceAtLeast(0)
        if (remainingMs <= LIVE_NEAR_THRESHOLD_MS) return duration(remainingMs)
        val wholeMinutes = remainingMs / MILLIS_PER_MINUTE +
            if (remainingMs % MILLIS_PER_MINUTE == 0L) 0 else 1
        val hours = wholeMinutes / 60
        val minutes = (wholeMinutes % 60).toString().padStart(2, '0')
        return AlarmTimerBundle.messageFor(locale, "duration.display.hours.minutes", hours, minutes)
    }

    fun dateTime(
        epochMs: Long,
        use24Hour: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = AlarmTimerBundle.locale(),
    ): String {
        val localDateTime = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime()
        val date = localDateTime.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val time = AlarmTimeText.format(
            localDateTime.toLocalTime(),
            use24HourTime = use24Hour,
            includeSeconds = true,
            locale = AlarmTimerLocale.resolve(locale),
        )
        return "$date $time"
    }
}
