package com.onlinealarmkur.jetbrains.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.ui.JBUI
import com.onlinealarmkur.jetbrains.AlarmTimerBundle
import com.onlinealarmkur.jetbrains.domain.Formatters
import com.onlinealarmkur.jetbrains.domain.ItemKind
import com.onlinealarmkur.jetbrains.domain.ScheduledItem
import com.onlinealarmkur.jetbrains.service.AlarmTimerService
import java.awt.Cursor
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.KeyStroke

class AlarmTimerStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = WIDGET_ID
    override fun getDisplayName() = AlarmTimerBundle.message("status.widget.display.name")
    override fun isAvailable(project: Project) = true
    override fun isEnabledByDefault() = true
    override fun createWidget(project: Project): StatusBarWidget = AlarmTimerStatusBarWidget(project)
}

internal class AlarmTimerStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val service = AlarmTimerService.getInstance()
    // A real button preserves the compact status-bar appearance while exposing push-button role
    // and AccessibleAction semantics. A focusable label with hand-written mouse/key listeners looks
    // similar, but assistive technology still announces it as inert text.
    private val button = JButton().apply {
        toolTipText = AlarmTimerBundle.message("status.widget.tooltip")
        border = JBUI.Borders.empty(0, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isFocusable = true
        isBorderPainted = false
        isContentAreaFilled = false
        isOpaque = false
        addActionListener { showToolWindow() }
        val openActionKey = "openAlarmTimerToolWindow"
        getInputMap(JComponent.WHEN_FOCUSED).apply {
            put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), openActionKey)
            put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), openActionKey)
        }
        actionMap.put(openActionKey, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) = showToolWindow()
        })
    }
    @Volatile private var statusBar: StatusBar? = null
    private var subscribed = false
    internal var updateRequests = 0
        private set
    internal var toolWindowShowRequests = 0
        private set

    init {
        refreshComponent()
    }

    override fun ID() = WIDGET_ID
    override fun getComponent(): JComponent = button
    // Subscribing belongs here, not in the constructor: a widget the platform creates but never
    // installs would otherwise hold this project in an application-level listener list for the
    // whole session. Disposal is unchanged — AlarmTimerService.addListener registers its own
    // removal against this widget as the parent disposable, whenever the subscription happened.
    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        if (!subscribed) {
            subscribed = true
            service.confirmUiReady()
            service.addListener(
                this,
                wantsClockPulses = { this.statusBar != null },
            ) {
                // Nothing here asks the status bar to re-query this widget: a CustomStatusBarWidget
                // owns its component, and that component repaints itself. Forcing a re-query would
                // only buy a second repaint per pulse, per project window.
                if (this.statusBar != null && refreshComponent()) updateRequests++
            }
        }
        refreshComponent()
    }
    override fun dispose() { statusBar = null }

    private fun showToolWindow() {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.let {
            it.show()
            toolWindowShowRequests++
        }
    }

    // Reports whether it applied a change. A pulse that produces the text already on screen must
    // not repaint, and must not restate the accessible name: that fires a property change event
    // every second under a screen reader.
    private fun refreshComponent(): Boolean {
        val text = AlarmTimerStatusProjection.text(
            nearest = service::nearestActive,
        )
        if (button.text == text && button.isVisible == text.isNotEmpty()) return false
        button.text = text
        button.isVisible = text.isNotEmpty()
        val accessibleName = if (text.isEmpty()) {
            AlarmTimerBundle.message("status.widget.accessible.empty")
        } else {
            AlarmTimerBundle.message("status.widget.accessible.value", text)
        }
        if (button.accessibleContext.accessibleName != accessibleName) {
            button.accessibleContext.accessibleName = accessibleName
        }
        button.revalidate()
        button.repaint()
        return true
    }
}

internal object AlarmTimerStatusProjection {
    fun text(nearest: () -> Pair<ScheduledItem, Long>?): String {
        val (item, remainingMs) = nearest() ?: return ""
        val remaining = Formatters.liveDuration(remainingMs)
        return if (item.kind == ItemKind.ALARM) {
            AlarmTimerBundle.message("status.alarm.in", remaining)
        } else {
            AlarmTimerBundle.message("status.timer", remaining)
        }
    }
}

private const val WIDGET_ID = "AlarmTimer.NextItem"
private const val TOOL_WINDOW_ID = "Alarm & Timer"
