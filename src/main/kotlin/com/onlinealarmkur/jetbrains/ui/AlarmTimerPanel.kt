package com.onlinealarmkur.jetbrains.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.onlinealarmkur.jetbrains.AlarmTimerBundle
import com.onlinealarmkur.jetbrains.domain.AlarmTimeCalculator
import com.onlinealarmkur.jetbrains.domain.AlarmTimeText
import com.onlinealarmkur.jetbrains.domain.DurationParser
import com.onlinealarmkur.jetbrains.domain.Formatters
import com.onlinealarmkur.jetbrains.domain.ItemKind
import com.onlinealarmkur.jetbrains.domain.ItemStatus
import com.onlinealarmkur.jetbrains.domain.ScheduledItem
import com.onlinealarmkur.jetbrains.service.AlarmTimerEvent
import com.onlinealarmkur.jetbrains.service.AlarmTimerService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.HierarchyEvent
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.util.concurrent.CancellationException
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextField

class AlarmTimerPanel : JPanel(BorderLayout()), Disposable {
    private val service = AlarmTimerService.getInstance()
    private val tabs = JBTabbedPane()
    private val alarmTimeState = AlarmTimeFieldState(
        initialTime = LocalTime.now().plusMinutes(5),
        initialUse24HourTime = service.settings().use24HourTime,
    )
    private val alarmTime = JTextField(alarmTimeState.initialText)
        .identified(AlarmTimerPanelComponentNames.ALARM_TIME, AlarmTimerBundle.message("accessible.alarm.time"))
    private val alarmDate = JTextField()
        .identified(AlarmTimerPanelComponentNames.ALARM_DATE, AlarmTimerBundle.message("accessible.alarm.date"))
    private val alarmLabel = JTextField()
        .identified(AlarmTimerPanelComponentNames.ALARM_LABEL, AlarmTimerBundle.message("accessible.alarm.label"))
    private val alarmSubmissionState = AlarmSubmissionState()
    private val alarmSubmitButton = JButton(AlarmTimerBundle.message("panel.set.alarm"))
        .identified(AlarmTimerPanelComponentNames.ALARM_SUBMIT, AlarmTimerBundle.message("accessible.alarm.submit"))
        .apply { addActionListener { submitAlarm() } }
    private val cancelAlarmEditButton = JButton(AlarmTimerBundle.message("panel.cancel.edit"))
        .identified(
            AlarmTimerPanelComponentNames.ALARM_CANCEL_EDIT,
            AlarmTimerBundle.message("accessible.alarm.cancel.edit"),
        )
        .apply {
            isVisible = false
            addActionListener { cancelAlarmEdit() }
        }
    private val timerDurationState = TimerDurationFieldState(service.settings().defaultTimerMs)
    private val timerDuration = JTextField(timerDurationState.initialText)
        .identified(AlarmTimerPanelComponentNames.TIMER_DURATION, AlarmTimerBundle.message("accessible.timer.duration"))
    private val timerLabel = JTextField()
        .identified(AlarmTimerPanelComponentNames.TIMER_LABEL, AlarmTimerBundle.message("accessible.timer.label"))
    private val timerStartButton = JButton(AlarmTimerBundle.message("panel.start.timer"))
        .identified(AlarmTimerPanelComponentNames.TIMER_START, AlarmTimerBundle.message("accessible.timer.start"))
        .apply { addActionListener { submitTimer() } }
    private val timerPresetButtons = listOf(
        1 to AlarmTimerPanelComponentNames.TIMER_PRESET_1_MINUTE,
        5 to AlarmTimerPanelComponentNames.TIMER_PRESET_5_MINUTES,
        10 to AlarmTimerPanelComponentNames.TIMER_PRESET_10_MINUTES,
        15 to AlarmTimerPanelComponentNames.TIMER_PRESET_15_MINUTES,
        30 to AlarmTimerPanelComponentNames.TIMER_PRESET_30_MINUTES,
        60 to AlarmTimerPanelComponentNames.TIMER_PRESET_60_MINUTES,
    ).map { (minutes, componentName) ->
        val text = if (minutes == 60) {
            AlarmTimerBundle.message("panel.preset.hour")
        } else {
            AlarmTimerBundle.message("panel.preset.minutes", minutes)
        }
        JButton(text)
            .identified(
                componentName,
                if (minutes == 1) {
                    AlarmTimerBundle.message("accessible.timer.preset.one.minute")
                } else {
                    AlarmTimerBundle.message("accessible.timer.preset.minutes", minutes)
                },
            )
            .apply {
                addActionListener {
                    timerDuration.text = "${minutes}m"
                    submitTimer()
                }
            }
    }
    private val activeModel = DefaultListModel<ScheduledItem>()
    private val historyModel = DefaultListModel<ScheduledItem>()
    private val activeList = itemList(activeModel, AlarmTimerBundle.message("panel.empty.active"))
        .identified(AlarmTimerPanelComponentNames.ACTIVE_LIST, AlarmTimerBundle.message("accessible.active.list"))
    private val historyList = itemList(historyModel, AlarmTimerBundle.message("panel.empty.history"))
        .identified(AlarmTimerPanelComponentNames.HISTORY_LIST, AlarmTimerBundle.message("accessible.history.list"))
    private val pauseResumeButton = JButton(AlarmTimerBundle.message("panel.pause.resume"))
        .identified(
            AlarmTimerPanelComponentNames.ACTIVE_PAUSE_RESUME,
            AlarmTimerBundle.message("accessible.pause.resume"),
        )
        .apply {
            addActionListener {
                selectedActive()
                    ?.takeIf { ActiveItemControlAvailabilityProjection.forSelection(it).pauseOrResume }
                    ?.let { if (it.status == ItemStatus.PAUSED) service.resume(it.id) else service.pause(it.id) }
            }
        }
    private val restartButton = JButton(AlarmTimerBundle.message("panel.restart"))
        .identified(AlarmTimerPanelComponentNames.ACTIVE_RESTART, AlarmTimerBundle.message("accessible.restart"))
        .apply {
            addActionListener {
                selectedActive()
                    ?.takeIf { ActiveItemControlAvailabilityProjection.forSelection(it).restart }
                    ?.let { service.restart(it.id) }
            }
        }
    private val editAlarmButton = JButton(AlarmTimerBundle.message("panel.edit.alarm"))
        .identified(AlarmTimerPanelComponentNames.ACTIVE_EDIT_ALARM, AlarmTimerBundle.message("accessible.edit.alarm"))
        .apply { addActionListener { beginEdit() } }
    private val cancelActiveButton = JButton(AlarmTimerBundle.message("panel.cancel"))
        .identified(AlarmTimerPanelComponentNames.ACTIVE_CANCEL, AlarmTimerBundle.message("accessible.cancel"))
        .apply {
            addActionListener {
                selectedActive()
                    ?.takeIf { ActiveItemControlAvailabilityProjection.forSelection(it).cancel }
                    ?.let { service.cancel(it.id) }
            }
        }
    private val historyRemoveButton = JButton(AlarmTimerBundle.message("panel.remove"))
        .identified(AlarmTimerPanelComponentNames.HISTORY_REMOVE, AlarmTimerBundle.message("accessible.remove"))
        .apply { addActionListener { historyList.selectedValue?.let { service.cancel(it.id) } } }
    private val historyClearAllButton = JButton(AlarmTimerBundle.message("panel.clear.all"))
        .identified(AlarmTimerPanelComponentNames.HISTORY_CLEAR_ALL, AlarmTimerBundle.message("accessible.clear.all"))
        .apply {
            addActionListener {
                val count = historyModel.size
                if (count > 0 && confirmClearHistory(count)) service.clearCompleted()
            }
        }
    private val listProjection = AlarmTimerListProjection(activeModel, historyModel)
    private val destinationFocus = AlarmTimerDestinationFocus(
        selectTab = { tabs.selectedIndex = it },
        focusAlarmTime = { alarmTime.requestFocusInWindow() },
        focusTimerDuration = { timerDuration.requestFocusInWindow() },
    )

