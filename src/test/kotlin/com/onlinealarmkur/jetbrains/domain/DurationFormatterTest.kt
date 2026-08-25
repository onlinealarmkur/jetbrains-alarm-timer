package com.onlinealarmkur.jetbrains.domain

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

class DurationFormatterTest {
    private lateinit var originalDefaultLocale: Locale

    @BeforeEach
    fun pinDefaultLocaleToEnglish() {
        originalDefaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterEach
    fun restoreDefaultLocale() {
        Locale.setDefault(originalDefaultLocale)
    }

    @Test
    fun `live duration keeps second precision through one hour`() {
        // Formatters.duration() renders mm:ss digits through String.format's no-locale
        // overload, i.e. Locale.getDefault(). Pinned to English above so this does not
        // depend on the test runner's ambient locale.
        assertEquals("00:00", Formatters.liveDuration(0))
        assertEquals("01:05", Formatters.liveDuration(65_000))
        assertEquals("1:00:00", Formatters.liveDuration(3_600_000))
    }

    @Test
    fun `live duration rounds values above one hour upward to whole minutes`() {
        // Above the one-hour threshold, single-argument liveDuration resolves its locale
        // from AlarmTimerBundle.locale() (IDE DynamicBundle), not Locale.getDefault(). Use
        // the two-argument overload with an explicit locale so this does not depend on IDE
        // platform state.
        assertEquals("1h 01m", Formatters.liveDuration(3_600_001, Locale.ENGLISH))
        assertEquals("1h 01m", Formatters.liveDuration(3_660_000, Locale.ENGLISH))
        assertEquals("168h 00m", Formatters.liveDuration(7 * 24 * 60 * 60_000L, Locale.ENGLISH))
        assertEquals("168h 00m", Formatters.liveDuration(7 * 24 * 60 * 60_000L - 1, Locale.ENGLISH))
    }

    @Test
    fun `duration keeps a fixed mm colon ss shape under a locale whose default numbering system is non-ascii`() {
        // Under an explicit ASCII-digit locale, the rendered value is pinned exactly.
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("01:05", Formatters.duration(65_000))

        // Whether Formatters.duration renders ASCII or locale-native (e.g. Eastern
        // Arabic-Indic) digits under a non-Latin numbering system locale is an OPEN
        // PRODUCT QUESTION, not settled behavior — this test deliberately does NOT pin
        // the digit set in either direction. It only pins the structural shape (five
        // characters, colon separator at index 2), which holds true regardless of which
        // digit glyphs the JDK chooses for "ar-EG-u-nu-arab" (its `-u-nu-arab` extension
        // requests the Arabic-Indic numbering system, and java.util.Formatter's %d
        // conversion has honored Unicode locale extensions such as `nu` since the JDK 9
        // move to CLDR locale data — so the digits below could legitimately render as
        // ASCII or as Eastern Arabic-Indic; either is acceptable here).
        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
        val result = Formatters.duration(65_000)
        assertEquals(5, result.length)
        assertEquals(':', result[2])
    }

    @Test
    fun `editable duration round trips allowed whole-second values`() {
        val values = listOf(
            1_000L,
            59_000L,
            60_000L,
            61_000L,
            90_000L,
            3_599_000L,
            3_600_000L,
            3_661_000L,
            DurationParser.MAX_DURATION_MS,
        )

        values.forEach { millis ->
            assertEquals(millis, DurationParser.parse(Formatters.editableDuration(millis)).getOrThrow())
        }
    }

    @Test
    fun `editable duration rounds in-range milliseconds up without exceeding maximum`() {
        assertEquals(2_000, DurationParser.parse(Formatters.editableDuration(1_500)).getOrThrow())
        assertEquals(
            DurationParser.MAX_DURATION_MS,
            DurationParser.parse(Formatters.editableDuration(DurationParser.MAX_DURATION_MS - 1)).getOrThrow(),
        )
    }

    @Test
    fun `editable duration rejects out-of-range values`() {
        assertThrows(IllegalArgumentException::class.java) { Formatters.editableDuration(0) }
        assertThrows(IllegalArgumentException::class.java) {
            Formatters.editableDuration(DurationParser.MAX_DURATION_MS + 1_000)
        }
    }
}
