package com.onlinealarmkur.jetbrains.platform

import com.intellij.ide.AppLifecycleListener
import com.intellij.notification.Notification
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.util.ui.EmptyIcon
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBSlider
import com.onlinealarmkur.jetbrains.actions.ClearCompletedAction
import com.onlinealarmkur.jetbrains.actions.DismissAllAlertsAction
import com.onlinealarmkur.jetbrains.actions.OpenAlarmWebsiteAction
import com.onlinealarmkur.jetbrains.actions.OpenDocumentationAction
import com.onlinealarmkur.jetbrains.actions.OpenSettingsAction
import com.onlinealarmkur.jetbrains.actions.OpenTimerWebsiteAction
import com.onlinealarmkur.jetbrains.actions.OpenToolWindowAction
import com.onlinealarmkur.jetbrains.actions.SetAlarmAction
import com.onlinealarmkur.jetbrains.actions.StartTimerAction
import com.onlinealarmkur.jetbrains.actions.ToggleSoundAction
import com.onlinealarmkur.jetbrains.domain.AlarmTimeText
import com.onlinealarmkur.jetbrains.domain.AlarmTimerSettings
import com.onlinealarmkur.jetbrains.domain.ItemKind
import com.onlinealarmkur.jetbrains.domain.ItemStatus
import com.onlinealarmkur.jetbrains.domain.ScheduledItem
import com.onlinealarmkur.jetbrains.persistence.PersistedState
import com.onlinealarmkur.jetbrains.persistence.StateCodec
import com.onlinealarmkur.jetbrains.service.AlarmTimerExitGuard
import com.onlinealarmkur.jetbrains.service.AlarmTimerLifecycleListener
import com.onlinealarmkur.jetbrains.service.AlarmTimerService
import com.onlinealarmkur.jetbrains.service.ExitWarningDecision
import com.onlinealarmkur.jetbrains.service.ExitWarningProjection
import com.onlinealarmkur.jetbrains.service.NotificationHandle
import com.onlinealarmkur.jetbrains.service.PlatformAlertNotifier
import com.onlinealarmkur.jetbrains.settings.AlarmTimerConfigurable
import com.onlinealarmkur.jetbrains.settings.AlarmTimerSettingsComponentNames
import com.onlinealarmkur.jetbrains.settings.AlarmTimerVersionMetadata
import com.onlinealarmkur.jetbrains.ui.AlarmTimerPanel
import com.onlinealarmkur.jetbrains.ui.AlarmTimerPanelComponentNames
import com.onlinealarmkur.jetbrains.ui.ResponsiveButtonGrid
import com.onlinealarmkur.jetbrains.ui.AlarmTimerStatusBarWidget
import com.onlinealarmkur.jetbrains.ui.AlarmTimerStatusBarWidgetFactory
import com.onlinealarmkur.jetbrains.ui.AlarmTimerToolWindowFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.time.ZonedDateTime
import javax.accessibility.AccessibleRole
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.JTextField

@TestApplication
@TestFixtures
@RunInEdt
@org.junit.jupiter.api.Tag("platform")
class AlarmTimerPlatformSmokeTest {
    private val project by projectFixture(tempPathFixture())

    @Test
    fun `plugin registrations and application state work together`() {
        val application = ApplicationManager.getApplication()
        assertTrue(application.isDispatchThread)

        val service = application.getService(AlarmTimerService::class.java)
        assertSame(service, AlarmTimerService.getInstance())

        try {
            assertRegisteredActions(service)
            assertConfigurableLifecycle()
            assertStateRoundTrip(service)
        } finally {
            service.dismissAllAlerts()
            service.loadState(PersistedState())
        }
    }

    @Test
    fun `tool window content follows the real project lifecycle`() {
        val manager = ToolWindowManager.getInstance(project)
        val factory = AlarmTimerToolWindowFactory()
        val toolWindow = manager.registerToolWindow(
            RegisterToolWindowTask.lazyAndClosable(
                TOOL_WINDOW_ID,
                factory,
                EmptyIcon.create(16),
            ),
        )
        assertSame(toolWindow, manager.getToolWindow(TOOL_WINDOW_ID))
        factory.createToolWindowContent(project, toolWindow)
        val contentManager = toolWindow.getContentManager()
        val content = contentManager.contents.single()
        val panel = assertInstanceOf(AlarmTimerPanel::class.java, content.getComponent())
        assertSame(panel, content.getDisposer())

        assertTrue(contentManager.removeContent(content, true))
        assertTrue(panel.isDisposed)
    }

