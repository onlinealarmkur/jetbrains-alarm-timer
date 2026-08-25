package com.onlinealarmkur.jetbrains.settings

import com.intellij.openapi.options.ConfigurationException
import com.onlinealarmkur.jetbrains.domain.AlarmTimerSettings
import com.onlinealarmkur.jetbrains.domain.AlarmTimerSettingsPolicy
import com.onlinealarmkur.jetbrains.domain.Formatters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlarmTimerConfigurableTest {
    @Test
    fun `about label formats a valid packaged version and license`() {
        assertEquals(
            "Version 9.9.9 · MIT License",
            aboutLabel("version=9.9.9\n"),
        )
    }

    @Test
    fun `about label falls back for missing blank and malformed version resources`() {
        listOf(
            null,
            "version=   \n",
            "version=development\n",
            "version=\\u12\n",
        ).forEach { resource ->
            assertEquals("Version — · MIT License", aboutLabel(resource), resource)
        }
    }

    @Test
    fun `reset values preserve seconds and are immediately unmodified`() {
        val settings = AlarmTimerSettings(
            defaultTimerMs = 30_000,
            overdueGracePeriodMs = 90 * 60_000,
        )

        val values = AlarmTimerSettingsForm.fromSettings(settings)

        assertEquals("30s", values.timer)
        assertEquals("1h 30m", values.grace)
        assertFalse(AlarmTimerSettingsForm.isModified(values, settings))
    }

    @Test
    fun `compound durations and unrelated settings apply together`() {
        val current = AlarmTimerSettings(showStatusBarWidget = false)
        val values = AlarmTimerSettingsForm.fromSettings(current).copy(
            timer = "1h 30m 5s",
            warnOnExit = false,
        )
        var updated: AlarmTimerSettings? = null

        AlarmTimerSettingsForm.apply(values, current) { updated = it }

        assertEquals(5_405_000, updated?.defaultTimerMs)
        assertFalse(updated!!.showStatusBarWidget)
        assertFalse(updated!!.warnOnExitWithPendingItems)
    }

    @Test
    fun `exit warning checkbox round trips through the form`() {
        val warning = AlarmTimerSettings()
        val values = AlarmTimerSettingsForm.fromSettings(warning)

        assertTrue(values.warnOnExit)
        assertFalse(AlarmTimerSettingsForm.isModified(values, warning))

        val silent = warning.copy(warnOnExitWithPendingItems = false)

        assertFalse(AlarmTimerSettingsForm.fromSettings(silent).warnOnExit)
        assertTrue(AlarmTimerSettingsForm.isModified(values, silent))
    }

    @Test
    fun `sound preview uses unsaved enabled state and volume without parsing unrelated fields`() {
        val values = AlarmTimerSettingsForm.fromSettings(AlarmTimerSettings()).copy(
            timer = "not a duration",
            sound = true,
            volume = 37,
        )

        val preview = AlarmTimerSettingsForm.soundPreview(values)

        assertEquals(AlarmTimerSoundPreview(enabled = true, volumePercent = 37), preview)
    }

    @Test
    fun `sound preview preserves unsaved disabled state`() {
        val values = AlarmTimerSettingsForm.fromSettings(AlarmTimerSettings()).copy(
            sound = false,
            volume = 91,
        )

        val preview = AlarmTimerSettingsForm.soundPreview(values)

        assertEquals(AlarmTimerSoundPreview(enabled = false, volumePercent = 91), preview)
    }

    @Test
    fun `zero grace period resets to valid text and is unmodified`() {
        val settings = AlarmTimerSettings(overdueGracePeriodMs = 0)

        val values = AlarmTimerSettingsForm.fromSettings(settings)

        assertEquals("0s", values.grace)
        assertFalse(AlarmTimerSettingsForm.isModified(values, settings))
    }

    @Test
    fun `sub-second stored grace period reads as zero and stays unmodified`() {
        val settings = AlarmTimerSettings(overdueGracePeriodMs = 500)

        val values = AlarmTimerSettingsForm.fromSettings(settings)

        assertEquals("0s", values.grace)
        assertFalse(AlarmTimerSettingsForm.isModified(values, settings))
    }

    @Test
    fun `modification check survives an unrepresentable stored timer`() {
        val current = AlarmTimerSettings(defaultTimerMs = 0)
        val values = AlarmTimerSettingsFormValues(
            timer = "5m",
            use24Hour = true,
            warnOnExit = true,
            grace = "5m",
            sound = true,
            volume = 70,
        )

        assertTrue(AlarmTimerSettingsForm.isModified(values, current))
    }

    @Test
    fun `invalid field prevents partial settings mutation`() {
        val current = AlarmTimerSettings()
        val invalidCases = listOf(
            AlarmTimerSettingsForm.fromSettings(current).copy(timer = "later") to "Default timer",
            AlarmTimerSettingsForm.fromSettings(current).copy(grace = "25h") to "Overdue alert window",
        )

        invalidCases.forEach { (values, field) ->
            var updateCount = 0
            val error = assertThrows(ConfigurationException::class.java) {
                AlarmTimerSettingsForm.apply(values.copy(sound = !values.sound), current) { updateCount++ }
            }
            assertEquals(field, error.title)
            assertEquals(0, updateCount)
            assertTrue(AlarmTimerSettingsForm.isModified(values, current))
        }
    }

    @Test
    fun `settings form accepts shared exact bounds`() {
        val current = AlarmTimerSettings()
        val values = AlarmTimerSettingsForm.fromSettings(current).copy(
            timer = Formatters.editableDuration(AlarmTimerSettingsPolicy.MAX_DURATION_MS),
            grace = Formatters.editableDuration(AlarmTimerSettingsPolicy.MAX_OVERDUE_GRACE_PERIOD_MS),
            volume = AlarmTimerSettingsPolicy.MIN_VOLUME_PERCENT,
        )
        var updated: AlarmTimerSettings? = null

        AlarmTimerSettingsForm.apply(values, current) { updated = it }

        assertEquals(AlarmTimerSettingsPolicy.MAX_DURATION_MS, updated?.defaultTimerMs)
        assertEquals(
            AlarmTimerSettingsPolicy.MAX_OVERDUE_GRACE_PERIOD_MS,
            updated?.overdueGracePeriodMs,
        )
        assertEquals(AlarmTimerSettingsPolicy.MIN_VOLUME_PERCENT, updated?.volumePercent)
    }

    @Test
    fun `grace over shared maximum preserves configuration error`() {
        val current = AlarmTimerSettings()
        val values = AlarmTimerSettingsForm.fromSettings(current).copy(grace = "24h 1s")

        val error = assertThrows(ConfigurationException::class.java) {
            AlarmTimerSettingsForm.apply(values, current) {}
        }

        assertEquals("Overdue alert window", error.title)
        assertEquals("Duration cannot exceed 24 hours.", error.localizedMessage)
    }

    @Test
    fun `inline validation shares the same duration policy as apply`() {
        assertEquals(null, AlarmTimerSettingsForm.defaultTimerValidation("1h 30m"))
        assertEquals(null, AlarmTimerSettingsForm.graceValidation("0s"))
        assertEquals(
            "Use a value such as 90s, 10m, 1h 30m, or 01:30.",
            AlarmTimerSettingsForm.defaultTimerValidation("later"),
        )
        assertEquals("Duration cannot exceed 24 hours.", AlarmTimerSettingsForm.graceValidation("24h 1s"))
    }

    @Test
    fun `unrelated apply preserves raw persisted millisecond defaults`() {
        val current = AlarmTimerSettings(
            defaultTimerMs = 1_500,
            overdueGracePeriodMs = 3_500,
        )
        val values = AlarmTimerSettingsForm.fromSettings(current).copy(use24Hour = false)
        var updated: AlarmTimerSettings? = null

        AlarmTimerSettingsForm.apply(values, current) { updated = it }

        assertEquals(1_500, updated?.defaultTimerMs)
        assertEquals(3_500, updated?.overdueGracePeriodMs)
        assertFalse(updated!!.use24HourTime)
    }

    private fun aboutLabel(resource: String?): String =
        AlarmTimerVersionMetadata.aboutLabel {
            resource?.byteInputStream(Charsets.ISO_8859_1)
        }
}