    private var alarmTimeHint: JEditorPane? = null

    @Volatile private var panelShowing = false
    @Volatile private var clockPulseItems = emptyList<ScheduledItem>()
    internal var isDisposed = false
        private set

    init {
        service.confirmUiReady()
        border = JBUI.Borders.empty(8)
        tabs.addTab(AlarmTimerBundle.message("panel.alarm.tab"), alarmForm())
        tabs.addTab(AlarmTimerBundle.message("panel.timer.tab"), timerForm())

        val limitationText = AlarmTimerBundle.message("panel.ide.running.limit")
        val limitation = JBLabel(limitationText)
            .identified(AlarmTimerPanelComponentNames.IDE_RUNNING_LIMIT, limitationText)
        limitation.foreground = JBColor.GRAY
        limitation.border = JBUI.Borders.emptyBottom(8)
        limitation.toolTipText = limitationText

        val top = JPanel(BorderLayout()).apply {
            add(limitation, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }
        val lists = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            section(AlarmTimerBundle.message("panel.section.active"), activeList, activeButtons()),
            section(AlarmTimerBundle.message("panel.section.history"), historyList, historyButtons()),
        ).apply {
            resizeWeight = 0.65
            isOneTouchExpandable = true
            dividerSize = JBUI.scale(5)
        }
        add(top, BorderLayout.NORTH)
        add(lists, BorderLayout.CENTER)
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                panelShowing = isShowing
                if (panelShowing) refreshSuggestedAlarmTime()
            }
        }
        activeList.addListSelectionListener { updateActiveControls() }
        historyList.addListSelectionListener { updateHistoryControls() }
        service.addListener(
            this,
            wantsClockPulses = {
                AlarmTimerPanelPulseInterest.wantsClockPulses(panelShowing, clockPulseItems)
            },
        ) { event ->
            when (event) {
                AlarmTimerEvent.STRUCTURE_CHANGED -> refreshStructure()
                AlarmTimerEvent.CLOCK_PULSE -> activeList.repaint()
            }
        }
        refreshStructure()
    }

    override fun dispose() {
        isDisposed = true
        panelShowing = false
        clockPulseItems = emptyList()
    }

    internal fun focusDestination(destination: AlarmTimerDestination) {
        destinationFocus.focus(destination)
    }

    private fun alarmForm(): JComponent = panel {
        row(AlarmTimerBundle.message("panel.time.label")) {
            cell(alarmTime)
                .align(AlignX.FILL)
                .comment(AlarmTimeText.hint(service.settings().use24HourTime))
                .also { alarmTimeHint = it.comment }
        }
        row(AlarmTimerBundle.message("panel.date.label")) {
            cell(alarmDate).align(AlignX.FILL).comment(AlarmTimerBundle.message("panel.date.comment"))
        }
        row(AlarmTimerBundle.message("panel.label.label")) { cell(alarmLabel).align(AlignX.FILL) }
        row {
            cell(alarmSubmitButton)
            cell(cancelAlarmEditButton)
        }
    }

    private fun timerForm(): JComponent = panel {
        row(AlarmTimerBundle.message("panel.duration.label")) {
            cell(timerDuration).align(AlignX.FILL).comment(AlarmTimerBundle.message("panel.duration.comment"))
        }
        row(AlarmTimerBundle.message("panel.label.label")) { cell(timerLabel).align(AlignX.FILL) }
        row {
            cell(timerStartButton)
        }
        timerPresetButtons.chunked(3).forEach { presetRow ->
            row {
                presetRow.forEach { cell(it) }
            }
        }
    }

    private fun section(title: String, list: JBList<ScheduledItem>, buttons: JComponent): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyTop(8)
        add(JBLabel(title).apply { border = JBUI.Borders.emptyBottom(4) }, BorderLayout.NORTH)
        add(JBScrollPane(list).apply { preferredSize = Dimension(280, 130) }, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
    }

    private fun activeButtons() = ResponsiveButtonGrid(
        maximumColumns = 2,
        horizontalGap = JBUI.scale(4),
        verticalGap = JBUI.scale(4),
    ).apply {
        add(pauseResumeButton)
        add(restartButton)
        add(editAlarmButton)
        add(cancelActiveButton)
    }

    private fun historyButtons() = ResponsiveButtonGrid(
        maximumColumns = 2,
        horizontalGap = JBUI.scale(4),
        verticalGap = JBUI.scale(4),
    ).apply {
        add(historyRemoveButton)
        add(historyClearAllButton)
    }

    private fun submitAlarm() {
        try {
            val result = alarmSubmissionState.submit(
                timeText = alarmTime.text,
                dateText = alarmDate.text,
                label = alarmLabel.text,
                clock = Clock.systemDefaultZone(),
                create = { target, label -> service.scheduleAlarm(target, label) },
                edit = service::editAlarm,
            )
            updateAlarmEditControls()
            alarmTimeState.clearSecondsPreference()
            alarmLabel.text = ""
            alarmDate.text = ""
            check(result != AlarmSubmissionResult.STALE_EDIT) {
                AlarmTimerBundle.message("panel.error.edit.failed")
            }
        } catch (error: Throwable) {
            showInputFailure(error, "panel.error.alarm.invalid")
        }
    }

    private fun submitTimer() {
        DurationParser.parse(timerDuration.text)
            .onSuccess {
                service.startTimer(it, timerLabel.text)
                timerLabel.text = ""
            }
            .onFailure { showInputFailure(it, "panel.error.duration.invalid") }
    }

    private fun showInputFailure(error: Throwable, fallbackKey: String) {
        if (SubmissionFailurePolicy.mustPropagate(error)) throw error
        showError(error.message ?: AlarmTimerBundle.message(fallbackKey))
    }

    private fun beginEdit() {
        val item = selectedActive() ?: return
        if (!ActiveItemControlAvailabilityProjection.forSelection(item).editAlarm) return
        val local = java.time.Instant.ofEpochMilli(item.targetEpochMs).atZone(java.time.ZoneId.systemDefault())
        alarmSubmissionState.beginEdit(item)
        alarmTime.text = alarmTimeState.programmaticText(
            time = local.toLocalTime(),
            use24HourTime = service.settings().use24HourTime,
            includeSeconds = true,
        )
        alarmDate.text = local.toLocalDate().toString()
        alarmLabel.text = item.label
        updateAlarmEditControls()
        focusDestination(AlarmTimerDestination.ALARM)
    }

    private fun cancelAlarmEdit() {
        alarmSubmissionState.cancelEdit()
        alarmTimeState.clearSecondsPreference()
        updateAlarmEditControls()
    }

    private fun updateAlarmEditControls() {
        alarmSubmitButton.text = if (alarmSubmissionState.isEditing) {
            AlarmTimerBundle.message("panel.save.alarm")
        } else {
            AlarmTimerBundle.message("panel.set.alarm")
        }
        cancelAlarmEditButton.isVisible = alarmSubmissionState.isEditing
        cancelAlarmEditButton.parent?.revalidate()
        cancelAlarmEditButton.parent?.repaint()
    }

    private fun refreshSuggestedAlarmTime() {
        alarmTimeState.regenerate(
            time = LocalTime.now().plusMinutes(5),
            currentText = alarmTime.text,
            fieldFocused = alarmTime.isFocusOwner,
            isEditing = alarmSubmissionState.isEditing,
        )?.let { alarmTime.text = it }
    }

    private fun refreshStructure() {
        refreshSuggestedAlarmTime()
        val settings = service.settings()
        val alarmTimeUpdate = alarmTimeState.updateFormat(settings.use24HourTime, alarmTime.text)
        if (alarmTimeUpdate.text != alarmTime.text) alarmTime.text = alarmTimeUpdate.text
        if (alarmTimeUpdate.formatChanged) {
            alarmTimeHint?.text = AlarmTimeText.hint(settings.use24HourTime)
        }
        val timerText = timerDurationState.updateDefault(settings.defaultTimerMs, timerDuration.text)
        if (timerText != timerDuration.text) timerDuration.text = timerText
        val selectedActiveId = activeList.selectedValue?.id
        val selectedHistoryId = historyList.selectedValue?.id
        val items = service.items()
        clockPulseItems = items
        listProjection.reconcile(items)
        val activeAlarms = items
            .filter { it.kind == ItemKind.ALARM && it.status == ItemStatus.ACTIVE }
        if (alarmSubmissionState.reconcile(activeAlarms)) {
            alarmTimeState.clearSecondsPreference()
            updateAlarmEditControls()
        }
        restoreSelection(activeList, selectedActiveId)
        restoreSelection(historyList, selectedHistoryId)
        updateActiveControls()
        updateHistoryControls()
        activeList.repaint()
        historyList.repaint()
    }

    private fun itemList(model: DefaultListModel<ScheduledItem>, empty: String) = JBList(model).apply {
        emptyText.text = empty
        cellRenderer = object : ColoredListCellRenderer<ScheduledItem>() {
            override fun customizeCellRenderer(list: javax.swing.JList<out ScheduledItem>, value: ScheduledItem, index: Int, selected: Boolean, hasFocus: Boolean) {
                val presentation = ScheduledItemPresentationFactory.present(
                    value,
                    service.settings().use24HourTime,
                    service.remainingMs(value.id),
                )
                append(presentation.name)
                append("  ${presentation.detail}", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
                // ColoredListCellRenderer exposes appended text through its standard accessible
                // text implementation. Do not assign a row-specific name to this shared renderer:
                // cycling that property once per painted row creates an accessibility event storm.
            }
        }
    }

    private fun selectedActive(): ScheduledItem? = activeList.selectedValue
    private fun updateActiveControls() {
        val selected = selectedActive()
        val availability = ActiveItemControlAvailabilityProjection.forSelection(selected)
        pauseResumeButton.isEnabled = availability.pauseOrResume
        pauseResumeButton.accessibleContext.accessibleName = AlarmTimerBundle.message(
            when (selected?.status) {
                ItemStatus.ACTIVE -> "accessible.pause.selected.timer"
                ItemStatus.PAUSED -> "accessible.resume.selected.timer"
                else -> "accessible.pause.resume"
            },
        )
        restartButton.isEnabled = availability.restart
        editAlarmButton.isEnabled = availability.editAlarm
        cancelActiveButton.isEnabled = availability.cancel
    }

    private fun updateHistoryControls() {
        val hasSelection = historyList.selectedValue != null
        historyRemoveButton.isEnabled = hasSelection
        historyClearAllButton.isEnabled = !historyModel.isEmpty
    }

    private fun restoreSelection(list: JBList<ScheduledItem>, id: String?) {
        if (id == null) return
        for (index in 0 until list.model.size) {
            if (list.model.getElementAt(index).id == id) {
                list.selectedIndex = index
                return
            }
        }
        list.clearSelection()
    }
    private fun showError(message: String) =
        Messages.showErrorDialog(this, message, AlarmTimerBundle.message("panel.error.title"))

    /**
     * Guards the only bulk deletion the panel offers. Clearing the history is permanent and has no
     * undo, so a misclick next to **Remove** must not be enough to destroy labelled reminders.
     */
    private fun confirmClearHistory(count: Int): Boolean = Messages.showYesNoDialog(
        this,
        ClearHistoryConfirmation.message(count),
        ClearHistoryConfirmation.title(),
        null,
    ) == Messages.YES
}

