package com.onlinealarmkur.jetbrains.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBSlider
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.onlinealarmkur.jetbrains.AlarmTimerBundle
import com.onlinealarmkur.jetbrains.Urls
import com.onlinealarmkur.jetbrains.domain.AlarmTimerSettings
import com.onlinealarmkur.jetbrains.domain.AlarmTimerSettingsPolicy
import com.onlinealarmkur.jetbrains.domain.DurationParser
import com.onlinealarmkur.jetbrains.domain.Formatters
import com.onlinealarmkur.jetbrains.service.AlarmTimerService
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.Properties
import javax.swing.JComponent
import javax.swing.JTextField

class AlarmTimerConfigurable : SearchableConfigurable {
    private val service get() = AlarmTimerService.getInstance()
    private lateinit var timer: JTextField
    private lateinit var use24Hour: JBCheckBox
    private lateinit var warnOnExit: JBCheckBox
    private lateinit var gracePeriod: JTextField
    private lateinit var sound: JBCheckBox
    private lateinit var volume: JBSlider
    private lateinit var volumeValue: JBLabel
    private var panel: JComponent? = null

    override fun getId() = "com.onlinealarmkur.jetbrains.settings"
    override fun getDisplayName() = AlarmTimerBundle.message("settings.display.name")

    override fun createComponent(): JComponent = panel {
        group(AlarmTimerBundle.message("settings.group.general")) {
            row {
                label(AlarmTimerBundle.message("settings.global.note"))
            }
            row(AlarmTimerBundle.message("settings.default.timer.label")) {
                textField()
                    .align(AlignX.FILL)
                    .comment(AlarmTimerBundle.message("settings.default.timer.comment"))
                    .durationValidation(AlarmTimerSettingsForm::defaultTimerValidation)
                    .capture(
                        AlarmTimerSettingsComponentNames.DEFAULT_TIMER,
                        AlarmTimerBundle.message("settings.default.timer.accessible"),
                    ) { timer = it }
            }
            row {
                val text = AlarmTimerBundle.message("settings.use.24.hour")
                checkBox(text)
                    .capture(AlarmTimerSettingsComponentNames.USE_24_HOUR, text) { use24Hour = it }
            }
            row {
                val text = AlarmTimerBundle.message("settings.warn.on.exit")
                checkBox(text)
                    .capture(AlarmTimerSettingsComponentNames.WARN_ON_EXIT, text) {
                        warnOnExit = it
                    }
            }
            row(AlarmTimerBundle.message("settings.overdue.label")) {
                textField()
                    .align(AlignX.FILL)
                    .comment(AlarmTimerBundle.message("settings.overdue.comment"))
                    .durationValidation(AlarmTimerSettingsForm::graceValidation)
                    .capture(
                        AlarmTimerSettingsComponentNames.OVERDUE_GRACE,
                        AlarmTimerBundle.message("settings.overdue.accessible"),
                    ) {
                        gracePeriod = it
                    }
            }
        }
        group(AlarmTimerBundle.message("settings.group.alerts")) {
            lateinit var soundCell: Cell<JBCheckBox>
            row {
                val text = AlarmTimerBundle.message("settings.play.sound")
                soundCell = checkBox(text)
                    .capture(AlarmTimerSettingsComponentNames.SOUND_ENABLED, text) { sound = it }
            }
            row(AlarmTimerBundle.message("settings.volume.label")) {
                cell(
                    JBSlider(
                        AlarmTimerSettingsPolicy.MIN_VOLUME_PERCENT,
                        AlarmTimerSettingsPolicy.MAX_VOLUME_PERCENT,
                        AlarmTimerSettingsPolicy.DEFAULT_VOLUME_PERCENT,
                    ).apply {
                        majorTickSpacing = 10
                        paintTicks = true
                        addChangeListener { updateVolumeLabel() }
                    },
                )
                    .align(AlignX.FILL)
                    .capture(
                        AlarmTimerSettingsComponentNames.VOLUME,
                        AlarmTimerBundle.message("settings.volume.accessible"),
                    ) { volume = it }
                cell(JBLabel())
                    .capture(
                        AlarmTimerSettingsComponentNames.VOLUME_VALUE,
                        AlarmTimerBundle.message("settings.volume.percentage.accessible"),
                    ) {
                        volumeValue = it
                    }
            }.enabledIf(soundCell.selected)
            row {
                button(AlarmTimerBundle.message("settings.test.sound")) {
                    val preview = AlarmTimerSettingsForm.soundPreview(formValues())
                    service.testSound(preview.enabled, preview.volumePercent)
                }.capture(
                    AlarmTimerSettingsComponentNames.TEST_SOUND,
                    AlarmTimerBundle.message("settings.test.sound.accessible"),
                ) {}
            }.enabledIf(soundCell.selected)
            row {
                comment(AlarmTimerBundle.message("settings.volume.comment"))
            }.enabledIf(soundCell.selected)
        }
        group(AlarmTimerBundle.message("settings.group.about")) {
            row { label(AlarmTimerVersionMetadata.ABOUT_LABEL) }
            row {
                link(AlarmTimerBundle.message("settings.online.alarm.kur")) { BrowserUtil.browse(Urls.SITE_URL) }
                    .capture(
                        AlarmTimerSettingsComponentNames.ONLINE_ALARM_KUR,
                        AlarmTimerBundle.message("settings.open.online.alarm.kur"),
                    ) {}
                link(AlarmTimerBundle.message("settings.documentation")) { BrowserUtil.browse(Urls.DOCUMENTATION_URL) }
                    .capture(
                        AlarmTimerSettingsComponentNames.DOCUMENTATION,
                        AlarmTimerBundle.message("settings.open.documentation"),
                    ) {}
                link(AlarmTimerBundle.message("settings.issues")) { BrowserUtil.browse(Urls.ISSUES_URL) }
                    .capture(
                        AlarmTimerSettingsComponentNames.ISSUES,
                        AlarmTimerBundle.message("settings.open.issues"),
                    ) {}
            }
        }
    }.also { panel = it; reset() }

