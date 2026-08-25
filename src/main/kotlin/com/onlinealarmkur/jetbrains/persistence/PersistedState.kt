package com.onlinealarmkur.jetbrains.persistence

import com.onlinealarmkur.jetbrains.domain.AlarmTimerSettings
import com.onlinealarmkur.jetbrains.domain.AlarmTimerSettingsPolicy
import com.onlinealarmkur.jetbrains.domain.ItemKind
import com.onlinealarmkur.jetbrains.domain.ItemStatus
import com.onlinealarmkur.jetbrains.domain.ScheduledItem
import com.onlinealarmkur.jetbrains.domain.normalizeLabel

class PersistedState {
    // Keep this default distinct from CURRENT_SCHEMA_VERSION. The platform serializer omits every
    // field that still equals its bean default, so only a stamped version reaches alarm-timer.xml,
    // and a file written before the field existed must read back as legacy rather than as current.
    var schemaVersion: Int = 0
    var settings: PersistedSettings = PersistedSettings()
    var items: MutableList<PersistedItem> = mutableListOf()
}

class PersistedSettings {
    var defaultTimerMs: Long = AlarmTimerSettingsPolicy.DEFAULT_TIMER_MS
    var use24HourTime: Boolean = AlarmTimerSettingsPolicy.DEFAULT_USE_24_HOUR_TIME
    var showStatusBarWidget: Boolean = AlarmTimerSettingsPolicy.DEFAULT_SHOW_STATUS_BAR_WIDGET
    // Added after schema 2. A file written before the field existed simply omits it, and the bean
    // default then reads back as the shipped default, so this needs no schema bump or migration.
    var warnOnExitWithPendingItems: Boolean = AlarmTimerSettingsPolicy.DEFAULT_WARN_ON_EXIT_WITH_PENDING_ITEMS
    var overdueGracePeriodMs: Long = AlarmTimerSettingsPolicy.DEFAULT_OVERDUE_GRACE_PERIOD_MS
    var soundEnabled: Boolean = AlarmTimerSettingsPolicy.DEFAULT_SOUND_ENABLED
    var volumePercent: Int = AlarmTimerSettingsPolicy.DEFAULT_VOLUME_PERCENT
}

class PersistedItem {
    var id: String = ""
    var kind: String = ""
    var label: String = ""
    var createdAtEpochMs: Long = 0
    var targetEpochMs: Long = 0
    var durationMs: Long = 0
    var remainingMs: Long = 0
    var status: String = ""
    var firedAtEpochMs: Long = 0
    var deliveryPending: Boolean = false
}

data class ValidatedState(
    val settings: AlarmTimerSettings,
    val items: List<ScheduledItem>,
    // True when the stored file belongs to a newer schema this build cannot represent. The caller
    // must keep that file instead of saving the empty in-memory state over it.
    val foreignSchema: Boolean = false,
)

object StateCodec {
    fun encode(settings: AlarmTimerSettings, items: List<ScheduledItem>) = PersistedState().also { state ->
        state.schemaVersion = CURRENT_SCHEMA_VERSION
        state.settings = PersistedSettings().also {
            it.defaultTimerMs = settings.defaultTimerMs
            it.use24HourTime = settings.use24HourTime
            it.showStatusBarWidget = settings.showStatusBarWidget
            it.warnOnExitWithPendingItems = settings.warnOnExitWithPendingItems
            it.overdueGracePeriodMs = settings.overdueGracePeriodMs
            it.soundEnabled = settings.soundEnabled
            it.volumePercent = settings.volumePercent
        }
        state.items = items.map { item ->
            PersistedItem().also {
                it.id = item.id
                it.kind = item.kind.name
                it.label = item.label
                it.createdAtEpochMs = item.createdAtEpochMs
                it.targetEpochMs = item.targetEpochMs
                it.durationMs = item.durationMs
                it.remainingMs = item.remainingMs
                it.status = item.status.name
                it.firedAtEpochMs = item.firedAtEpochMs
                it.deliveryPending = item.deliveryPending
            }
        }.toMutableList()
    }

