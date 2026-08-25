package com.onlinealarmkur.jetbrains.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.time.ZoneOffset
import java.util.Locale

class AlarmTimeTextTest {
    @Test
    fun `formats 24-hour boundaries with optional seconds`() {
        assertEquals("00:00", AlarmTimeText.format(LocalTime.MIDNIGHT, use24HourTime = true, includeSeconds = false, locale = Locale.ENGLISH))
        assertEquals("12:00:00", AlarmTimeText.format(LocalTime.NOON, use24HourTime = true, includeSeconds = true, locale = Locale.ENGLISH))
        assertEquals("13:05", AlarmTimeText.format(LocalTime.of(13, 5, 9), use24HourTime = true, includeSeconds = false, locale = Locale.ENGLISH))
        assertEquals("13:05:09", AlarmTimeText.format(LocalTime.of(13, 5, 9), use24HourTime = true, includeSeconds = true, locale = Locale.ENGLISH))
    }

    @Test
    fun `formats 12-hour boundaries with optional seconds`() {
        assertEquals("12:00 AM", AlarmTimeText.format(LocalTime.MIDNIGHT, use24HourTime = false, includeSeconds = false, locale = Locale.ENGLISH))
        assertEquals("12:00:00 PM", AlarmTimeText.format(LocalTime.NOON, use24HourTime = false, includeSeconds = true, locale = Locale.ENGLISH))
        assertEquals("1:05 PM", AlarmTimeText.format(LocalTime.of(13, 5, 9), use24HourTime = false, includeSeconds = false, locale = Locale.ENGLISH))
        assertEquals("1:05:09 PM", AlarmTimeText.format(LocalTime.of(13, 5, 9), use24HourTime = false, includeSeconds = true, locale = Locale.ENGLISH))
    }

    @Test
    fun `parses every accepted alarm syntax regardless of display mode`() {
        val expected = LocalTime.of(13, 30, 45)

        assertEquals(LocalTime.of(13, 30), AlarmTimeText.parse("13:30", Locale.ENGLISH))
        assertEquals(expected, AlarmTimeText.parse("13:30:45", Locale.ENGLISH))
        assertEquals(LocalTime.of(13, 30), AlarmTimeText.parse("1:30 PM", Locale.ENGLISH))
        assertEquals(expected, AlarmTimeText.parse("1:30:45 pm", Locale.ENGLISH))
        assertEquals(LocalTime.of(0, 30), AlarmTimeText.parse("  12:30 am  ", Locale.ENGLISH))
        assertEquals(LocalTime.NOON, AlarmTimeText.parse("12:00 PM", Locale.ENGLISH))
    }

    @Test
    fun `rejects incomplete ambiguous or out-of-range alarm text`() {
        listOf(
            "",
            "1 PM",
            "7:30",
            "1:2 PM",
            "1:30PM",
            "00:30 AM",
            "13:00 PM",
            "24:00",
            "23:60",
            "13:00 trailing",
        ).forEach { text ->
            assertThrows(DateTimeParseException::class.java) {
                AlarmTimeText.parse(text, Locale.ENGLISH)
            }
        }
    }

    @Test
    fun `hints follow the selected generated-text mode`() {
        assertEquals("Local time, HH:mm or HH:mm:ss", AlarmTimeText.hint(use24HourTime = true))
        assertEquals("Local time, h:mm AM/PM or h:mm:ss AM/PM", AlarmTimeText.hint(use24HourTime = false))
    }

    @Test
    fun `native markers have canonical order and English remains accepted`() {
        val cases = listOf(
            Triple(Locale.JAPANESE, "午後1:30", "午後1:30:45"),
            Triple(Locale.KOREAN, "오후 1:30", "오후 1:30:45"),
            Triple(Locale.SIMPLIFIED_CHINESE, "下午1:30", "下午1:30:45"),
            Triple(Locale.forLanguageTag("tr"), "ÖS 1:30", "ÖS 1:30:45"),
        )
        cases.forEach { (locale, short, seconds) ->
            assertEquals(short, AlarmTimeText.format(LocalTime.of(13, 30), false, false, locale))
            assertEquals(seconds, AlarmTimeText.format(LocalTime.of(13, 30, 45), false, true, locale))
            assertEquals(LocalTime.of(13, 30), AlarmTimeText.parse(short, locale))
            assertEquals(LocalTime.of(13, 30, 45), AlarmTimeText.parse(seconds, locale))
            assertEquals(LocalTime.of(13, 30), AlarmTimeText.parse(" 1:30 PM ", locale))
        }
    }

    @Test
    fun `Spanish spacing variants and unsupported regional locales resolve deterministically`() {
        val spanish = Locale.forLanguageTag("es")
        assertEquals(LocalTime.of(13, 30), AlarmTimeText.parse("1:30 p. m.", spanish))
        assertEquals(LocalTime.of(13, 30), AlarmTimeText.parse("1:30 p. m.", spanish))
        assertEquals("1:30 PM", AlarmTimeText.format(LocalTime.of(13, 30), false, false, Locale.forLanguageTag("pt-PT")))
        assertEquals("1:30 PM", AlarmTimeText.format(LocalTime.of(13, 30), false, false, Locale.forLanguageTag("zh-TW")))
        assertEquals("1970-01-01 午後1:00:00", Formatters.dateTime(13 * 60 * 60_000L, false, ZoneOffset.UTC, Locale.JAPANESE))
        assertTrue(AlarmTimeText.format(LocalTime.NOON, false, false, Locale.SIMPLIFIED_CHINESE).startsWith("下午"))
    }

    @Test
    fun `native morning afternoon date time and English fallback preserve the default locale`() {
        val defaultLocale = Locale.getDefault()
        val cases = listOf(
            Triple(Locale.JAPANESE, "午前1:30", "午後1:30"),
            Triple(Locale.KOREAN, "오전 1:30", "오후 1:30"),
            Triple(Locale.SIMPLIFIED_CHINESE, "上午1:30", "下午1:30"),
            Triple(Locale.forLanguageTag("tr"), "ÖÖ 1:30", "ÖS 1:30"),
        )
        cases.forEach { (locale, morning, afternoon) ->
            assertEquals(morning, AlarmTimeText.format(LocalTime.of(1, 30), false, false, locale))
            assertEquals("${morning}:45", AlarmTimeText.format(LocalTime.of(1, 30, 45), false, true, locale))
            assertEquals(LocalTime.of(1, 30, 45), AlarmTimeText.parse("${morning}:45", locale))
            assertEquals(LocalTime.of(13, 30), AlarmTimeText.parse(afternoon, locale))
            assertEquals(LocalTime.of(13, 30, 45), AlarmTimeText.parse("1:30:45 PM", locale))
            assertEquals(
                "1970-01-01 ${AlarmTimeText.format(LocalTime.of(13, 0), false, true, locale)}",
                Formatters.dateTime(13 * 60 * 60_000L, false, ZoneOffset.UTC, locale),
            )
        }
        listOf("de-DE", "es-MX", "fr-CA", "ja-JP", "ko-KR", "ru-RU", "tr-TR", "pt-BR", "zh-SG", "zh-Hans").forEach {
            assertEquals(LocalTime.of(13, 30), AlarmTimeText.parse("1:30 PM", Locale.forLanguageTag(it)))
        }
        assertEquals("1:30 p. m.", AlarmTimeText.format(LocalTime.of(13, 30), false, false, Locale.forLanguageTag("es")))
        assertEquals(defaultLocale, Locale.getDefault())
    }
}
