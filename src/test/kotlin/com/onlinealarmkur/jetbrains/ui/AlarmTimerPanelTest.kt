package com.onlinealarmkur.jetbrains.ui

import com.intellij.openapi.progress.ProcessCanceledException
import com.onlinealarmkur.jetbrains.AlarmTimerBundle
import com.onlinealarmkur.jetbrains.domain.ItemKind
import com.onlinealarmkur.jetbrains.domain.ItemStatus
import com.onlinealarmkur.jetbrains.domain.ScheduledItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.concurrent.CancellationException
import java.util.Locale
import javax.swing.JButton

class AlarmTimerPanelTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneId.of("UTC"))

    @Test
    fun `translated control grids do not clip labels at compact width`() {
        val locales = listOf(
            Locale.ENGLISH,
            Locale.GERMAN,
            Locale.forLanguageTag("es"),
            Locale.FRENCH,
            Locale.JAPANESE,
            Locale.KOREAN,
            Locale.forLanguageTag("ru"),
            Locale.forLanguageTag("tr"),
            Locale.forLanguageTag("pt-BR"),
            Locale.SIMPLIFIED_CHINESE,
        )
        val groups = listOf(
            listOf("panel.pause.resume", "panel.restart", "panel.edit.alarm", "panel.cancel"),
            listOf("panel.remove", "panel.clear.all"),
        )

        locales.forEach { locale ->
            groups.forEach { keys ->
                val grid = ResponsiveButtonGrid(maximumColumns = 2, horizontalGap = 4, verticalGap = 4)
                keys.map { key -> JButton(AlarmTimerBundle.messageFor(locale, key)).also(grid::add) }
                grid.setSize(COMPACT_CONTROL_WIDTH, 1)
                grid.setSize(COMPACT_CONTROL_WIDTH, grid.preferredSize.height)
                grid.doLayout()

                assertTrue(grid.columnCount in 1..2, "$locale ${grid.columnCount}")
                grid.components.filterIsInstance<JButton>().forEach { button ->
                    assertTrue(
                        button.width >= button.preferredSize.width,
                        "$locale clipped '${button.text}' (${button.width} < ${button.preferredSize.width})",
                    )
                }
            }
        }
    }

    @Test
    fun `row presentation separates kind title from history detail`() {
        val alarm = item(ItemKind.ALARM, ItemStatus.COMPLETED)
        val timer = item(ItemKind.TIMER, ItemStatus.COMPLETED)
        val missedAlarm = item(ItemKind.ALARM, ItemStatus.MISSED)
        val missedTimer = item(ItemKind.TIMER, ItemStatus.MISSED)

        assertEquals(ScheduledItemPresentation("Alarm", "Triggered"), ScheduledItemPresentationFactory.present(alarm, true, 0))
        assertEquals(ScheduledItemPresentation("Timer", "Finished"), ScheduledItemPresentationFactory.present(timer, true, 0))
        assertEquals(ScheduledItemPresentation("Alarm", "Missed · no alert sent"), ScheduledItemPresentationFactory.present(missedAlarm, true, 0))
        assertEquals(ScheduledItemPresentation("Timer", "Missed · no alert sent"), ScheduledItemPresentationFactory.present(missedTimer, true, 0))
    }

    @Test
    fun `row presentation preserves labels and never composes another kind noun into details`() {
        val presentation = ScheduledItemPresentationFactory.present(item(ItemKind.ALARM, ItemStatus.COMPLETED, "Focus"), true, 0)
        assertEquals("Focus", presentation.name)
        assertEquals("Triggered", presentation.detail)
        assertFalse(presentation.detail.contains("Alarm"))
    }

    @Test
    fun `production presentation covers active paused history and Russian paused status`() {
        val activeAlarm = ScheduledItemPresentationFactory.present(item(ItemKind.ALARM, ItemStatus.ACTIVE), true, 0)
        val activeTimer = ScheduledItemPresentationFactory.present(item(ItemKind.TIMER, ItemStatus.ACTIVE), true, 65_000)
        val pausedTimer = ScheduledItemPresentationFactory.present(item(ItemKind.TIMER, ItemStatus.PAUSED).copy(remainingMs = 65_000), true, 0)
        val russianPaused = ScheduledItemPresentationFactory.present(item(ItemKind.TIMER, ItemStatus.PAUSED).copy(remainingMs = 65_000), true, 0, Locale.forLanguageTag("ru-RU"))

        assertEquals("Alarm", activeAlarm.name)
        assertEquals("Timer", activeTimer.name)
        assertEquals("01:05", activeTimer.detail)
        assertEquals("Paused · 01:05", pausedTimer.detail)
        assertEquals("Приостановлен · 01:05", russianPaused.detail)
        listOf(activeAlarm, activeTimer, pausedTimer, russianPaused).forEach { assertFalse(it.detail.contains("Alarm") || it.detail.contains("Timer")) }
    }

    @Test
    fun `alarm destination selects alarm tab and focuses time`() {
        var selectedTab = -1
        var alarmFocusCount = 0
        var timerFocusCount = 0
        val focus = AlarmTimerDestinationFocus(
            selectTab = { selectedTab = it },
            focusAlarmTime = { alarmFocusCount++ },
            focusTimerDuration = { timerFocusCount++ },
        )

        focus.focus(AlarmTimerDestination.ALARM)

        assertEquals(0, selectedTab)
        assertEquals(1, alarmFocusCount)
        assertEquals(0, timerFocusCount)
    }

    private fun item(kind: ItemKind, status: ItemStatus, label: String = "") = ScheduledItem(
        id = "item-$kind-$status",
        kind = kind,
        label = label,
        createdAtEpochMs = 0,
        targetEpochMs = 1_000,
        status = status,
    )

    private fun editableAlarm(id: String = "alarm", label: String = "Original") =
        item(ItemKind.ALARM, ItemStatus.ACTIVE, label).copy(id = id)

    @Test
    fun `timer destination selects timer tab and focuses duration`() {
        var selectedTab = -1
        var alarmFocusCount = 0
        var timerFocusCount = 0
        val focus = AlarmTimerDestinationFocus(
            selectTab = { selectedTab = it },
            focusAlarmTime = { alarmFocusCount++ },
            focusTimerDuration = { timerFocusCount++ },
        )

        focus.focus(AlarmTimerDestination.TIMER)

        assertEquals(1, selectedTab)
        assertEquals(0, alarmFocusCount)
        assertEquals(1, timerFocusCount)
    }

    @Test
    fun `timer field initializes from a non-five-minute default`() {
        val state = TimerDurationFieldState(90_000)

        assertEquals("1m 30s", state.initialText)
    }

    @Test
    fun `timer field defensively initializes from persisted sub-second remainder`() {
        val state = TimerDurationFieldState(1_500)

        assertEquals("2s", state.initialText)
    }

    @Test
    fun `settings update replaces untouched previous default`() {
        val state = TimerDurationFieldState(90_000)

        val updated = state.updateDefault(2 * 60_000, state.initialText)

        assertEquals("2m", updated)
    }

    @Test
    fun `settings update preserves user-edited duration`() {
        val state = TimerDurationFieldState(90_000)

        val updated = state.updateDefault(2 * 60_000, "45s")

        assertEquals("45s", updated)
    }

    @Test
    fun `time format update reformats untouched generated alarm text`() {
        val state = AlarmTimeFieldState(LocalTime.of(13, 5), initialUse24HourTime = true)

        val updated = state.updateFormat(newUse24HourTime = false, currentText = state.initialText)

        assertEquals("1:05 PM", updated.text)
        assertTrue(updated.formatChanged)
    }

    @Test
    fun `time format update preserves user-edited alarm text`() {
        val state = AlarmTimeFieldState(LocalTime.of(13, 5), initialUse24HourTime = true)

        val updated = state.updateFormat(newUse24HourTime = false, currentText = "14:30")

        assertEquals("14:30", updated.text)
        assertTrue(updated.formatChanged)
    }

    @Test
    fun `unchanged time format does not request alarm form rebuild`() {
        val state = AlarmTimeFieldState(LocalTime.of(13, 5), initialUse24HourTime = true)

        val updated = state.updateFormat(newUse24HourTime = true, currentText = state.initialText)

        assertEquals("13:05", updated.text)
        assertFalse(updated.formatChanged)
    }

    @Test
    fun `programmatic edit text participates in later format updates`() {
        val state = AlarmTimeFieldState(LocalTime.of(13, 5), initialUse24HourTime = true)
        val editText = state.programmaticText(
            time = LocalTime.of(7, 8, 9),
            use24HourTime = true,
            includeSeconds = true,
        )

        val updated = state.updateFormat(newUse24HourTime = false, currentText = editText)

        assertEquals("7:08:09 AM", updated.text)
        assertTrue(updated.formatChanged)
    }

    @Test
    fun `suggested alarm time refreshes while the field still holds the generated text`() {
        val state = AlarmTimeFieldState(LocalTime.of(9, 5), initialUse24HourTime = true)

        val regenerated = state.regenerate(
            time = LocalTime.of(17, 5),
            currentText = state.initialText,
            fieldFocused = false,
            isEditing = false,
        )

        assertEquals("17:05", regenerated)
        assertEquals("17:05", state.initialText)
    }

    @Test
    fun `suggested alarm time never overwrites user-typed text`() {
        val state = AlarmTimeFieldState(LocalTime.of(9, 5), initialUse24HourTime = true)

        val regenerated = state.regenerate(
            time = LocalTime.of(17, 5),
            currentText = "14:30",
            fieldFocused = false,
            isEditing = false,
        )

        assertNull(regenerated)
        assertEquals("09:05", state.initialText)
    }

    @Test
    fun `suggested alarm time never overwrites a focused or edited field`() {
        val state = AlarmTimeFieldState(LocalTime.of(9, 5), initialUse24HourTime = true)

        assertNull(
            state.regenerate(
                time = LocalTime.of(17, 5),
                currentText = state.initialText,
                fieldFocused = true,
                isEditing = false,
            ),
        )
        assertNull(
            state.regenerate(
                time = LocalTime.of(17, 5),
                currentText = state.initialText,
                fieldFocused = false,
                isEditing = true,
            ),
        )
        assertEquals("09:05", state.initialText)
    }

    @Test
    fun `ending an alarm edit stops generating seconds`() {
        val state = AlarmTimeFieldState(LocalTime.of(9, 5), initialUse24HourTime = true)
        val editText = state.programmaticText(
            time = LocalTime.of(7, 8, 9),
            use24HourTime = true,
            includeSeconds = true,
        )
        assertEquals("07:08:09", editText)

        state.clearSecondsPreference()
        val updated = state.updateFormat(newUse24HourTime = false, currentText = editText)

        assertEquals("7:08 AM", updated.text)
        assertTrue(updated.formatChanged)
    }

    @Test
    fun `ending an alarm edit leaves the field open to a seconds-free suggestion`() {
        val state = AlarmTimeFieldState(LocalTime.of(9, 5), initialUse24HourTime = true)
        val editText = state.programmaticText(
            time = LocalTime.of(7, 8, 9),
            use24HourTime = true,
            includeSeconds = true,
        )

        state.clearSecondsPreference()

        assertEquals(
            "17:05",
            state.regenerate(
                time = LocalTime.of(17, 5),
                currentText = editText,
                fieldFocused = false,
                isEditing = false,
            ),
        )
    }

    @Test
    fun `time format update reformats a refreshed suggestion`() {
        val state = AlarmTimeFieldState(LocalTime.of(9, 5), initialUse24HourTime = true)
        val regenerated = requireNotNull(
            state.regenerate(
                time = LocalTime.of(17, 5),
                currentText = state.initialText,
                fieldFocused = false,
                isEditing = false,
            ),
        )

        val updated = state.updateFormat(newUse24HourTime = false, currentText = regenerated)

        assertEquals("5:05 PM", updated.text)
        assertTrue(updated.formatChanged)
    }

    @Test
    fun `alarm submission creates with target calculated from fixed clock`() {
        val state = AlarmSubmissionState()
        val created = mutableListOf<Pair<Long, String>>()

        val result = state.submit(
            timeText = "13:00",
            dateText = "",
            label = "Stand-up",
            clock = clock,
            create = { target, label -> created += target to label },
            edit = { _, _, _ -> error("Edit must not be called in create mode.") },
        )

        assertEquals(AlarmSubmissionResult.CREATED, result)
        assertEquals(listOf(Instant.parse("2026-07-15T13:00:00Z").toEpochMilli() to "Stand-up"), created)
        assertFalse(state.isEditing)
    }

    @Test
    fun `12-hour alarm submission creates the same target as 24-hour text`() {
        val twelveHourTargets = mutableListOf<Long>()
        val twentyFourHourTargets = mutableListOf<Long>()

        AlarmSubmissionState().submit(
            timeText = "1:00 PM",
            dateText = "",
            label = "Twelve hour",
            clock = clock,
            create = { target, _ -> twelveHourTargets += target },
            edit = { _, _, _ -> error("Edit must not be called in create mode.") },
        )
        AlarmSubmissionState().submit(
            timeText = "13:00",
            dateText = "",
            label = "Twenty-four hour",
            clock = clock,
            create = { target, _ -> twentyFourHourTargets += target },
            edit = { _, _, _ -> error("Edit must not be called in create mode.") },
        )

        assertEquals(twentyFourHourTargets, twelveHourTargets)
        assertEquals(listOf(Instant.parse("2026-07-15T13:00:00Z").toEpochMilli()), twelveHourTargets)
    }

    @Test
    fun `successful alarm edit routes id and clears edit mode`() {
        val state = AlarmSubmissionState()
        val edited = mutableListOf<Triple<String, Long, String>>()
        state.beginEdit(editableAlarm())

        val result = state.submit(
            timeText = "13:00",
            dateText = "2026-07-15",
            label = "Review",
            clock = clock,
            create = { _, _ -> error("Create must not be called in edit mode.") },
            edit = { alarm, target, label -> edited += Triple(alarm.id, target, label); true },
        )

        assertEquals(AlarmSubmissionResult.EDITED, result)
        assertEquals(
            listOf(Triple("alarm", Instant.parse("2026-07-15T13:00:00Z").toEpochMilli(), "Review")),
            edited,
        )
        assertFalse(state.isEditing)
    }

    @Test
    fun `12-hour alarm edit routes the same target as 24-hour text`() {
        fun editedTarget(timeText: String): Long {
            val state = AlarmSubmissionState().apply { beginEdit(editableAlarm()) }
            var target: Long? = null
            val result = state.submit(
                timeText = timeText,
                dateText = "2026-07-16",
                label = "Review",
                clock = clock,
                create = { _, _ -> error("Create must not be called in edit mode.") },
                edit = { alarm, editedTarget, _ ->
                    assertEquals("alarm", alarm.id)
                    target = editedTarget
                    true
                },
            )
            assertEquals(AlarmSubmissionResult.EDITED, result)
            assertFalse(state.isEditing)
            return requireNotNull(target)
        }

        val twelveHourTarget = editedTarget("12:30:45 am")
        val twentyFourHourTarget = editedTarget("00:30:45")

        assertEquals(twentyFourHourTarget, twelveHourTarget)
        assertEquals(Instant.parse("2026-07-16T00:30:45Z").toEpochMilli(), twelveHourTarget)
    }

    @Test
    fun `cancel edit makes next submission create without editing`() {
        val state = AlarmSubmissionState()
        var createCount = 0
        state.beginEdit(editableAlarm())

        state.cancelEdit()
        val result = state.submit(
            timeText = "13:00",
            dateText = "",
            label = "New",
            clock = clock,
            create = { _, _ -> createCount++ },
            edit = { _, _, _ -> error("Edit must not be called after cancel.") },
        )

        assertEquals(AlarmSubmissionResult.CREATED, result)
        assertEquals(1, createCount)
        assertFalse(state.isEditing)
    }

    @Test
    fun `parse failure preserves alarm edit mode`() {
        val state = AlarmSubmissionState()
        state.beginEdit(editableAlarm())

        assertThrows(DateTimeParseException::class.java) {
            state.submit(
                timeText = "not-a-time",
                dateText = "",
                label = "Review",
                clock = clock,
                create = { _, _ -> error("Create must not be called for invalid input.") },
                edit = { _, _, _ -> error("Edit must not be called for invalid input.") },
            )
        }

        assertTrue(state.isEditing)
    }

    @Test
    fun `invalid alarm date fails with the localized date message`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            AlarmSubmissionState().submit(
                timeText = "13:00",
                dateText = "2026-13-99",
                label = "Review",
                clock = clock,
                create = { _, _ -> error("Create must not be called for an invalid date.") },
                edit = { _, _, _ -> error("Edit must not be called for an invalid date.") },
            )
        }

        assertEquals(AlarmTimerBundle.message("panel.error.date.invalid"), failure.message)
        assertTrue(failure.cause is DateTimeParseException)
    }

    @Test
    fun `alarm submission lets cancellation escape instead of reporting invalid input`() {
        assertThrows(ProcessCanceledException::class.java) {
            AlarmSubmissionState().submit(
                timeText = "13:00",
                dateText = "",
                label = "Review",
                clock = clock,
                create = { _, _ -> throw ProcessCanceledException() },
                edit = { _, _, _ -> error("Edit must not be called in create mode.") },
            )
        }
    }

    @Test
    fun `clear history confirmation names the count and separates title from warning`() {
        val one = ClearHistoryConfirmation.message(1)
        val three = ClearHistoryConfirmation.message(3)
        val title = ClearHistoryConfirmation.title()

        assertTrue(one.contains("1"), one)
        assertTrue(three.contains("3"), three)
        assertNotEquals(one, three)
        assertFalse(title.isBlank())
        assertNotEquals(title, one)
    }

    @Test
    fun `only input failures may become a dialog`() {
        assertTrue(SubmissionFailurePolicy.mustPropagate(ProcessCanceledException()))
        assertTrue(SubmissionFailurePolicy.mustPropagate(CancellationException()))
        assertTrue(SubmissionFailurePolicy.mustPropagate(OutOfMemoryError("out of memory")))
        assertFalse(SubmissionFailurePolicy.mustPropagate(IllegalArgumentException("invalid date")))
        assertFalse(SubmissionFailurePolicy.mustPropagate(IllegalStateException("stale edit")))
        assertFalse(SubmissionFailurePolicy.mustPropagate(DateTimeParseException("bad time", "nope", 0)))
    }

    @Test
    fun `reconcile preserves stale alarm edit so its draft cannot silently create a duplicate`() {
        val state = AlarmSubmissionState()
        val editing = editableAlarm()
        state.beginEdit(editing)

        assertFalse(state.reconcile(listOf(editing)))
        assertTrue(state.isEditing)
        assertTrue(state.reconcile(listOf(editing.copy(label = "Changed elsewhere"))))
        assertTrue(state.isEditing)

        var createCount = 0
        val result = state.submit(
            timeText = "13:00",
            dateText = "",
            label = "Replacement",
            clock = clock,
            create = { _, _ -> createCount++ },
            edit = { _, _, _ -> error("Edit must not be called after stale reconciliation.") },
        )
        assertEquals(AlarmSubmissionResult.STALE_EDIT, result)
        assertEquals(0, createCount)
        assertFalse(state.isEditing)
    }

    @Test
    fun `stale edit failure clears mode so next submission creates`() {
        val state = AlarmSubmissionState()
        state.beginEdit(editableAlarm("missing"))

        val staleResult = state.submit(
            timeText = "13:00",
            dateText = "",
            label = "Review",
            clock = clock,
            create = { _, _ -> error("Create must not be called for the stale edit attempt.") },
            edit = { _, _, _ -> false },
        )

        assertEquals(AlarmSubmissionResult.STALE_EDIT, staleResult)
        assertFalse(state.isEditing)

        var createCount = 0
        val retryResult = state.submit(
            timeText = "13:00",
            dateText = "",
            label = "Review",
            clock = clock,
            create = { _, _ -> createCount++ },
            edit = { _, _, _ -> error("Edit must not be retried after stale failure.") },
        )
        assertEquals(AlarmSubmissionResult.CREATED, retryResult)
        assertEquals(1, createCount)
    }
}

private const val COMPACT_CONTROL_WIDTH = 264
