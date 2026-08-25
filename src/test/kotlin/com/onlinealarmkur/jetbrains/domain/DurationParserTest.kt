package com.onlinealarmkur.jetbrains.domain

import com.onlinealarmkur.jetbrains.AlarmTimerBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class DurationParserTest {
    @ParameterizedTest
    @CsvSource("90s,90000", "10m,600000", "1h,3600000", "'1h 30m',5400000", "01:30,90000", "01:30:00,5400000")
    fun `parses documented formats`(input: String, expected: Long) {
        assertEquals(expected, DurationParser.parse(input).getOrThrow())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "hello", "0s", "-1s", "1:60", "1:60:00", "31d", "30m 1h", "1m 2m"])
    fun `rejects invalid durations`(input: String) {
        assertTrue(DurationParser.parse(input).isFailure)
    }

    @Test
    fun `bounded digit groups still accept zero padded values`() {
        val padding = "0".repeat(40)

        assertEquals(1_000, DurationParser.parse("${padding}1s").getOrThrow())
        assertEquals(90_000, DurationParser.parse("${padding}1m ${padding}30s").getOrThrow())
        assertEquals(90_000, DurationParser.parse("${padding}1:30").getOrThrow())
        assertEquals(5_400_000, DurationParser.parse("${padding}1:30:00").getOrThrow())
    }

    @Test
    fun `oversized input fails with a localized message instead of raw JVM text`() {
        val cases = linkedMapOf(
            "9".repeat(20) + ":00" to "duration.error.colon.format",
            "9".repeat(20) + "s" to "duration.error.unsupported",
            "9000000000000000h" to "duration.error.maximum",
            "9".repeat(18) + ":00:00" to "duration.error.maximum",
            "1234567890s" to "duration.error.maximum",
        )

        cases.forEach { (input, key) ->
            val message = requireNotNull(DurationParser.parse(input).exceptionOrNull()?.message) { input }
            assertEquals(AlarmTimerBundle.message(key), message, input)
            assertFalse(message.contains("For input string"), input)
            assertFalse(message.contains("overflow"), input)
        }
    }
}
