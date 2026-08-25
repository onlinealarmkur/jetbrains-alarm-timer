package com.onlinealarmkur.jetbrains.domain

import com.onlinealarmkur.jetbrains.AlarmTimerBundle
import java.util.Locale

object DurationParser {
    const val MAX_DURATION_MS: Long = 30L * 24 * 60 * 60 * 1_000

    /**
     * Bounds every digit group at 18 significant digits (leading zeros stay unlimited, so no
     * currently accepted input is lost). 18 nines fit in `Long`, which keeps `String.toLong` total;
     * the remaining accumulation can still overflow, and [parse] turns that into the localized
     * maximum message instead of a raw `long overflow`.
     */
    private const val BOUNDED_DIGITS = "0*\\d{1,18}"

    fun parse(input: String): Result<Long> = runCatching {
        val text = input.trim().lowercase(Locale.ROOT)
        require(text.isNotEmpty()) { AlarmTimerBundle.message("duration.error.empty") }

        val millis = try {
            val seconds = if (':' in text) parseColon(text) else parseUnits(text)
            require(seconds > 0) { AlarmTimerBundle.message("duration.error.minimum") }
            Math.multiplyExact(seconds, 1_000L)
        } catch (overflow: ArithmeticException) {
            throw IllegalArgumentException(AlarmTimerBundle.message("duration.error.maximum"), overflow)
        }
        require(millis <= MAX_DURATION_MS) { AlarmTimerBundle.message("duration.error.maximum") }
        millis
    }

    private fun parseColon(text: String): Long {
        require(text.matches(Regex("$BOUNDED_DIGITS:\\d{1,2}(:\\d{1,2})?"))) {
            AlarmTimerBundle.message("duration.error.colon.format")
        }
        val parts = text.split(':').map(String::toLong)
        return if (parts.size == 2) {
            val (minutes, seconds) = parts
            require(seconds < 60) { AlarmTimerBundle.message("duration.error.seconds.range") }
            Math.addExact(Math.multiplyExact(minutes, 60), seconds)
        } else {
            val (hours, minutes, seconds) = parts
            require(minutes < 60 && seconds < 60) {
                AlarmTimerBundle.message("duration.error.minutes.seconds.range")
            }
            Math.addExact(Math.addExact(Math.multiplyExact(hours, 3_600), Math.multiplyExact(minutes, 60)), seconds)
        }
    }

    private fun parseUnits(text: String): Long {
        val matcher = Regex("($BOUNDED_DIGITS)\\s*([hms])").findAll(text).toList()
        require(matcher.isNotEmpty()) { AlarmTimerBundle.message("duration.error.examples") }
        require(matcher.joinToString("") { it.value }.replace(" ", "") == text.replace(" ", "")) {
            AlarmTimerBundle.message("duration.error.unsupported")
        }
        val seen = mutableSetOf<String>()
        var total = 0L
        var previousRank = 4
        matcher.forEach { match ->
            val value = match.groupValues[1].toLong()
            val unit = match.groupValues[2]
            require(seen.add(unit)) { AlarmTimerBundle.message("duration.error.duplicate.unit") }
            val rank = when (unit) { "h" -> 3; "m" -> 2; else -> 1 }
            require(rank < previousRank) { AlarmTimerBundle.message("duration.error.unit.order") }
            previousRank = rank
            val multiplier = when (unit) { "h" -> 3_600L; "m" -> 60L; else -> 1L }
            total = Math.addExact(total, Math.multiplyExact(value, multiplier))
        }
        return total
    }
}