internal data class ActiveItemControlAvailability(
    val pauseOrResume: Boolean,
    val restart: Boolean,
    val editAlarm: Boolean,
    val cancel: Boolean,
) {
    companion object {
        val NONE = ActiveItemControlAvailability(false, false, false, false)
        val TIMER = ActiveItemControlAvailability(true, true, false, true)
        val ALARM = ActiveItemControlAvailability(false, false, true, true)
    }
}

internal object ActiveItemControlAvailabilityProjection {
    fun forSelection(item: ScheduledItem?): ActiveItemControlAvailability = when {
        item?.kind == ItemKind.TIMER &&
            (item.status == ItemStatus.ACTIVE || item.status == ItemStatus.PAUSED) ->
            ActiveItemControlAvailability.TIMER
        item?.kind == ItemKind.ALARM && item.status == ItemStatus.ACTIVE ->
            ActiveItemControlAvailability.ALARM
        else -> ActiveItemControlAvailability.NONE
    }
}

internal object SubmissionFailurePolicy {
    /**
     * Decides whether a submit-path failure may be reported as an invalid-input dialog.
     *
     * Cancellation and JVM errors must never be turned into a dialog: `ProcessCanceledException`
     * implements [ControlFlowException], and the platform requires it to keep propagating.
     */
    fun mustPropagate(error: Throwable): Boolean =
        error is ControlFlowException || error is CancellationException || error is Error
}

