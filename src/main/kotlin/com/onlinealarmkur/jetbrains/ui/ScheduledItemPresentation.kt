package com.onlinealarmkur.jetbrains.ui

import com.onlinealarmkur.jetbrains.AlarmTimerBundle
import com.onlinealarmkur.jetbrains.domain.Formatters
import com.onlinealarmkur.jetbrains.domain.ItemKind
import com.onlinealarmkur.jetbrains.domain.ItemStatus
import com.onlinealarmkur.jetbrains.domain.ScheduledItem
import java.util.Locale

internal data class ScheduledItemPresentation(
    val name: String,
    val detail: String,
)

internal object ScheduledItemPresentationFactory {
    fun present(
        item: ScheduledItem,
        use24HourTime: Boolean,
        remainingMs: Long,
        locale: Locale = AlarmTimerBundle.locale(),
    ): ScheduledItemPresentation {
        val name = item.label.ifBlank { noun(item.kind, locale) }
        val detail = when (item.status) {
            ItemStatus.ACTIVE -> if (item.kind == ItemKind.ALARM) {
                Formatters.dateTime(item.targetEpochMs, use24HourTime, locale = locale)
            } else {
                Formatters.liveDuration(remainingMs, locale)
            }
            ItemStatus.PAUSED -> AlarmTimerBundle.messageFor(locale, "status.paused", Formatters.duration(item.remainingMs))
            ItemStatus.COMPLETED -> AlarmTimerBundle.messageFor(locale,
                if (item.kind == ItemKind.ALARM) "status.alarm.triggered" else "status.timer.finished",
            )
            ItemStatus.MISSED -> AlarmTimerBundle.messageFor(locale,
                if (item.kind == ItemKind.ALARM) "status.alarm.missed" else "status.timer.missed",
            )
        }
        return ScheduledItemPresentation(name, detail)
    }

    private fun noun(kind: ItemKind, locale: Locale): String = AlarmTimerBundle.messageFor(locale,
        if (kind == ItemKind.ALARM) "noun.alarm" else "noun.timer",
    )
}