    @Test
    fun `panel component identities are unique and accessible`() = withPanel { _, panel ->
        PANEL_COMPONENT_NAMES.forEach { componentName ->
            val component = namedComponent<JComponent>(panel, componentName)
            assertFalse(
                component.accessibleContext.accessibleName.isNullOrBlank(),
                "Component $componentName must have an accessible name",
            )
        }

        panel.focusDestination(com.onlinealarmkur.jetbrains.ui.AlarmTimerDestination.TIMER)
        panel.setSize(COMPACT_PANEL_WIDTH, 720)
        layoutRecursively(panel)

        val limitation = namedComponent<JLabel>(panel, AlarmTimerPanelComponentNames.IDE_RUNNING_LIMIT)
        assertEquals(limitation.text, limitation.toolTipText)

        val presetButtons = TIMER_PRESET_COMPONENTS.map { (_, name) -> namedComponent<JButton>(panel, name) }
        val presetRows = presetButtons.groupBy { button ->
            SwingUtilities.convertPoint(button.parent, button.location, panel).y
        }
        assertEquals(listOf(3, 3), presetRows.values.map(List<JButton>::size).sorted())

        val activeButtons = listOf(
            AlarmTimerPanelComponentNames.ACTIVE_PAUSE_RESUME,
            AlarmTimerPanelComponentNames.ACTIVE_RESTART,
            AlarmTimerPanelComponentNames.ACTIVE_EDIT_ALARM,
            AlarmTimerPanelComponentNames.ACTIVE_CANCEL,
        ).map { namedComponent<JButton>(panel, it) }
        val historyButtons = listOf(
            AlarmTimerPanelComponentNames.HISTORY_REMOVE,
            AlarmTimerPanelComponentNames.HISTORY_CLEAR_ALL,
        ).map { namedComponent<JButton>(panel, it) }

        assertSame(activeButtons.first().parent, activeButtons.last().parent)
        assertSame(historyButtons.first().parent, historyButtons.last().parent)
        assertTrue(assertInstanceOf(ResponsiveButtonGrid::class.java, activeButtons.first().parent).columnCount in 1..2)
        assertTrue(assertInstanceOf(ResponsiveButtonGrid::class.java, historyButtons.first().parent).columnCount in 1..2)
        (activeButtons + historyButtons).forEach { button ->
            assertTrue(button.width >= button.preferredSize.width, "${button.name} text is clipped")
        }
        (presetButtons + activeButtons + historyButtons).forEach { button ->
            assertPositiveSizeWithinParentAndPanel(button, panel)
        }
    }

    @Test
    fun `open alarm form follows time format settings without overwriting user text`() = withPanel { service, panel ->
        val alarmTime = namedComponent<JTextField>(panel, AlarmTimerPanelComponentNames.ALARM_TIME)
        assertTrue(service.settings().use24HourTime)

        service.updateSettings(service.settings().copy(use24HourTime = false))

        assertTrue(alarmTime.text.endsWith(" AM") || alarmTime.text.endsWith(" PM"))

        alarmTime.text = "14:30"
        service.updateSettings(service.settings().copy(use24HourTime = true))

        assertEquals("14:30", alarmTime.text)
    }

    @Test
    fun `timer form and active controls use their real Swing listeners`() = withPanel { service, panel ->
        val duration = namedComponent<JTextField>(panel, AlarmTimerPanelComponentNames.TIMER_DURATION)
        val label = namedComponent<JTextField>(panel, AlarmTimerPanelComponentNames.TIMER_LABEL)
        val start = namedComponent<JButton>(panel, AlarmTimerPanelComponentNames.TIMER_START)
        val activeList = itemList(panel, AlarmTimerPanelComponentNames.ACTIVE_LIST)
        val pauseResume = namedComponent<JButton>(panel, AlarmTimerPanelComponentNames.ACTIVE_PAUSE_RESUME)
        val restart = namedComponent<JButton>(panel, AlarmTimerPanelComponentNames.ACTIVE_RESTART)
        val editAlarm = namedComponent<JButton>(panel, AlarmTimerPanelComponentNames.ACTIVE_EDIT_ALARM)
        val cancel = namedComponent<JButton>(panel, AlarmTimerPanelComponentNames.ACTIVE_CANCEL)
        val scopedControls = listOf(pauseResume, restart, editAlarm, cancel)

        assertControlsDisabled(scopedControls)
        duration.text = "1h"
        label.text = "Listener timer"
        start.doClick(0)

        val timer = service.items().single()
        assertEquals(ItemKind.TIMER, timer.kind)
        assertEquals("Listener timer", timer.label)
        assertEquals(3_600_000, timer.durationMs)
        selectItem(activeList, timer)
        assertTrue(pauseResume.isEnabled)
        assertEquals("Pause selected timer", pauseResume.accessibleContext.accessibleName)
        assertTrue(restart.isEnabled)
        assertFalse(editAlarm.isEnabled)
        assertTrue(cancel.isEnabled)

        pauseResume.doClick(0)
        val paused = service.items().single { it.id == timer.id }
        assertEquals(ItemStatus.PAUSED, paused.status)
        assertEquals("Resume selected timer", pauseResume.accessibleContext.accessibleName)
        assertTrue(paused.remainingMs in 1..timer.durationMs)

        pauseResume.doClick(0)
        assertEquals(ItemStatus.ACTIVE, service.items().single { it.id == timer.id }.status)

        restart.doClick(0)
        val restarted = service.items().single { it.id == timer.id }
        assertEquals(timer.durationMs, restarted.durationMs)
        assertEquals(ItemStatus.ACTIVE, restarted.status)
        assertTrue(service.remainingMs(timer.id) in (timer.durationMs - CONTROL_TIME_TOLERANCE_MS)..timer.durationMs)

        activeList.clearSelection()
        assertControlsDisabled(scopedControls)
        selectItem(activeList, restarted)

        cancel.doClick(0)
        assertTrue(service.items().isEmpty())
        assertTrue(listItems(activeList).isEmpty())
        assertControlsDisabled(scopedControls)
    }