/**
 * Copy for the confirmation that guards clearing the history, shared by the panel button and the
 * **Tools** menu action so both surfaces warn with the same words and the same item count.
 */
internal object ClearHistoryConfirmation {
    fun title(): String = AlarmTimerBundle.message("dialog.clear.history.title")

    fun message(count: Int): String = AlarmTimerBundle.message("dialog.clear.history.message", count)
}

internal object AlarmTimerPanelPulseInterest {
    fun wantsClockPulses(panelShowing: Boolean, items: List<ScheduledItem>): Boolean =
        panelShowing && items.any { it.kind == ItemKind.TIMER && it.status == ItemStatus.ACTIVE }
}

internal class AlarmTimerListProjection(
    private val activeModel: DefaultListModel<ScheduledItem>,
    private val historyModel: DefaultListModel<ScheduledItem>,
) {
    fun reconcile(items: List<ScheduledItem>) {
        reconcileModel(activeModel, items.filter { it.status == ItemStatus.ACTIVE || it.status == ItemStatus.PAUSED })
        reconcileModel(historyModel, items.filterNot { it.status == ItemStatus.ACTIVE || it.status == ItemStatus.PAUSED })
    }

    private fun reconcileModel(model: DefaultListModel<ScheduledItem>, desired: List<ScheduledItem>) {
        desired.forEachIndexed { index, item ->
            if (index < model.size && model.getElementAt(index).id == item.id) {
                if (model.getElementAt(index) != item) model.setElementAt(item, index)
                return@forEachIndexed
            }

            val existingIndex = (index + 1 until model.size).firstOrNull { model.getElementAt(it).id == item.id }
            if (existingIndex != null) model.removeElementAt(existingIndex)
            model.insertElementAt(item, index)
        }
        while (model.size > desired.size) model.removeElementAt(model.size - 1)
    }
}