    override fun getPreferredFocusedComponent(): JComponent? =
        if (::timer.isInitialized) timer else null

    override fun isModified(): Boolean {
        return AlarmTimerSettingsForm.isModified(formValues(), service.settings())
    }

    override fun apply() {
        AlarmTimerSettingsForm.apply(formValues(), service.settings(), service::updateSettings)
    }

    override fun reset() {
        val values = AlarmTimerSettingsForm.fromSettings(service.settings())
        timer.text = values.timer
        use24Hour.isSelected = values.use24Hour
        warnOnExit.isSelected = values.warnOnExit
        gracePeriod.text = values.grace
        sound.isSelected = values.sound
        volume.value = values.volume
        updateVolumeLabel()
    }

    override fun disposeUIResources() { panel = null }

    private fun formValues() = AlarmTimerSettingsFormValues(
        timer = timer.text,
        use24Hour = use24Hour.isSelected,
        warnOnExit = warnOnExit.isSelected,
        grace = gracePeriod.text,
        sound = sound.isSelected,
        volume = volume.value,
    )

    private fun updateVolumeLabel() {
        if (::volume.isInitialized && ::volumeValue.isInitialized) {
            volumeValue.text = "${volume.value}%"
        }
    }
}

/**
 * Reads the version resource generated from the Gradle project version. The em dash fallback keeps
 * the About row rendering when the resource is missing or invalid.
 */
internal object AlarmTimerVersionMetadata {
    const val UNKNOWN_VERSION = "—"
    private const val VERSION_RESOURCE = "/com/onlinealarmkur/jetbrains/plugin-version.properties"
    private const val VERSION_PROPERTY = "version"
    private val STABLE_SEMANTIC_VERSION = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")

    val ABOUT_LABEL: String
        get() = aboutLabel {
            javaClass.getResourceAsStream(VERSION_RESOURCE)
        }

    internal fun aboutLabel(openResource: () -> InputStream?): String =
        AlarmTimerBundle.message("settings.about.version", readVersion(openResource))