    @Test
    fun `quick timer presets use their real Swing listeners`() = withPanel { service, panel ->
        val duration = namedComponent<JTextField>(panel, AlarmTimerPanelComponentNames.TIMER_DURATION)
        val label = namedComponent<JTextField>(panel, AlarmTimerPanelComponentNames.TIMER_LABEL)

        TIMER_PRESET_COMPONENTS.forEach { (minutes, componentName) ->
            label.text = "Preset $minutes"
            namedComponent<JButton>(panel, componentName).doClick(0)

            val timer = service.items().single()
            assertEquals(minutes * 60_000L, timer.durationMs)
            assertEquals("Preset $minutes", timer.label)
            assertEquals("${minutes}m", duration.text)
            assertTrue(service.cancel(timer.id))
        }
    }

    @Test
    fun `alarm form supports create cancel edit and save through real controls`() = withPanel { service, panel ->
        val submit = namedComponent<JButton>(panel, AlarmTimerPanelComponentNames.ALARM_SUBMIT)
        val cancelEdit = namedComponent<JButton>(panel, AlarmTimerPanelComponentNames.ALARM_CANCEL_EDIT)
        val edit = namedComponent<JButton>(panel, AlarmTimerPanelComponentNames.ACTIVE_EDIT_ALARM)
        val activeList = itemList(panel, AlarmTimerPanelComponentNames.ACTIVE_LIST)
        val initialTarget = safeFutureAlarm(dayOffset = 2, minute = 34)

        enterAlarm(panel, service, initialTarget, "Initial alarm")
        submit.doClick(0)

        val created = service.items().single()
        assertEquals(ItemKind.ALARM, created.kind)
        assertEquals("Initial alarm", created.label)
        assertEquals(initialTarget.toInstant().toEpochMilli(), created.targetEpochMs)
        selectItem(activeList, created)
        assertTrue(edit.isEnabled)

        edit.doClick(0)
        assertTrue(cancelEdit.isVisible)
        assertEquals("Save Alarm", submit.text)
        cancelEdit.doClick(0)
        assertFalse(cancelEdit.isVisible)
        assertEquals("Set Alarm", submit.text)
        assertEquals(created, service.items().single())

        edit.doClick(0)
        assertTrue(cancelEdit.isVisible)
        assertEquals("Save Alarm", submit.text)
        val updatedTarget = safeFutureAlarm(dayOffset = 3, minute = 35)
        enterAlarm(panel, service, updatedTarget, "Updated alarm")
        submit.doClick(0)

        val updated = service.items().single()
        assertEquals(created.id, updated.id)
        assertEquals("Updated alarm", updated.label)
        assertEquals(updatedTarget.toInstant().toEpochMilli(), updated.targetEpochMs)
        assertFalse(cancelEdit.isVisible)
        assertEquals("Set Alarm", submit.text)
    }

    @Test
    fun `history remove deletes only the selected item through the real control`() = withPanel { service, panel ->
        val historyItems = loadHistory(service)
        val completed = historyItems.single { it.status == ItemStatus.COMPLETED }
        val missed = historyItems.single { it.status == ItemStatus.MISSED }
        val historyList = itemList(panel, AlarmTimerPanelComponentNames.HISTORY_LIST)
        val remove = namedComponent<JButton>(panel, AlarmTimerPanelComponentNames.HISTORY_REMOVE)
        val clearAll = namedComponent<JButton>(panel, AlarmTimerPanelComponentNames.HISTORY_CLEAR_ALL)

        assertFalse(remove.isEnabled)
        selectItem(historyList, missed)
        assertTrue(remove.isEnabled)
        remove.doClick(0)

        assertEquals(setOf(completed.id), service.items().mapTo(mutableSetOf()) { it.id })
        assertEquals(setOf(completed.id), listItems(historyList).mapTo(mutableSetOf()) { it.id })
        assertFalse(remove.isEnabled)
        assertTrue(clearAll.isEnabled)
    }