internal class TimerDurationFieldState(initialDefaultMs: Long) {
    private var defaultText = Formatters.editableDuration(initialDefaultMs)
    val initialText: String get() = defaultText

    fun updateDefault(newDefaultMs: Long, currentText: String): String {
        val nextDefaultText = Formatters.editableDuration(newDefaultMs)
        val result = if (currentText == defaultText) nextDefaultText else currentText
        defaultText = nextDefaultText
        return result
    }
}

/**
 * Keeps short translated controls in a compact grid and falls back to one column before labels
 * clip. The preferred height follows the width already assigned to the section, so BorderLayout
 * reserves enough vertical space on the same layout pass.
 */
internal class ResponsiveButtonGrid(
    private val maximumColumns: Int,
    private val horizontalGap: Int,
    private val verticalGap: Int,
) : JPanel(null) {
    init {
        require(maximumColumns > 0)
        require(horizontalGap >= 0)
        require(verticalGap >= 0)
    }

    internal val columnCount: Int
        get() = columnsFor(layoutWidth())

    override fun getPreferredSize(): Dimension = gridSize(columnCount)

    override fun getMinimumSize(): Dimension = gridSize(1)

    override fun doLayout() {
        val visibleComponents = components.filter(Component::isVisible)
        if (visibleComponents.isEmpty()) return
        val columns = columnsFor(width)
        val rows = (visibleComponents.size + columns - 1) / columns
        val availableWidth = (width - insets.left - insets.right - horizontalGap * (columns - 1)).coerceAtLeast(0)
        val cellWidth = availableWidth / columns
        val rowHeights = IntArray(rows)
        visibleComponents.forEachIndexed { index, component ->
            val row = index / columns
            rowHeights[row] = maxOf(rowHeights[row], component.preferredSize.height)
        }

        var y = insets.top
        visibleComponents.forEachIndexed { index, component ->
            val row = index / columns
            val column = index % columns
            component.setBounds(
                insets.left + column * (cellWidth + horizontalGap),
                y,
                cellWidth,
                rowHeights[row],
            )
            if (column == columns - 1 || index == visibleComponents.lastIndex) {
                y += rowHeights[row] + verticalGap
            }
        }
    }

    private fun layoutWidth(): Int {
        if (width > 0) return width
        val container = parent ?: return 0
        return (container.width - container.insets.left - container.insets.right).coerceAtLeast(0)
    }

    private fun columnsFor(availableWidth: Int): Int {
        val visibleComponents = components.filter(Component::isVisible)
        val upperBound = minOf(maximumColumns, visibleComponents.size).coerceAtLeast(1)
        if (availableWidth <= 0 || upperBound == 1) return upperBound
        val contentWidth = (availableWidth - insets.left - insets.right).coerceAtLeast(0)
        val widest = visibleComponents.maxOfOrNull { it.preferredSize.width } ?: return upperBound
        return if (widest * upperBound + horizontalGap * (upperBound - 1) <= contentWidth) upperBound else 1
    }

    private fun gridSize(columns: Int): Dimension {
        val visibleComponents = components.filter(Component::isVisible)
        if (visibleComponents.isEmpty()) return Dimension(insets.left + insets.right, insets.top + insets.bottom)
        val rows = (visibleComponents.size + columns - 1) / columns
        val width = visibleComponents.maxOf { it.preferredSize.width } * columns +
            horizontalGap * (columns - 1) + insets.left + insets.right
        val rowHeights = IntArray(rows)
        visibleComponents.forEachIndexed { index, component ->
            val row = index / columns
            rowHeights[row] = maxOf(rowHeights[row], component.preferredSize.height)
        }
        val height = rowHeights.sum() + verticalGap * (rows - 1) + insets.top + insets.bottom
        return Dimension(width, height)
    }
}