    fun decode(raw: PersistedState): ValidatedState {
        val defaults = AlarmTimerSettingsPolicy.DEFAULTS
        if (raw.schemaVersion > CURRENT_SCHEMA_VERSION) {
            return ValidatedState(defaults, emptyList(), foreignSchema = true)
        }
        // Decode migrations into domain values without modifying the serializer-owned bean. If a
        // later field proves unreadable, AlarmTimerService must be able to preserve the exact bean
        // it received instead of saving a half-migrated representation over the user's file.
        val isLegacySoundSchema = raw.schemaVersion < 2
        // The serializer fills these beans by reflection, so a hostile or truncated file can put a
        // null where the Kotlin type says it cannot be. Read across that boundary defensively.
        val persistedSettings = runCatching { raw.settings }.getOrNull() ?: PersistedSettings()
        val settings = persistedSettings.let {
            val legacyMuted = isLegacySoundSchema && it.volumePercent <= 0
            AlarmTimerSettings(
                defaultTimerMs = it.defaultTimerMs.takeIf(AlarmTimerSettingsPolicy::isValidDuration)
                    ?: defaults.defaultTimerMs,
                use24HourTime = it.use24HourTime,
                showStatusBarWidget = it.showStatusBarWidget,
                warnOnExitWithPendingItems = it.warnOnExitWithPendingItems,
                overdueGracePeriodMs = it.overdueGracePeriodMs.coerceIn(
                    AlarmTimerSettingsPolicy.MIN_OVERDUE_GRACE_PERIOD_MS,
                    AlarmTimerSettingsPolicy.MAX_OVERDUE_GRACE_PERIOD_MS,
                ),
                soundEnabled = if (legacyMuted) false else it.soundEnabled,
                volumePercent = if (legacyMuted) {
                    AlarmTimerSettingsPolicy.DEFAULT_VOLUME_PERCENT
                } else {
                    it.volumePercent.coerceIn(
                        AlarmTimerSettingsPolicy.MIN_VOLUME_PERCENT,
                        AlarmTimerSettingsPolicy.MAX_VOLUME_PERCENT,
                    )
                },
            )
        }
        val seen = mutableSetOf<String>()
        val persistedItems = runCatching { raw.items }.getOrNull() ?: emptyList()
        val items = persistedItems.mapNotNull { value ->
            val id = runCatching { value.id }.getOrNull()
                ?.takeIf { it.isNotBlank() && it.length <= MAX_PERSISTED_ID_LENGTH }
                ?: return@mapNotNull null
            val kind = runCatching { ItemKind.valueOf(value.kind) }.getOrNull() ?: return@mapNotNull null
            val status = runCatching { ItemStatus.valueOf(value.status) }.getOrNull() ?: return@mapNotNull null
            if (value.createdAtEpochMs <= 0 || value.targetEpochMs <= 0) return@mapNotNull null
            if (kind == ItemKind.TIMER && !AlarmTimerSettingsPolicy.isValidDuration(value.durationMs)) return@mapNotNull null
            if (status == ItemStatus.PAUSED && (kind != ItemKind.TIMER || value.remainingMs < 1)) return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            ScheduledItem(
                id = id,
                kind = kind,
                label = normalizeLabel(runCatching { value.label }.getOrNull() ?: ""),
                createdAtEpochMs = value.createdAtEpochMs,
                targetEpochMs = value.targetEpochMs,
                durationMs = if (kind == ItemKind.TIMER) value.durationMs else 0,
                // A wall clock that moved backward while the IDE was closed can store a paused
                // remainder past the timer duration. Repair it instead of dropping the timer.
                remainingMs = if (status == ItemStatus.PAUSED) {
                    value.remainingMs.coerceAtMost(value.durationMs)
                } else {
                    0
                },
                status = status,
                firedAtEpochMs = value.firedAtEpochMs.coerceAtLeast(0),
                deliveryPending = value.deliveryPending &&
                    status == ItemStatus.COMPLETED &&
                    value.firedAtEpochMs > 0,
            )
        }
        return ValidatedState(settings, items)
    }
}

private const val MAX_PERSISTED_ID_LENGTH = 100
const val CURRENT_SCHEMA_VERSION = 2
