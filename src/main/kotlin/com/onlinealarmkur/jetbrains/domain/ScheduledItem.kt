package com.onlinealarmkur.jetbrains.domain

enum class ItemKind { ALARM, TIMER }

enum class ItemStatus { ACTIVE, PAUSED, COMPLETED, MISSED }

data class ScheduledItem(
    val id: String,
    val kind: ItemKind,
    val label: String,
    val createdAtEpochMs: Long,
    val targetEpochMs: Long,
    val durationMs: Long = 0,
    val remainingMs: Long = 0,
    val status: ItemStatus = ItemStatus.ACTIVE,
    val firedAtEpochMs: Long = 0,
    val deliveryPending: Boolean = false,
)

data class AlarmTimerSettings(
    val defaultTimerMs: Long = AlarmTimerSettingsPolicy.DEFAULT_TIMER_MS,
    val use24HourTime: Boolean = AlarmTimerSettingsPolicy.DEFAULT_USE_24_HOUR_TIME,
    // Retained for persisted-state compatibility. Widget visibility is owned by the IDE registry.
    val showStatusBarWidget: Boolean = AlarmTimerSettingsPolicy.DEFAULT_SHOW_STATUS_BAR_WIDGET,
    val warnOnExitWithPendingItems: Boolean = AlarmTimerSettingsPolicy.DEFAULT_WARN_ON_EXIT_WITH_PENDING_ITEMS,
    val overdueGracePeriodMs: Long = AlarmTimerSettingsPolicy.DEFAULT_OVERDUE_GRACE_PERIOD_MS,
    val soundEnabled: Boolean = AlarmTimerSettingsPolicy.DEFAULT_SOUND_ENABLED,
    val volumePercent: Int = AlarmTimerSettingsPolicy.DEFAULT_VOLUME_PERCENT,
)

data class DueItem(val item: ScheduledItem, val shouldAlert: Boolean)