    @Test
    fun `history clear all removes every row and disables controls`() = withPanel { service, panel ->
        val historyItems = loadHistory(service)
        val historyList = itemList(panel, AlarmTimerPanelComponentNames.HISTORY_LIST)
        val remove = namedComponent<JButton>(panel, AlarmTimerPanelComponentNames.HISTORY_REMOVE)
        val clearAll = namedComponent<JButton>(panel, AlarmTimerPanelComponentNames.HISTORY_CLEAR_ALL)

        selectItem(historyList, historyItems.single { it.status == ItemStatus.COMPLETED })
        assertTrue(remove.isEnabled)
        assertTrue(clearAll.isEnabled)
        val previousDialog = TestDialogManager.setTestDialog(TestDialog.YES)
        try {
            clearAll.doClick(0)
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }

        assertTrue(service.items().isEmpty())
        assertTrue(listItems(historyList).isEmpty())
        assertFalse(remove.isEnabled)
        assertFalse(clearAll.isEnabled)
    }

    @Test
    fun `disposed panel no longer follows service changes`() {
        val service = AlarmTimerService.getInstance()
        resetPanelTestService(service)
        val owner = Disposer.newDisposable()
        val panel = AlarmTimerPanel()
        Disposer.register(owner, panel)
        var disposed = false

        try {
            val activeList = itemList(panel, AlarmTimerPanelComponentNames.ACTIVE_LIST)
            assertTrue(listItems(activeList).isEmpty())

            Disposer.dispose(owner)
            disposed = true
            assertTrue(panel.isDisposed)
            service.startTimer(3_600_000, "After disposal")

            assertEquals(1, service.items().size)
            assertTrue(listItems(activeList).isEmpty())
        } finally {
            if (!disposed) Disposer.dispose(owner)
            service.dismissAllAlerts()
            service.loadState(PersistedState())
        }
    }

    @Test
    fun `status widget registration install update click and dispose use public contracts`() {
        val factory = StatusBarWidgetFactory.EP_NAME.extensionList.single { it.id == WIDGET_ID }
        assertInstanceOf(AlarmTimerStatusBarWidgetFactory::class.java, factory)
        assertTrue(factory.isAvailable(project))
        assertTrue(factory.isEnabledByDefault)

        val service = AlarmTimerService.getInstance()
        service.loadState(PersistedState())
        val widget = assertInstanceOf(AlarmTimerStatusBarWidget::class.java, factory.createWidget(project))
        val statusBar = WindowManager.getInstance().getStatusBar(project)
        assertNotNull(statusBar)
        val manager = ToolWindowManager.getInstance(project)
        val clickTarget = manager.getToolWindow(TOOL_WINDOW_ID) ?: manager.registerToolWindow(
            RegisterToolWindowTask.lazyAndClosable(
                TOOL_WINDOW_ID,
                AlarmTimerToolWindowFactory(),
                EmptyIcon.create(16),
            ),
        )
        var widgetDisposed = false
        try {
            val customWidget = assertInstanceOf(CustomStatusBarWidget::class.java, widget)
            assertEquals(WIDGET_ID, widget.ID())
            widget.install(statusBar!!)
            val component = assertInstanceOf(JButton::class.java, customWidget.component)
            service.startTimer(60_000, "platform widget smoke")
            assertTrue(component.text.startsWith("Timer "))
            assertEquals("Open Alarm & Timer", component.toolTipText)
            assertTrue(component.accessibleContext.accessibleName.orEmpty().isNotBlank())
            assertEquals(AccessibleRole.PUSH_BUTTON, component.accessibleContext.accessibleRole)
            assertTrue(component.accessibleContext.accessibleAction.accessibleActionCount > 0)
            assertTrue(component.isVisible)
            assertTrue(component.isFocusable)
            // The counter now records applied updates: refreshes that actually changed the label.
            // A pulse that reproduces the text on screen repaints nothing and counts for nothing.
            assertTrue(widget.updateRequests >= 1, "Expected the structure change to apply an update")

            assertEquals(0, widget.toolWindowShowRequests)
            component.doClick()
            assertEquals(1, widget.toolWindowShowRequests)
            assertSame(clickTarget, manager.getToolWindow(TOOL_WINDOW_ID))

            assertTrue(component.accessibleContext.accessibleAction.doAccessibleAction(0))
            assertEquals(2, widget.toolWindowShowRequests)

            listOf(KeyEvent.VK_ENTER, KeyEvent.VK_SPACE).forEach { keyCode ->
                val actionKey = component.getInputMap(JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(keyCode, 0))
                assertNotNull(actionKey)
                val action = component.actionMap.get(actionKey)
                assertNotNull(action)
                action!!.actionPerformed(ActionEvent(component, ActionEvent.ACTION_PERFORMED, "keyboard"))
            }
            assertEquals(4, widget.toolWindowShowRequests)

            // The IDE's Status Bar Widgets registry is the sole visibility control. The retained
            // legacy field remains readable for state compatibility but no longer hides the widget.
            service.updateSettings(service.settings().copy(showStatusBarWidget = false))
            assertTrue(component.text.startsWith("Timer "))
            assertTrue(component.isVisible)

            factory.disposeWidget(widget)
            widgetDisposed = true
            val updatesAfterDisposal = widget.updateRequests
            val textAfterDisposal = component.text
            service.startTimer(120_000, "disposed widget smoke")
            assertEquals(updatesAfterDisposal, widget.updateRequests)
            assertEquals(textAfterDisposal, component.text)
        } finally {
            if (!widgetDisposed) factory.disposeWidget(widget)
            service.loadState(PersistedState())
        }
    }

