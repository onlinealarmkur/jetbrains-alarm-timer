package com.onlinealarmkur.jetbrains.domain

import com.onlinealarmkur.jetbrains.AlarmTimerBundle
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object AlarmTimeCalculator {
    /**
     * Resolves an alarm in [zone]. Daylight-saving gaps are rejected. During an overlap, the
     * earliest occurrence strictly after [clock] is selected; an undated alarm advances to the
     * next day only when neither occurrence today is still in the future.
     */
    fun nextOccurrence(time: LocalTime, date: LocalDate?, clock: Clock, zone: ZoneId = clock.zone): Long {
        val now = clock.instant()
        val nowLocal = now.atZone(zone).toLocalDateTime()
        if (date != null) {
            return resolvedInstants(LocalDateTime.of(date, time), zone)
                .firstOrNull { it.isAfter(now) }
                ?.toEpochMilli()
                ?: throw IllegalArgumentException(AlarmTimerBundle.message("alarm.date.time.error.future"))
        }

        val today = LocalDateTime.of(nowLocal.toLocalDate(), time)
        val todayInstants = resolvedInstants(today, zone, rejectGap = today.isAfter(nowLocal))
        todayInstants.firstOrNull { it.isAfter(now) }?.let { return it.toEpochMilli() }

        val tomorrow = today.plusDays(1)
        return resolvedInstants(tomorrow, zone)
            .firstOrNull { it.isAfter(now) }
            ?.toEpochMilli()
            ?: throw IllegalArgumentException(AlarmTimerBundle.message("alarm.date.time.error.future"))
    }

    private fun resolvedInstants(
        localDateTime: LocalDateTime,
        zone: ZoneId,
        rejectGap: Boolean = true,
    ) = zone.rules.getValidOffsets(localDateTime)
        .also { offsets ->
            require(offsets.isNotEmpty() || !rejectGap) {
                AlarmTimerBundle.message("alarm.date.time.error.dst.gap", localDateTime, zone.id)
            }
        }
        .map { localDateTime.toInstant(it) }
        .sorted()
}