internal data class AlarmTimeFieldUpdate(
    val text: String,
    val formatChanged: Boolean,
)

internal class AlarmTimeFieldState(
    initialTime: LocalTime,
    initialUse24HourTime: Boolean,
) {
    private var generatedTime = initialTime
    private var includeSeconds = false
    private var use24HourTime = initialUse24HourTime
    private var generatedText = formatGenerated()

    val initialText: String get() = generatedText

    fun programmaticText(
        time: LocalTime,
        use24HourTime: Boolean,
        includeSeconds: Boolean,
    ): String {
        generatedTime = time
        this.use24HourTime = use24HourTime
        this.includeSeconds = includeSeconds
        generatedText = formatGenerated()
        return generatedText
    }

    /**
     * Returns a freshly generated text for [time], or `null` when the field must not be touched:
     * the user typed into it, it holds the caret, or an alarm edit is in progress.
     */
    fun regenerate(
        time: LocalTime,
        currentText: String,
        fieldFocused: Boolean,
        isEditing: Boolean,
    ): String? {
        if (fieldFocused || isEditing || currentText != generatedText) return null
        generatedTime = time
        generatedText = formatGenerated()
        return generatedText
    }

    /**
     * Stops later generated times from carrying seconds. [generatedText] is deliberately left alone:
     * it tracks the text this state last handed to the field, so recomputing it here would make the
     * field look user-typed and would block [regenerate] for the rest of the session.
     */
    fun clearSecondsPreference() {
        includeSeconds = false
    }

    fun updateFormat(newUse24HourTime: Boolean, currentText: String): AlarmTimeFieldUpdate {
        if (newUse24HourTime == use24HourTime) {
            return AlarmTimeFieldUpdate(currentText, formatChanged = false)
        }
        val wasGenerated = currentText == generatedText
        use24HourTime = newUse24HourTime
        generatedText = formatGenerated()
        return AlarmTimeFieldUpdate(
            text = if (wasGenerated) generatedText else currentText,
            formatChanged = true,
        )
    }

    private fun formatGenerated(): String = AlarmTimeText.format(
        time = generatedTime,
        use24HourTime = use24HourTime,
        includeSeconds = includeSeconds,
    )
}