    private fun readVersion(openResource: () -> InputStream?): String {
        val version = try {
            openResource()?.use { stream ->
                Properties().apply { load(stream) }.getProperty(VERSION_PROPERTY)
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return version
            ?.trim()
            ?.takeIf(STABLE_SEMANTIC_VERSION::matches)
            ?: UNKNOWN_VERSION
    }
}

internal data class AlarmTimerSettingsFormValues(
    val timer: String,
    val use24Hour: Boolean,
    val warnOnExit: Boolean,
    val grace: String,
    val sound: Boolean,
    val volume: Int,
)

internal data class AlarmTimerSoundPreview(
    val enabled: Boolean,
    val volumePercent: Int,
)

internal object AlarmTimerSettingsForm {
    fun defaultTimerValidation(value: String): String? = validationMessage {
        parseDuration(AlarmTimerBundle.message("settings.validation.default.timer.title"), value)
    }

    fun graceValidation(value: String): String? = validationMessage {
        parseGrace(value)
    }

    fun fromSettings(settings: AlarmTimerSettings) = AlarmTimerSettingsFormValues(
        timer = Formatters.editableDuration(settings.defaultTimerMs),
        use24Hour = settings.use24HourTime,
        warnOnExit = settings.warnOnExitWithPendingItems,
        grace = formatGrace(settings.overdueGracePeriodMs),
        sound = settings.soundEnabled,
        volume = settings.volumePercent,
    )

    fun isModified(values: AlarmTimerSettingsFormValues, current: AlarmTimerSettings): Boolean = try {
        nextSettings(values, current) != current
    } catch (_: ConfigurationException) {
        true
    } catch (_: IllegalArgumentException) {
        true
    }

    fun soundPreview(values: AlarmTimerSettingsFormValues) = AlarmTimerSoundPreview(
        enabled = values.sound,
        volumePercent = values.volume,
    )

    fun apply(
        values: AlarmTimerSettingsFormValues,
        current: AlarmTimerSettings,
        update: (AlarmTimerSettings) -> Unit,
    ) {
        val next = nextSettings(values, current)
        update(next)
    }

    private fun nextSettings(
        values: AlarmTimerSettingsFormValues,
        current: AlarmTimerSettings,
    ): AlarmTimerSettings {
        val timer = parseDuration(AlarmTimerBundle.message("settings.validation.default.timer.title"), values.timer)
        val grace = parseGrace(values.grace)
        return current.copy(
            defaultTimerMs = preserveUnchanged(values.timer, current.defaultTimerMs, timer),
            use24HourTime = values.use24Hour,
            warnOnExitWithPendingItems = values.warnOnExit,
            overdueGracePeriodMs = preserveUnchangedGrace(values.grace, current.overdueGracePeriodMs, grace),
            soundEnabled = values.sound,
            volumePercent = values.volume,
        )
    }

    private fun parseDuration(field: String, value: String): Long = DurationParser.parse(value).getOrElse { error ->
        throw ConfigurationException(
            error.message ?: AlarmTimerBundle.message("settings.validation.enter.duration"),
            field,
        )
    }

    private fun parseGrace(value: String): Long {
        val normalized = value.trim().lowercase(Locale.ROOT)
        val parsed = if (normalized == "0" || normalized == "0s") 0L
        else parseDuration(AlarmTimerBundle.message("settings.validation.overdue.title"), value)
        if (parsed > AlarmTimerSettingsPolicy.MAX_OVERDUE_GRACE_PERIOD_MS) {
            throw ConfigurationException(
                AlarmTimerBundle.message("settings.validation.overdue.maximum"),
                AlarmTimerBundle.message("settings.validation.overdue.title"),
            )
        }
        return parsed
    }

    private fun preserveUnchanged(value: String, current: Long, parsed: Long): Long =
        if (value.trim() == Formatters.editableDuration(current)) current else parsed

    private fun preserveUnchangedGrace(value: String, current: Long, parsed: Long): Long =
        if (value.trim() == formatGrace(current)) current else parsed

    /**
     * Total for every representable grace period. The policy minimum is 0 while
     * [Formatters.editableDuration] starts at one second, so a stored sub-second value (which the
     * settings UI can never produce) renders as `0s` instead of throwing on every form read.
     */
    private fun formatGrace(millis: Long): String =
        if (millis < 1_000L) "0s" else Formatters.editableDuration(millis)

    private fun validationMessage(validation: () -> Unit): String? = try {
        validation()
        null
    } catch (error: ConfigurationException) {
        error.localizedMessage
    }
}

internal object AlarmTimerSettingsComponentNames {
    const val DEFAULT_TIMER = "alarmTimer.settings.defaultTimer"
    const val USE_24_HOUR = "alarmTimer.settings.use24Hour"
    const val WARN_ON_EXIT = "alarmTimer.settings.warnOnExit"
    const val OVERDUE_GRACE = "alarmTimer.settings.overdueGrace"
    const val SOUND_ENABLED = "alarmTimer.settings.soundEnabled"
    const val VOLUME = "alarmTimer.settings.volume"
    const val VOLUME_VALUE = "alarmTimer.settings.volumeValue"
    const val TEST_SOUND = "alarmTimer.settings.testSound"
    const val ONLINE_ALARM_KUR = "alarmTimer.settings.onlineAlarmKur"
    const val DOCUMENTATION = "alarmTimer.settings.documentation"
    const val ISSUES = "alarmTimer.settings.issues"
}

private fun Cell<JBTextField>.durationValidation(validation: (String) -> String?): Cell<JBTextField> =
    validationOnInput { field -> validation(field.text)?.let { ValidationInfo(it, field) } }
        .validationOnApply { field -> validation(field.text)?.let { ValidationInfo(it, field) } }

private fun <T : JComponent> Cell<T>.capture(
    componentName: String,
    accessibleName: String,
    capture: (T) -> Unit,
): Cell<T> = apply {
    component.name = componentName
    component.accessibleContext.accessibleName = accessibleName
    capture(component)
}
