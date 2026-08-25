package com.onlinealarmkur.jetbrains.domain

object AlarmTimerSettingsPolicy {
    const val DEFAULT_TIMER_MS = 5 * 60_000L
    const val DEFAULT_USE_24_HOUR_TIME = true
    const val DEFAULT_SHOW_STATUS_BAR_WIDGET = true
    const val DEFAULT_WARN_ON_EXIT_WITH_PENDING_ITEMS = true
    const val DEFAULT_OVERDUE_GRACE_PERIOD_MS = 5 * 60_000L
    const val DEFAULT_SOUND_ENABLED = true
    const val DEFAULT_VOLUME_PERCENT = 70

    const val MIN_DURATION_MS = 1_000L
    const val MAX_DURATION_MS = DurationParser.MAX_DURATION_MS
    const val MIN_OVERDUE_GRACE_PERIOD_MS = 0L
    const val MAX_OVERDUE_GRACE_PERIOD_MS = 24L * 60 * 60_000
    const val MIN_VOLUME_PERCENT = 1
    const val MAX_VOLUME_PERCENT = 100

    val DEFAULTS = AlarmTimerSettings(
        defaultTimerMs = DEFAULT_TIMER_MS,
        use24HourTime = DEFAULT_USE_24_HOUR_TIME,
        showStatusBarWidget = DEFAULT_SHOW_STATUS_BAR_WIDGET,
        warnOnExitWithPendingItems = DEFAULT_WARN_ON_EXIT_WITH_PENDING_ITEMS,
        overdueGracePeriodMs = DEFAULT_OVERDUE_GRACE_PERIOD_MS,
        soundEnabled = DEFAULT_SOUND_ENABLED,
        volumePercent = DEFAULT_VOLUME_PERCENT,
    )

    fun sanitizeRuntime(settings: AlarmTimerSettings): AlarmTimerSettings = settings.copy(
        defaultTimerMs = settings.defaultTimerMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS),
        overdueGracePeriodMs = settings.overdueGracePeriodMs.coerceIn(
            MIN_OVERDUE_GRACE_PERIOD_MS,
            MAX_OVERDUE_GRACE_PERIOD_MS,
        ),
        volumePercent = settings.volumePercent.coerceIn(MIN_VOLUME_PERCENT, MAX_VOLUME_PERCENT),
    )

    fun isValidDuration(value: Long): Boolean = value in MIN_DURATION_MS..MAX_DURATION_MS
}
