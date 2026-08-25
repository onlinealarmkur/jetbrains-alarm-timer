package com.onlinealarmkur.jetbrains.ui

import com.onlinealarmkur.jetbrains.domain.ItemKind
import com.onlinealarmkur.jetbrains.domain.ItemStatus
import com.onlinealarmkur.jetbrains.domain.ScheduledItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

class AlarmTimerProjectionTest {
    @Test
    fun `active item control availability covers every selection state`() {
        val none = availability()
        val timer = availability(pauseOrResume = true, restart = true, cancel = true)
        val alarm = availability(editAlarm = true, cancel = true)
        val cases = listOf(
            null to none,
            item(ItemKind.ALARM, ItemStatus.ACTIVE) to alarm,
            item(ItemKind.ALARM, ItemStatus.PAUSED) to none,
            item(ItemKind.ALARM, ItemStatus.COMPLETED) to none,
            item(ItemKind.ALARM, ItemStatus.MISSED) to none,
            item(ItemKind.TIMER, ItemStatus.ACTIVE) to timer,
            item(ItemKind.TIMER, ItemStatus.PAUSED) to timer,
            item(ItemKind.TIMER, ItemStatus.COMPLETED) to none,
            item(ItemKind.TIMER, ItemStatus.MISSED) to none,
        )

        cases.forEach { (selected, expected) ->
            assertEquals(
                expected,
                ActiveItemControlAvailabilityProjection.forSelection(selected),
                "Unexpected controls for ${selected?.kind}/${selected?.status}",
            )
        }
    }

    @Test
    fun `unchanged projection preserves model and selected item without list events`() {
        val activeModel = DefaultListModel<ScheduledItem>()
        val historyModel = DefaultListModel<ScheduledItem>()
        val projection = AlarmTimerListProjection(activeModel, historyModel)
        val items = listOf(timer("first", "First"), timer("second", "Second"))
        projection.reconcile(items)
        val list = JList(activeModel)
        list.selectedIndex = 1
        val events = modelEvents(activeModel)

        projection.reconcile(items)

        assertSame(activeModel, list.model)
        assertEquals("second", list.selectedValue.id)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `same-id update changes row in place without clear and re-add`() {
        val activeModel = DefaultListModel<ScheduledItem>()
        val historyModel = DefaultListModel<ScheduledItem>()
        val projection = AlarmTimerListProjection(activeModel, historyModel)
        projection.reconcile(listOf(timer("first", "First"), timer("second", "Second")))
        val list = JList(activeModel)
        list.selectedIndex = 1
        val events = modelEvents(activeModel)

        projection.reconcile(listOf(timer("first", "Updated"), timer("second", "Second")))

        assertEquals(listOf("changed"), events)
        assertEquals("Updated", activeModel.getElementAt(0).label)
        assertEquals("second", list.selectedValue.id)
    }

    @Test
    fun `active status projection advances countdown text`() {
        val item = timer("active", "Tea", targetEpochMs = 65_000)

        assertEquals(
            "Timer 01:05",
            AlarmTimerStatusProjection.text { item to 65_000L },
        )
        assertEquals(
            "Timer 01:04",
            AlarmTimerStatusProjection.text { item to 64_000L },
        )
    }

    @Test
    fun `active status projection aligns far and near text with polling precision`() {
        val item = timer("active", "Tea")

        assertEquals(
            "Timer 168h 00m",
            AlarmTimerStatusProjection.text { item to 7 * 24 * 60 * 60_000L },
        )
        assertEquals(
            "Timer 1h 01m",
            AlarmTimerStatusProjection.text { item to 3_600_001L },
        )
        assertEquals(
            "Timer 1:00:00",
            AlarmTimerStatusProjection.text { item to 3_600_000L },
        )
    }

    @Test
    fun `panel requests pulses only while visible with an active timer`() {
        val alarm = item(ItemKind.ALARM, ItemStatus.ACTIVE)
        val activeTimer = item(ItemKind.TIMER, ItemStatus.ACTIVE)
        val pausedTimer = item(ItemKind.TIMER, ItemStatus.PAUSED)

        assertFalse(AlarmTimerPanelPulseInterest.wantsClockPulses(false, listOf(activeTimer)))
        assertFalse(AlarmTimerPanelPulseInterest.wantsClockPulses(true, listOf(alarm)))
        assertFalse(AlarmTimerPanelPulseInterest.wantsClockPulses(true, listOf(pausedTimer)))
        assertTrue(AlarmTimerPanelPulseInterest.wantsClockPulses(true, listOf(alarm, activeTimer)))
    }

    @Test
    fun `status projection renders the kind and remaining time of the reported nearest item`() {
        // Choosing the nearest item now belongs to the engine, which reads both time sources under
        // one lock; see AlarmEngineTest. The projection only renders what it is handed.
        val alarm = item(ItemKind.ALARM, ItemStatus.ACTIVE)
        val nearestTimer = timer("elapsed-first", "Elapsed first")

        assertEquals(
            "Alarm in 01:05",
            AlarmTimerStatusProjection.text { alarm to 65_000L },
        )
        assertEquals(
            "Timer 00:05",
            AlarmTimerStatusProjection.text { nearestTimer to 5_000L },
        )
        assertEquals(
            "",
            AlarmTimerStatusProjection.text { null },
        )
    }

    private fun timer(id: String, label: String, targetEpochMs: Long = 60_000) = ScheduledItem(
        id = id,
        kind = ItemKind.TIMER,
        label = label,
        createdAtEpochMs = 0,
        targetEpochMs = targetEpochMs,
        durationMs = targetEpochMs,
    )

    private fun item(kind: ItemKind, status: ItemStatus) = ScheduledItem(
        id = "$kind-$status",
        kind = kind,
        label = "",
        createdAtEpochMs = 1_000,
        targetEpochMs = 2_000,
        durationMs = if (kind == ItemKind.TIMER) 1_000 else 0,
        remainingMs = if (status == ItemStatus.PAUSED) 1_000 else 0,
        status = status,
    )

    private fun availability(
        pauseOrResume: Boolean = false,
        restart: Boolean = false,
        editAlarm: Boolean = false,
        cancel: Boolean = false,
    ) = ActiveItemControlAvailability(pauseOrResume, restart, editAlarm, cancel)

    private fun modelEvents(model: DefaultListModel<ScheduledItem>): MutableList<String> {
        val events = mutableListOf<String>()
        model.addListDataListener(object : ListDataListener {
            override fun intervalAdded(event: ListDataEvent) {
                events += "added"
            }

            override fun intervalRemoved(event: ListDataEvent) {
                events += "removed"
            }

            override fun contentsChanged(event: ListDataEvent) {
                events += "changed"
            }
        })
        return events
    }
}
