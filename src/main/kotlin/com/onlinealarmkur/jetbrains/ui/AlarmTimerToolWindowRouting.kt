package com.onlinealarmkur.jetbrains.ui

import com.intellij.openapi.wm.ToolWindow

enum class AlarmTimerDestination {
    ALARM,
    TIMER,
}

internal object AlarmTimerToolWindowRouting {
    fun focus(toolWindow: ToolWindow, destination: AlarmTimerDestination) {
        toolWindow.contentManager.contents
            .asSequence()
            .map { it.component }
            .filterIsInstance<AlarmTimerPanel>()
            .firstOrNull()
            ?.focusDestination(destination)
    }

    fun route(
        destination: AlarmTimerDestination?,
        showToolWindow: ((() -> Unit) -> Unit),
        focusDestination: (AlarmTimerDestination) -> Unit,
    ) {
        showToolWindow {
            destination?.let(focusDestination)
        }
    }
}

internal class AlarmTimerDestinationFocus(
    private val selectTab: (Int) -> Unit,
    private val focusAlarmTime: () -> Unit,
    private val focusTimerDuration: () -> Unit,
) {
    fun focus(destination: AlarmTimerDestination) {
        when (destination) {
            AlarmTimerDestination.ALARM -> {
                selectTab(0)
                focusAlarmTime()
            }

            AlarmTimerDestination.TIMER -> {
                selectTab(1)
                focusTimerDuration()
            }
        }
    }
}
