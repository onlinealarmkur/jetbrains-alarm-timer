package com.onlinealarmkur.jetbrains.actions

import com.intellij.openapi.actionSystem.Presentation
import com.onlinealarmkur.jetbrains.domain.ItemKind
import com.onlinealarmkur.jetbrains.domain.ItemStatus
import com.onlinealarmkur.jetbrains.domain.ScheduledItem
import com.onlinealarmkur.jetbrains.ui.AlarmTimerDestination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlarmTimerActionRoutingTest {
    @Test
    fun `fresh tool window content is created before contextual action routing`() {
        actions().forEach { (action, expectedDestination) ->
            var panel: RecordingPanel? = null
            var showCount = 0

            action.route(
                showToolWindow = { onShown ->
                    showCount++
                    panel = RecordingPanel(AlarmTimerDestination.ALARM)
                    onShown()
                },
                focusDestination = { checkNotNull(panel).focus(it) },
            )

            val expectedTab = expectedDestination ?: AlarmTimerDestination.ALARM
            assertEquals(1, showCount, action.javaClass.simpleName)
            assertEquals(expectedTab, panel?.destination, action.javaClass.simpleName)
            assertEquals(if (expectedDestination == null) 0 else 1, panel?.focusCount, action.javaClass.simpleName)
        }
    }

    @Test
    fun `already-open content is routed contextually while generic open preserves tab`() {
        actions().forEach { (action, expectedDestination) ->
            val panel = RecordingPanel(AlarmTimerDestination.TIMER)
            var showCount = 0

            action.route(
                showToolWindow = { onShown ->
                    showCount++
                    onShown()
                },
                focusDestination = panel::focus,
            )

            val expectedTab = expectedDestination ?: AlarmTimerDestination.TIMER
            assertEquals(1, showCount, action.javaClass.simpleName)
            assertEquals(expectedTab, panel.destination, action.javaClass.simpleName)
            assertEquals(if (expectedDestination == null) 0 else 1, panel.focusCount, action.javaClass.simpleName)
        }
    }

    @Test
    fun `clearing completed items stays disabled until the history holds something to delete`() {
        val action = ClearCompletedAction()

        assertEquals(0, action.clearableCount(emptyList()))
        assertEquals(0, action.clearableCount(listOf(item(ItemStatus.ACTIVE), item(ItemStatus.PAUSED))))
        assertEquals(1, action.clearableCount(listOf(item(ItemStatus.ACTIVE), item(ItemStatus.COMPLETED))))
        assertEquals(
            2,
            action.clearableCount(
                listOf(item(ItemStatus.COMPLETED), item(ItemStatus.ACTIVE), item(ItemStatus.MISSED)),
            ),
        )
    }

    @Test
    fun `dismiss all presentation follows visible alert availability`() {
        var hasActiveAlerts = false
        val presentation = Presentation()
        val action = DismissAllAlertsAction(
            hasActiveAlerts = { hasActiveAlerts },
            dismissAllAlerts = {},
        )

        action.updatePresentation(presentation)
        assertFalse(presentation.isEnabled)

        hasActiveAlerts = true
        action.updatePresentation(presentation)
        assertTrue(presentation.isEnabled)

        hasActiveAlerts = false
        action.updatePresentation(presentation)
        assertFalse(presentation.isEnabled)
    }

    private fun item(status: ItemStatus) = ScheduledItem(
        id = "item-$status",
        kind = ItemKind.TIMER,
        label = "",
        createdAtEpochMs = 0,
        targetEpochMs = 1_000,
        status = status,
    )

    private fun actions() = listOf(
        OpenToolWindowAction() to null,
        SetAlarmAction() to AlarmTimerDestination.ALARM,
        StartTimerAction() to AlarmTimerDestination.TIMER,
    )

    private class RecordingPanel(var destination: AlarmTimerDestination) {
        var focusCount = 0

        fun focus(next: AlarmTimerDestination) {
            destination = next
            focusCount++
        }
    }
}
