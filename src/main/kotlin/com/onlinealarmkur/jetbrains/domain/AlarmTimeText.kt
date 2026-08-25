package com.onlinealarmkur.jetbrains.domain

import com.onlinealarmkur.jetbrains.AlarmTimerBundle
import com.onlinealarmkur.jetbrains.AlarmTimerLocale
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.SignStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoField
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal object AlarmTimeText {
    private val formatters = ConcurrentHashMap<FormatterKey, DateTimeFormatter>()

    fun parse(text: String, locale: Locale = AlarmTimerBundle.locale()): LocalTime {
        val effectiveLocale = AlarmTimerLocale.resolve(locale)
        val candidate = normalize(text.trim(), effectiveLocale)
        var lastFailure: DateTimeParseException? = null
        acceptedFormats(effectiveLocale).forEach { formatter ->
            try {
                return LocalTime.parse(candidate, formatter)
            } catch (failure: DateTimeParseException) {
                lastFailure = failure
            }
        }
        throw DateTimeParseException(
            AlarmTimerBundle.message("alarm.time.error.format"),
            candidate,
            lastFailure?.errorIndex ?: 0,
            lastFailure,
        )
    }

    fun format(
        time: LocalTime,
        use24HourTime: Boolean,
        includeSeconds: Boolean,
        locale: Locale = AlarmTimerBundle.locale(),
    ): String {
        val effectiveLocale = AlarmTimerLocale.resolve(locale)
        return time.format(formatterFor(effectiveLocale, use24HourTime, includeSeconds))
    }

    fun hint(use24HourTime: Boolean): String = if (use24HourTime) {
        AlarmTimerBundle.message("alarm.time.hint.24")
    } else {
        AlarmTimerBundle.message("alarm.time.hint.12")
    }

    private fun acceptedFormats(locale: Locale): List<DateTimeFormatter> = buildList {
        add(formatterFor(locale, use24HourTime = true, includeSeconds = false))
        add(formatterFor(locale, use24HourTime = true, includeSeconds = true))
        add(formatterFor(locale, use24HourTime = false, includeSeconds = false))
        add(formatterFor(locale, use24HourTime = false, includeSeconds = true))
        if (locale != Locale.ENGLISH) {
            add(formatterFor(Locale.ENGLISH, use24HourTime = false, includeSeconds = false))
            add(formatterFor(Locale.ENGLISH, use24HourTime = false, includeSeconds = true))
        }
    }

    private fun normalize(value: String, locale: Locale): String = when (locale.language) {
        "es" -> value
            .replace("a. m.", "a.\u00a0m.", ignoreCase = true)
            .replace("p. m.", "p.\u00a0m.", ignoreCase = true)
        else -> value
    }

    private fun formatterFor(locale: Locale, use24HourTime: Boolean, includeSeconds: Boolean): DateTimeFormatter =
        formatters.getOrPut(FormatterKey(locale, use24HourTime, includeSeconds)) {
            formatter(locale, use24HourTime, includeSeconds)
        }

    private fun formatter(locale: Locale, use24HourTime: Boolean, includeSeconds: Boolean): DateTimeFormatter =
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .parseStrict()
            .apply {
                if (use24HourTime) {
                    appendValue(ChronoField.HOUR_OF_DAY, 2)
                    appendLiteral(':')
                    appendValue(ChronoField.MINUTE_OF_HOUR, 2)
                    if (includeSeconds) {
                        appendLiteral(':')
                        appendValue(ChronoField.SECOND_OF_MINUTE, 2)
                    }
                } else {
                    val prefixMarker = locale in PREFIX_MARKER_LOCALES
                    if (prefixMarker) {
                        appendText(ChronoField.AMPM_OF_DAY, TextStyle.SHORT)
                        if (locale == Locale.KOREAN || locale.language == "tr") appendLiteral(' ')
                    }
                    appendValue(ChronoField.CLOCK_HOUR_OF_AMPM, 1, 2, SignStyle.NOT_NEGATIVE)
                    appendLiteral(':')
                    appendValue(ChronoField.MINUTE_OF_HOUR, 2)
                    if (includeSeconds) {
                        appendLiteral(':')
                        appendValue(ChronoField.SECOND_OF_MINUTE, 2)
                    }
                    if (!prefixMarker) {
                        appendLiteral(' ')
                        appendText(ChronoField.AMPM_OF_DAY, TextStyle.SHORT)
                    }
                }
            }
            .toFormatter(locale)
            .withResolverStyle(java.time.format.ResolverStyle.STRICT)

    private data class FormatterKey(
        val locale: Locale,
        val use24HourTime: Boolean,
        val includeSeconds: Boolean,
    )

    private val PREFIX_MARKER_LOCALES = setOf(Locale.JAPANESE, Locale.KOREAN, Locale.SIMPLIFIED_CHINESE, Locale.forLanguageTag("tr"))
}
