package com.onlinealarmkur.jetbrains.actions

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.onlinealarmkur.jetbrains.Urls
import com.onlinealarmkur.jetbrains.domain.ItemStatus
import com.onlinealarmkur.jetbrains.domain.ScheduledItem
import com.onlinealarmkur.jetbrains.service.AlarmTimerService
import com.onlinealarmkur.jetbrains.settings.AlarmTimerConfigurable
import com.onlinealarmkur.jetbrains.ui.AlarmTimerDestination
import com.onlinealarmkur.jetbrains.ui.AlarmTimerToolWindowRouting
import com.onlinealarmkur.jetbrains.ui.ClearHistoryConfirmation

open class OpenToolWindowAction(private val destination: AlarmTimerDestination? = null) : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Alarm & Timer") ?: return
        route(
            showToolWindow = { onShown -> toolWindow.show(onShown) },
            focusDestination = { AlarmTimerToolWindowRouting.focus(toolWindow, it) },
        )
    }
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    internal fun route(
        showToolWindow: ((() -> Unit) -> Unit),
        focusDestination: (AlarmTimerDestination) -> Unit,
    ) {
        AlarmTimerToolWindowRouting.route(destination, showToolWindow, focusDestination)
    }
}

class SetAlarmAction : OpenToolWindowAction(AlarmTimerDestination.ALARM)
class StartTimerAction : OpenToolWindowAction(AlarmTimerDestination.TIMER)

class DismissAllAlertsAction internal constructor(
    private val hasActiveAlerts: () -> Boolean,
    private val dismissAllAlerts: () -> Unit,
) : DumbAwareAction() {
    constructor() : this(
        hasActiveAlerts = { AlarmTimerService.getInstance().hasActiveAlerts() },
        dismissAllAlerts = { AlarmTimerService.getInstance().dismissAllAlerts() },
    )

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) = updatePresentation(e.presentation)

    override fun actionPerformed(e: AnActionEvent) = dismissAllAlerts()

    internal fun updatePresentation(presentation: Presentation) {
        presentation.isEnabled = hasActiveAlerts()
    }
}

class ClearCompletedAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = clearableCount(AlarmTimerService.getInstance().items()) > 0
    }

    /**
     * Clearing the history is permanent and has no undo, so the menu entry confirms first and the
     * count it shows must describe exactly what [AlarmTimerService.clearCompleted] will remove.
     */
    override fun actionPerformed(e: AnActionEvent) {
        val service = AlarmTimerService.getInstance()
        val count = clearableCount(service.items())
        if (count == 0) return
        val confirmed = Messages.showYesNoDialog(
            e.project,
            ClearHistoryConfirmation.message(count),
            ClearHistoryConfirmation.title(),
            null,
        ) == Messages.YES
        if (confirmed) service.clearCompleted()
    }

    /** Mirrors the selection made by `AlarmEngine.clearCompleted`; kept as a seam for tests. */
    internal fun clearableCount(items: List<ScheduledItem>): Int =
        items.count { it.status == ItemStatus.COMPLETED || it.status == ItemStatus.MISSED }
}

class ToggleSoundAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = selected(AlarmTimerService.getInstance())

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        setSelected(AlarmTimerService.getInstance(), state)
    }

    internal fun selected(service: AlarmTimerService): Boolean = service.settings().soundEnabled

    internal fun setSelected(service: AlarmTimerService, selected: Boolean) {
        val current = service.settings()
        if (current.soundEnabled != selected) {
            service.updateSettings(current.copy(soundEnabled = selected))
        }
    }
}

class OpenSettingsAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, AlarmTimerConfigurable::class.java)
    }
}

abstract class OpenUrlAction(private val url: String) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) = BrowserUtil.browse(url)
}

class OpenAlarmWebsiteAction : OpenUrlAction(Urls.ALARM_URL)
class OpenTimerWebsiteAction : OpenUrlAction(Urls.TIMER_URL)
class OpenDocumentationAction : OpenUrlAction(Urls.DOCUMENTATION_URL)