    @Test
    fun `lifecycle listener implements both registered platform topics`() {
        val listener = AlarmTimerLifecycleListener()
        assertInstanceOf(AppLifecycleListener::class.java, listener)
        assertInstanceOf(ApplicationActivationListener::class.java, listener)
    }

    @Test
    fun `exit guard reads the real service and never blocks a headless shutdown`() {
        val application = ApplicationManager.getApplication()
        val service = AlarmTimerService.getInstance()
        service.loadState(PersistedState())

        try {
            assertTrue(service.settings().warnOnExitWithPendingItems)
            service.startTimer(60_000, "platform exit guard smoke")
            val remaining = service.nearestActiveRemainingMs()
            assertNotNull(remaining)
            assertTrue(remaining!! in 1..60_000L)
            assertEquals(
                ExitWarningDecision.ALLOW_EXIT,
                ExitWarningProjection.decision(
                    enabled = service.settings().warnOnExitWithPendingItems,
                    headless = true,
                    nearestRemainingMs = remaining,
                ),
            )
            // A fixture has nobody to answer a modal question, so the production hook itself runs
            // only where its own headless branch is guaranteed to answer before one can open.
            if (application.isHeadlessEnvironment) {
                assertTrue(AlarmTimerExitGuard().canExitApplication())
            }
        } finally {
            service.dismissAllAlerts()
            service.loadState(PersistedState())
        }
    }

    @Test
    fun `production notification adapter uses registered group actions and lifecycle`() {
        val groupManager = NotificationGroupManager.getInstance()
        assertTrue(groupManager.isGroupRegistered(NOTIFICATION_GROUP_ID))
        val group = groupManager.getNotificationGroup(NOTIFICATION_GROUP_ID)
        assertEquals(NotificationDisplayType.BALLOON, group.displayType)
        assertTrue(group.isLogByDefault)

        val notifier = PlatformAlertNotifier()
        val item = ScheduledItem(
            id = "platform-notification",
            kind = ItemKind.TIMER,
            label = "Tea & <b>biscuits</b>",
            createdAtEpochMs = 1_000,
            targetEpochMs = 2_000,
            durationMs = 1_000,
        )
        var dismissCalls = 0
        var expirationCalls = 0
        val handles = mutableListOf<NotificationHandle>()

        try {
            val delivered = createPlatformNotification(
                notifier,
                item,
                dismiss = { dismissCalls++ },
                expired = { expirationCalls++ },
            ).also(handles::add)
            val notification = delivered.notification
            assertEquals(NOTIFICATION_GROUP_ID, notification.groupId)
            assertEquals("Timer", notification.title)
            assertEquals("Tea &amp; &lt;b&gt;biscuits&lt;/b&gt;", notification.content)
            assertEquals(listOf("Dismiss"), notification.actions.map { it.templatePresentation.text })

            delivered.deliver()
            assertTrue(activeNotifications().any { it === notification })
            delivered.expire()
            delivered.expire()
            assertTrue(notification.isExpired)
            assertFalse(activeNotifications().any { it === notification })
            assertEquals(1, expirationCalls)

            val dismissNotification = createPlatformNotification(
                notifier,
                item,
                dismiss = { dismissCalls++ },
                expired = { expirationCalls++ },
            ).also(handles::add).notification
            fireNotificationAction(dismissNotification, 0)
            assertEquals(1, dismissCalls)
            assertEquals(2, expirationCalls)
            assertTrue(dismissNotification.isExpired)
        } finally {
            handles.forEach(NotificationHandle::expire)
        }
    }

    private fun withPanel(test: (AlarmTimerService, AlarmTimerPanel) -> Unit) {
        val service = AlarmTimerService.getInstance()
        resetPanelTestService(service)
        val owner = Disposer.newDisposable()
        val panel = AlarmTimerPanel()
        Disposer.register(owner, panel)

        try {
            test(service, panel)
        } finally {
            Disposer.dispose(owner)
            service.dismissAllAlerts()
            service.loadState(PersistedState())
        }
    }

    private fun resetPanelTestService(service: AlarmTimerService) {
        service.dismissAllAlerts()
        service.loadState(PersistedState())
        service.updateSettings(
            service.settings().copy(
                soundEnabled = false,
            ),
        )
    }