internal enum class AlarmSubmissionResult {
    CREATED,
    EDITED,
    STALE_EDIT,
}

internal class AlarmSubmissionState {
    private var editingAlarm: ScheduledItem? = null
    private var staleEdit = false
    val isEditing: Boolean get() = editingAlarm != null

    fun beginEdit(alarm: ScheduledItem) {
        require(alarm.kind == ItemKind.ALARM && alarm.status == ItemStatus.ACTIVE)
        editingAlarm = alarm
        staleEdit = false
    }

    fun cancelEdit() {
        editingAlarm = null
        staleEdit = false
    }

    fun reconcile(activeAlarms: List<ScheduledItem>): Boolean {
        val editing = editingAlarm ?: return false
        val nextStaleEdit = activeAlarms.none { it == editing }
        val changed = staleEdit != nextStaleEdit
        staleEdit = nextStaleEdit
        // Retain edit mode and its draft. The next Save reports a stale edit instead of silently
        // reinterpreting the draft as a brand-new alarm in another project window.
        return changed
    }

    fun submit(
        timeText: String,
        dateText: String,
        label: String,
        clock: Clock,
        create: (targetEpochMs: Long, label: String) -> Unit,
        edit: (expected: ScheduledItem, targetEpochMs: Long, label: String) -> Boolean,
    ): AlarmSubmissionResult {
        val time = AlarmTimeText.parse(timeText)
        val date = dateText.trim().takeIf(String::isNotEmpty)?.let {
            try {
                LocalDate.parse(it)
            } catch (error: DateTimeParseException) {
                throw IllegalArgumentException(AlarmTimerBundle.message("panel.error.date.invalid"), error)
            }
        }
        val target = AlarmTimeCalculator.nextOccurrence(time, date, clock)
        val editing = editingAlarm
        if (editing == null) {
            create(target, label)
            return AlarmSubmissionResult.CREATED
        }
        if (staleEdit || !edit(editing, target, label)) {
            editingAlarm = null
            staleEdit = false
            return AlarmSubmissionResult.STALE_EDIT
        }
        editingAlarm = null
        staleEdit = false
        return AlarmSubmissionResult.EDITED
    }
}

