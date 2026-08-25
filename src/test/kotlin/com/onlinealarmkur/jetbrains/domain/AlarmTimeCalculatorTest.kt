package com.onlinealarmkur.jetbrains.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class AlarmTimeCalculatorTest {
    private val zone = ZoneId.of("UTC")
    private val clock = MutableClock(Instant.parse("2026-07-15T12:00:00Z"), zone)

    @Test
    fun `time later today uses today`() {
        assertEquals(Instant.parse("2026-07-15T13:00:00Z").toEpochMilli(), AlarmTimeCalculator.nextOccurrence(LocalTime.of(13, 0), null, clock))
    }

    @Test
    fun `time already passed uses tomorrow`() {
        assertEquals(Instant.parse("2026-07-16T11:00:00Z").toEpochMilli(), AlarmTimeCalculator.nextOccurrence(LocalTime.of(11, 0), null, clock))
    }

    @Test
    fun `explicit past date is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AlarmTimeCalculator.nextOccurrence(LocalTime.NOON, LocalDate.of(2026, 7, 14), clock)
        }
    }

    @Test
    fun `explicit time in Madrid spring gap is rejected without shifting`() {
        val madrid = ZoneId.of("Europe/Madrid")
        val beforeGap = Clock.fixed(Instant.parse("2026-03-29T00:00:00Z"), madrid)

        val error = assertThrows(IllegalArgumentException::class.java) {
            AlarmTimeCalculator.nextOccurrence(
                LocalTime.of(2, 30),
                LocalDate.of(2026, 3, 29),
                beforeGap,
            )
        }

        assertTrue(error.message.orEmpty().contains("2026-03-29T02:30"))
        assertTrue(error.message.orEmpty().contains("Europe/Madrid"))
        assertTrue(error.message.orEmpty().contains("does not exist"))
    }

    @Test
    fun `Madrid overlap chooses earlier occurrence when both are future`() {
        val madrid = ZoneId.of("Europe/Madrid")
        val beforeOverlap = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), madrid)

        val occurrence = AlarmTimeCalculator.nextOccurrence(
            LocalTime.of(2, 30),
            LocalDate.of(2026, 10, 25),
            beforeOverlap,
        )

        assertEquals(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(), occurrence)
    }

    @Test
    fun `Madrid overlap chooses second occurrence when first has passed`() {
        val madrid = ZoneId.of("Europe/Madrid")
        val betweenOccurrences = Clock.fixed(Instant.parse("2026-10-25T00:45:00Z"), madrid)

        val occurrence = AlarmTimeCalculator.nextOccurrence(
            LocalTime.of(2, 30),
            null,
            betweenOccurrences,
        )

        assertEquals(Instant.parse("2026-10-25T01:30:00Z").toEpochMilli(), occurrence)
    }

    @Test
    fun `undated Madrid overlap advances a day after both occurrences`() {
        val madrid = ZoneId.of("Europe/Madrid")
        val afterOverlap = Clock.fixed(Instant.parse("2026-10-25T02:00:00Z"), madrid)

        val occurrence = AlarmTimeCalculator.nextOccurrence(
            LocalTime.of(2, 30),
            null,
            afterOverlap,
        )

        assertEquals(Instant.parse("2026-10-26T01:30:00Z").toEpochMilli(), occurrence)
    }

    @Test
    fun `explicit Madrid overlap is rejected after both occurrences`() {
        val madrid = ZoneId.of("Europe/Madrid")
        val afterOverlap = Clock.fixed(Instant.parse("2026-10-25T02:00:00Z"), madrid)

        val error = assertThrows(IllegalArgumentException::class.java) {
            AlarmTimeCalculator.nextOccurrence(
                LocalTime.of(2, 30),
                LocalDate.of(2026, 10, 25),
                afterOverlap,
            )
        }

        assertEquals("The selected date and time must be in the future.", error.message)
    }
}