    private fun enterAlarm(
        panel: AlarmTimerPanel,
        service: AlarmTimerService,
        target: ZonedDateTime,
        label: String,
    ) {
        namedComponent<JTextField>(panel, AlarmTimerPanelComponentNames.ALARM_TIME).text =
            AlarmTimeText.format(
                time = target.toLocalTime(),
                use24HourTime = service.settings().use24HourTime,
                includeSeconds = true,
            )
        namedComponent<JTextField>(panel, AlarmTimerPanelComponentNames.ALARM_DATE).text =
            target.toLocalDate().toString()
        namedComponent<JTextField>(panel, AlarmTimerPanelComponentNames.ALARM_LABEL).text = label
    }

    private fun safeFutureAlarm(dayOffset: Long, minute: Int): ZonedDateTime = ZonedDateTime.now()
        .plusDays(dayOffset)
        .withHour(12)
        .withMinute(minute)
        .withSecond(45)
        .withNano(0)

    private fun loadHistory(service: AlarmTimerService): List<ScheduledItem> {
        val now = service.now()
        val items = listOf(
            ScheduledItem(
                id = "panel-completed-timer",
                kind = ItemKind.TIMER,
                label = "Completed timer",
                createdAtEpochMs = now - 120_000,
                targetEpochMs = now - 60_000,
                durationMs = 60_000,
                status = ItemStatus.COMPLETED,
                firedAtEpochMs = now - 60_000,
            ),
            ScheduledItem(
                id = "panel-missed-alarm",
                kind = ItemKind.ALARM,
                label = "Missed alarm",
                createdAtEpochMs = now - 180_000,
                targetEpochMs = now - 120_000,
                status = ItemStatus.MISSED,
            ),
        )
        service.loadState(StateCodec.encode(service.settings(), items))
        return items
    }

    private inline fun <reified T : JComponent> namedComponent(root: JComponent, componentName: String): T {
        val matching = componentTree(root)
            .filterIsInstance<T>()
            .filter { it.name == componentName }
        val availableNames = componentTree(root)
            .filterIsInstance<T>()
            .mapNotNull { it.name }
        assertEquals(
            1,
            matching.size,
            "Expected exactly one ${T::class.java.simpleName} named '$componentName'; " +
                "found ${matching.size}. Available names: $availableNames",
        )
        return matching.single()
    }

    private fun componentTree(root: Component): List<Component> {
        val result = mutableListOf<Component>()
        fun visit(component: Component) {
            result += component
            if (component is Container) component.components.forEach(::visit)
        }
        visit(root)
        return result
    }

    private fun layoutRecursively(component: Component) {
        if (component !is Container) return
        component.doLayout()
        component.components.forEach(::layoutRecursively)
    }