internal object AlarmTimerPanelComponentNames {
    const val IDE_RUNNING_LIMIT = "alarmTimer.ideRunningLimit"
    const val ALARM_TIME = "alarmTimer.alarm.time"
    const val ALARM_DATE = "alarmTimer.alarm.date"
    const val ALARM_LABEL = "alarmTimer.alarm.label"
    const val ALARM_SUBMIT = "alarmTimer.alarm.submit"
    const val ALARM_CANCEL_EDIT = "alarmTimer.alarm.cancelEdit"
    const val TIMER_DURATION = "alarmTimer.timer.duration"
    const val TIMER_LABEL = "alarmTimer.timer.label"
    const val TIMER_START = "alarmTimer.timer.start"
    const val TIMER_PRESET_1_MINUTE = "alarmTimer.timer.preset.1m"
    const val TIMER_PRESET_5_MINUTES = "alarmTimer.timer.preset.5m"
    const val TIMER_PRESET_10_MINUTES = "alarmTimer.timer.preset.10m"
    const val TIMER_PRESET_15_MINUTES = "alarmTimer.timer.preset.15m"
    const val TIMER_PRESET_30_MINUTES = "alarmTimer.timer.preset.30m"
    const val TIMER_PRESET_60_MINUTES = "alarmTimer.timer.preset.60m"
    const val ACTIVE_LIST = "alarmTimer.active.list"
    const val ACTIVE_PAUSE_RESUME = "alarmTimer.active.pauseResume"
    const val ACTIVE_RESTART = "alarmTimer.active.restart"
    const val ACTIVE_EDIT_ALARM = "alarmTimer.active.editAlarm"
    const val ACTIVE_CANCEL = "alarmTimer.active.cancel"
    const val HISTORY_LIST = "alarmTimer.history.list"
    const val HISTORY_REMOVE = "alarmTimer.history.remove"
    const val HISTORY_CLEAR_ALL = "alarmTimer.history.clearAll"
}

private fun <T : JComponent> T.identified(componentName: String, accessibleName: String): T = apply {
    name = componentName
    accessibleContext.accessibleName = accessibleName
}
