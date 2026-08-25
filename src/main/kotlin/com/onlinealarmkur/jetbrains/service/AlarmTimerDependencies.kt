package com.onlinealarmkur.jetbrains.service

import com.intellij.configurationStore.StoreUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.text.StringUtil
import com.onlinealarmkur.jetbrains.AlarmTimerBundle
import com.onlinealarmkur.jetbrains.domain.ItemKind
import com.onlinealarmkur.jetbrains.domain.ScheduledItem
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal interface AlarmScheduler {
    fun scheduleWithFixedDelay(task: () -> Unit, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledTask
    fun schedule(task: () -> Unit, delay: Long, unit: TimeUnit)
    fun shutdownNow()
}

internal interface ScheduledTask {
    fun cancel()

    // A repeating executor task ends permanently once its body throws, and the handle stays
    // non-null. Callers must probe liveness instead of treating a handle as proof of a live task.
    fun isAlive(): Boolean = true
}

internal class ExecutorAlarmScheduler : AlarmScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Alarm & Timer scheduler").apply { isDaemon = true }
    }

    override fun scheduleWithFixedDelay(
        task: () -> Unit,
        initialDelay: Long,
        delay: Long,
        unit: TimeUnit,
    ): ScheduledTask = executor.scheduleWithFixedDelay(task, initialDelay, delay, unit).let { future ->
        object : ScheduledTask {
            override fun cancel() {
                future.cancel(false)
            }

            // A fixed-delay future completes only when it is cancelled or its body threw.
            override fun isAlive(): Boolean = !future.isDone
        }
    }

    override fun schedule(task: () -> Unit, delay: Long, unit: TimeUnit) {
        executor.schedule(task, delay, unit)
    }

    override fun shutdownNow() {
        executor.shutdownNow()
    }
}

internal fun interface StateSaver {
    fun save()
}

internal class PlatformStateSaver : StateSaver {
    override fun save() {
        StoreUtil.saveSettings(ApplicationManager.getApplication(), true)
    }
}

internal interface UiDispatcher {
    fun isDispatchThread(): Boolean
    fun invokeLater(task: () -> Unit)
}

internal class PlatformUiDispatcher : UiDispatcher {
    override fun isDispatchThread(): Boolean = ApplicationManager.getApplication().isDispatchThread

    override fun invokeLater(task: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(task)
    }
}

internal fun interface AlarmSound {
    fun play(volumePercent: Int)

    fun stop() = Unit

    fun dispose() = stop()
}

internal fun interface AlertNotifier {
    fun create(item: ScheduledItem, dismiss: () -> Unit, expired: () -> Unit): AlertHandle
}

internal interface AlertHandle {
    fun deliver()
    fun expire()
}

internal class PlatformAlertNotifier : AlertNotifier {
    override fun create(
        item: ScheduledItem,
        dismiss: () -> Unit,
        expired: () -> Unit,
    ): AlertHandle {
        val text = notificationText(item)
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Alarm & Timer alerts")
            .createNotification(text.title, notificationSinkContent(text), NotificationType.INFORMATION)
        notification.addAction(
            NotificationAction.createSimpleExpiring(AlarmTimerBundle.message("notification.dismiss")) { dismiss() },
        )
        notification.whenExpired { expired() }
        return NotificationHandle(notification)
    }
}

internal fun notificationContent(item: ScheduledItem): String = notificationSinkContent(notificationText(item))

internal fun notificationSinkContent(text: NotificationText): String =
    if (text.contentIsUntrusted) StringUtil.escapeXmlEntities(text.content) else text.content

internal data class NotificationText(
    val title: String,
    val content: String,
    val contentIsUntrusted: Boolean,
)

internal fun notificationText(item: ScheduledItem): NotificationText = NotificationText(
    title = AlarmTimerBundle.message(if (item.kind == ItemKind.ALARM) "noun.alarm" else "noun.timer"),
    content = item.label.ifBlank {
        AlarmTimerBundle.message(if (item.kind == ItemKind.ALARM) "notification.alarm.body" else "notification.timer.body")
    },
    contentIsUntrusted = item.label.isNotBlank(),
)

internal class NotificationHandle(internal val notification: Notification) : AlertHandle {
    override fun deliver() {
        notification.notify(null)
    }

    override fun expire() {
        notification.expire()
    }
}