    private fun assertPositiveSizeWithinParentAndPanel(button: JButton, panel: AlarmTimerPanel) {
        val parent = requireNotNull(button.parent)
        assertTrue(button.width > 0, "${button.name} must have positive width")
        assertTrue(button.height > 0, "${button.name} must have positive height")
        assertTrue(button.x >= 0 && button.y >= 0, "${button.name} must start inside its parent")
        assertTrue(button.x + button.width <= parent.width, "${button.name} exceeds its parent width")
        assertTrue(button.y + button.height <= parent.height, "${button.name} exceeds its parent height")
        val panelBounds = SwingUtilities.convertRectangle(parent, button.bounds, panel)
        assertTrue(panelBounds.x >= 0, "${button.name} starts left of the compact panel")
        assertTrue(
            panelBounds.x + panelBounds.width <= COMPACT_PANEL_WIDTH,
            "${button.name} exceeds the compact panel width",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun itemList(panel: AlarmTimerPanel, componentName: String): JList<ScheduledItem> =
        namedComponent<JList<*>>(panel, componentName) as JList<ScheduledItem>

    private fun selectItem(list: JList<ScheduledItem>, item: ScheduledItem) {
        list.setSelectedValue(item, true)
        assertEquals(item.id, list.selectedValue?.id, "Could not select ${item.id} through the named list")
    }

    private fun listItems(list: JList<ScheduledItem>): List<ScheduledItem> =
        (0 until list.model.size).map(list.model::getElementAt)

    private fun assertControlsDisabled(controls: List<JButton>) {
        controls.forEach { assertFalse(it.isEnabled, "${it.name} should be disabled") }
    }

    private fun assertRegisteredActions(service: AlarmTimerService) {
        val actionManager = ActionManager.getInstance()
        val actions = mapOf(
            "AlarmTimer.Open" to OpenToolWindowAction::class.java,
            "AlarmTimer.SetAlarm" to SetAlarmAction::class.java,
            "AlarmTimer.StartTimer" to StartTimerAction::class.java,
            "AlarmTimer.ToggleSound" to ToggleSoundAction::class.java,
            "AlarmTimer.DismissAll" to DismissAllAlertsAction::class.java,
            "AlarmTimer.ClearCompleted" to ClearCompletedAction::class.java,
            "AlarmTimer.OpenSettings" to OpenSettingsAction::class.java,
            "AlarmTimer.OpenAlarmWebsite" to OpenAlarmWebsiteAction::class.java,
            "AlarmTimer.OpenTimerWebsite" to OpenTimerWebsiteAction::class.java,
            "AlarmTimer.OpenDocumentation" to OpenDocumentationAction::class.java,
        ).mapValues { (id, expectedClass) ->
            assertInstanceOf(expectedClass, actionManager.getAction(id)).also { action ->
                assertInstanceOf(DumbAware::class.java, action, id)
            }
        }

        listOf(
            "AlarmTimer.Open",
            "AlarmTimer.SetAlarm",
            "AlarmTimer.StartTimer",
            "AlarmTimer.DismissAll",
            "AlarmTimer.ClearCompleted",
        ).forEach { id ->
            assertEquals(ActionUpdateThread.BGT, actions.getValue(id).actionUpdateThread, id)
        }

        val toggleSound = assertInstanceOf(ToggleSoundAction::class.java, actions.getValue("AlarmTimer.ToggleSound"))
        val initialSoundEnabled = service.settings().soundEnabled
        assertEquals("Alert Sound", toggleSound.templatePresentation.text)
        assertEquals(initialSoundEnabled, toggleSound.selected(service))
        toggleSound.setSelected(service, !initialSoundEnabled)
        assertEquals(!initialSoundEnabled, toggleSound.selected(service))
        toggleSound.setSelected(service, initialSoundEnabled)
        assertEquals(initialSoundEnabled, service.settings().soundEnabled)
        assertEquals(ActionUpdateThread.BGT, toggleSound.actionUpdateThread)
    }

    private fun assertConfigurableLifecycle() {
        val extension = Configurable.APPLICATION_CONFIGURABLE.extensionList.single {
            it.id == "com.onlinealarmkur.jetbrains.settings"
        }
        assertEquals("tools", extension.parentId)
        val configurable = assertInstanceOf(
            AlarmTimerConfigurable::class.java,
            extension.createConfigurable(),
        )

        val service = AlarmTimerService.getInstance()
        val originalSettings = service.settings()
        try {
            val root = requireNotNull(configurable.createComponent())
            configurable.reset()
            assertFalse(configurable.isModified)

            val defaultTimer = namedComponent<JTextField>(
                root,
                AlarmTimerSettingsComponentNames.DEFAULT_TIMER,
            )
            val grace = namedComponent<JTextField>(
                root,
                AlarmTimerSettingsComponentNames.OVERDUE_GRACE,
            )
            val sound = namedComponent<JBCheckBox>(
                root,
                AlarmTimerSettingsComponentNames.SOUND_ENABLED,
            )
            val warnOnExit = namedComponent<JBCheckBox>(
                root,
                AlarmTimerSettingsComponentNames.WARN_ON_EXIT,
            )
            val volume = namedComponent<JBSlider>(
                root,
                AlarmTimerSettingsComponentNames.VOLUME,
            )
            val volumeValue = namedComponent<JLabel>(
                root,
                AlarmTimerSettingsComponentNames.VOLUME_VALUE,
            )
            val testSound = namedComponent<JButton>(
                root,
                AlarmTimerSettingsComponentNames.TEST_SOUND,
            )

            assertSame(defaultTimer, configurable.preferredFocusedComponent)
            assertTrue(sound.isSelected)
            assertTrue(volume.isEnabled)
            assertTrue(testSound.isEnabled)
            assertEquals("${volume.value}%", volumeValue.text)

            sound.doClick()
            assertFalse(volume.isEnabled)
            assertFalse(volumeValue.isEnabled)
            assertFalse(testSound.isEnabled)
            sound.doClick()
            assertTrue(volume.isEnabled)
            assertTrue(testSound.isEnabled)

            defaultTimer.text = "later"
            assertThrows(ConfigurationException::class.java, configurable::apply)
            assertTrue(configurable.isModified)

            defaultTimer.text = "2m"
            grace.text = "10m"
            volume.value = 25
            configurable.apply()
            assertEquals(120_000, service.settings().defaultTimerMs)
            assertEquals(600_000, service.settings().overdueGracePeriodMs)
            assertEquals(25, service.settings().volumePercent)
            assertEquals("25%", volumeValue.text)

            assertTrue(warnOnExit.isSelected)
            warnOnExit.doClick()
            configurable.apply()
            assertFalse(service.settings().warnOnExitWithPendingItems)

            val labels = componentTree(root).filterIsInstance<JLabel>().mapNotNull { it.text }
            assertTrue(AlarmTimerVersionMetadata.ABOUT_LABEL in labels)
            listOf(
                AlarmTimerSettingsComponentNames.ONLINE_ALARM_KUR,
                AlarmTimerSettingsComponentNames.DOCUMENTATION,
                AlarmTimerSettingsComponentNames.ISSUES,
            ).forEach { name ->
                assertFalse(namedComponent<JComponent>(root, name).accessibleContext.accessibleName.isNullOrBlank())
            }
        } finally {
            service.updateSettings(originalSettings)
            configurable.disposeUIResources()
        }
    }

    private fun assertStateRoundTrip(service: AlarmTimerService) {
        service.loadState(PersistedState())
        val expectedSettings = AlarmTimerSettings(
            defaultTimerMs = 90_000,
            soundEnabled = false,
            volumePercent = 25,
        )
        service.updateSettings(expectedSettings)
        val timer = service.startTimer(60_000, "platform smoke")

        val encoded = service.state
        service.loadState(PersistedState())
        assertTrue(service.items().isEmpty())
        service.loadState(encoded)

        assertEquals(expectedSettings, service.settings())
        val restoredTimer = service.items().single()
        assertEquals(timer.id, restoredTimer.id)
        assertEquals(timer.kind, restoredTimer.kind)
        assertEquals(timer.label, restoredTimer.label)
        assertEquals(timer.createdAtEpochMs, restoredTimer.createdAtEpochMs)
        assertEquals(timer.durationMs, restoredTimer.durationMs)
        assertEquals(timer.status, restoredTimer.status)
        assertEquals(timer.remainingMs, restoredTimer.remainingMs)
        assertEquals(timer.firedAtEpochMs, restoredTimer.firedAtEpochMs)
        assertEquals(timer.deliveryPending, restoredTimer.deliveryPending)
        assertTrue(service.remainingMs(restoredTimer.id) in 1..restoredTimer.durationMs)
    }

    private fun createPlatformNotification(
        notifier: PlatformAlertNotifier,
        item: ScheduledItem,
        dismiss: () -> Unit,
        expired: () -> Unit,
    ): NotificationHandle = assertInstanceOf(
        NotificationHandle::class.java,
        notifier.create(item, dismiss, expired),
    )

    private fun activeNotifications(): Array<Notification> = NotificationsManager.getNotificationsManager()
        .getNotificationsOfType(Notification::class.java, null)

    private fun fireNotificationAction(notification: Notification, actionIndex: Int) {
        val dataContext = DataContext { dataId ->
            notification.takeIf { Notification.KEY.`is`(dataId) }
        }
        Notification.fire(notification, notification.actions[actionIndex], dataContext)
    }
}

private const val TOOL_WINDOW_ID = "Alarm & Timer"
private const val WIDGET_ID = "AlarmTimer.NextItem"
private const val NOTIFICATION_GROUP_ID = "Alarm & Timer alerts"
private const val CONTROL_TIME_TOLERANCE_MS = 5_000L
private const val COMPACT_PANEL_WIDTH = 280

private val TIMER_PRESET_COMPONENTS = listOf(
    1L to AlarmTimerPanelComponentNames.TIMER_PRESET_1_MINUTE,
    5L to AlarmTimerPanelComponentNames.TIMER_PRESET_5_MINUTES,
    10L to AlarmTimerPanelComponentNames.TIMER_PRESET_10_MINUTES,
    15L to AlarmTimerPanelComponentNames.TIMER_PRESET_15_MINUTES,
    30L to AlarmTimerPanelComponentNames.TIMER_PRESET_30_MINUTES,
    60L to AlarmTimerPanelComponentNames.TIMER_PRESET_60_MINUTES,
)

private val PANEL_COMPONENT_NAMES = listOf(
    AlarmTimerPanelComponentNames.IDE_RUNNING_LIMIT,
    AlarmTimerPanelComponentNames.ALARM_TIME,
    AlarmTimerPanelComponentNames.ALARM_DATE,
    AlarmTimerPanelComponentNames.ALARM_LABEL,
    AlarmTimerPanelComponentNames.ALARM_SUBMIT,
    AlarmTimerPanelComponentNames.ALARM_CANCEL_EDIT,
    AlarmTimerPanelComponentNames.TIMER_DURATION,
    AlarmTimerPanelComponentNames.TIMER_LABEL,
    AlarmTimerPanelComponentNames.TIMER_START,
    AlarmTimerPanelComponentNames.TIMER_PRESET_1_MINUTE,
    AlarmTimerPanelComponentNames.TIMER_PRESET_5_MINUTES,
    AlarmTimerPanelComponentNames.TIMER_PRESET_10_MINUTES,
    AlarmTimerPanelComponentNames.TIMER_PRESET_15_MINUTES,
    AlarmTimerPanelComponentNames.TIMER_PRESET_30_MINUTES,
    AlarmTimerPanelComponentNames.TIMER_PRESET_60_MINUTES,
    AlarmTimerPanelComponentNames.ACTIVE_LIST,
    AlarmTimerPanelComponentNames.ACTIVE_PAUSE_RESUME,
    AlarmTimerPanelComponentNames.ACTIVE_RESTART,
    AlarmTimerPanelComponentNames.ACTIVE_EDIT_ALARM,
    AlarmTimerPanelComponentNames.ACTIVE_CANCEL,
    AlarmTimerPanelComponentNames.HISTORY_LIST,
    AlarmTimerPanelComponentNames.HISTORY_REMOVE,
    AlarmTimerPanelComponentNames.HISTORY_CLEAR_ALL,
)
