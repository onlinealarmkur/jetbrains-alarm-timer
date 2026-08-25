package com.onlinealarmkur.jetbrains.domain

import com.onlinealarmkur.jetbrains.AlarmTimerBundle
import java.time.Clock
import java.util.UUID

class AlarmEngine(
    private val clock: Clock,
    private val elapsedTimeSource: ElapsedTimeSource,
) {
    private val items = linkedMapOf<String, ScheduledItem>()
    private val activeItemIds = linkedSetOf<String>()
    private val timerDeadlines = mutableMapOf<String, Long>()
    private val restoredActiveIdsAwaitingStartup = mutableSetOf<String>()
    private val restoredPendingIdsAwaitingStartup = mutableSetOf<String>()

    @Synchronized fun snapshot(): List<ScheduledItem> = items.values.toList()

    @Synchronized fun item(id: String): ScheduledItem? = items[id]

    @Synchronized fun hasActiveItems(): Boolean = activeItemIds.isNotEmpty()

    @Synchronized
    fun nearestActiveRemainingMs(): Long? {
        val wallNow = clock.millis()
        val elapsedNow = elapsedTimeSource.nowMillis()
        return activeItemIds
            .asSequence()
            .mapNotNull(items::get)
            .map { item ->
                when (item.kind) {
                    ItemKind.ALARM -> item.targetEpochMs - wallNow
                    // Self-healing like every other deadline read: a live timer without a stored
                    // deadline must not throw here, because this call drives live polling.
                    ItemKind.TIMER -> timerDeadline(item, wallNow, elapsedNow) - elapsedNow
                }.coerceAtLeast(0)
            }
            .minOrNull()
    }

    // The status-bar projection needs the nearest item and its remaining time together. One locked
    // pass over one pair of clock samples keeps every window's text consistent with itself, instead
    // of a snapshot plus a remainingMs lock per active item.
    @Synchronized
    fun nearestActive(): Pair<ScheduledItem, Long>? {
        val wallNow = clock.millis()
        val elapsedNow = elapsedTimeSource.nowMillis()
        return activeItemIds
            .asSequence()
            .mapNotNull(items::get)
            // Self-healing like every other deadline read: remaining() rebuilds a live timer's
            // missing deadline instead of throwing, because this call drives the widget text.
            .map { it to remaining(it, wallNow, elapsedNow) }
            .minByOrNull { it.second }
    }

    @Synchronized
    fun persistenceSnapshot(): List<ScheduledItem> {
        val wallNow = clock.millis()
        val elapsedNow = elapsedTimeSource.nowMillis()
        return items.values.map { item ->
            if (item.kind == ItemKind.TIMER && item.status == ItemStatus.ACTIVE) {
                item.copy(targetEpochMs = wallNow + timerRemaining(item, wallNow, elapsedNow))
            } else {
                item
            }
        }
    }

    @Synchronized
    fun remainingMs(id: String): Long {
        val item = items[id] ?: return 0
        return remaining(item, clock.millis(), elapsedTimeSource.nowMillis())
    }

    @Synchronized fun restore(restored: List<ScheduledItem>) {
        items.clear()
        activeItemIds.clear()
        timerDeadlines.clear()
        restoredActiveIdsAwaitingStartup.clear()
        restoredPendingIdsAwaitingStartup.clear()
        val wallNow = clock.millis()
        val elapsedNow = elapsedTimeSource.nowMillis()
        restored.forEach(::store)
        items.values
            .filter { it.status == ItemStatus.ACTIVE }
            .mapTo(restoredActiveIdsAwaitingStartup) { it.id }
        items.values
            .filter { it.deliveryPending }
            .mapTo(restoredPendingIdsAwaitingStartup) { it.id }
        items.values.forEach { item ->
            if (item.kind == ItemKind.TIMER && item.status == ItemStatus.ACTIVE) {
                // A wall clock that moved backward while the IDE was closed can rebuild a deadline
                // past the timer duration. Bound it by the duration, and leave a nonpositive
                // duration alone because it cannot bound anything.
                val rebuiltRemainingMs = (item.targetEpochMs - wallNow).coerceAtLeast(0)
                val boundedRemainingMs = if (item.durationMs > 0) {
                    rebuiltRemainingMs.coerceAtMost(item.durationMs)
                } else {
                    rebuiltRemainingMs
                }
                timerDeadlines[item.id] = elapsedNow + boundedRemainingMs
            }
        }
    }

    @Synchronized
    fun scheduleAlarm(targetEpochMs: Long, label: String, id: String = UUID.randomUUID().toString()): ScheduledItem {
        require(targetEpochMs > clock.millis()) { AlarmTimerBundle.message("alarm.error.future") }
        timerDeadlines.remove(id)
        restoredActiveIdsAwaitingStartup.remove(id)
        restoredPendingIdsAwaitingStartup.remove(id)
        return store(ScheduledItem(id, ItemKind.ALARM, normalizeLabel(label), clock.millis(), targetEpochMs))
    }

    @Synchronized
    fun startTimer(durationMs: Long, label: String, id: String = UUID.randomUUID().toString()): ScheduledItem {
        require(durationMs in 1_000..DurationParser.MAX_DURATION_MS) {
            AlarmTimerBundle.message("timer.error.range")
        }
        val now = clock.millis()
        restoredActiveIdsAwaitingStartup.remove(id)
        restoredPendingIdsAwaitingStartup.remove(id)
        timerDeadlines[id] = elapsedTimeSource.nowMillis() + durationMs
        return store(ScheduledItem(id, ItemKind.TIMER, normalizeLabel(label), now, now + durationMs, durationMs))
    }

    @Synchronized
    fun editAlarm(expected: ScheduledItem, targetEpochMs: Long, label: String): Boolean {
        val item = items[expected.id] ?: return false
        if (item != expected || item.kind != ItemKind.ALARM || targetEpochMs <= clock.millis()) return false
        store(item.copy(
            label = normalizeLabel(label),
            targetEpochMs = targetEpochMs,
            status = ItemStatus.ACTIVE,
            firedAtEpochMs = 0,
            deliveryPending = false,
        ))
        restoredActiveIdsAwaitingStartup.remove(item.id)
        restoredPendingIdsAwaitingStartup.remove(item.id)
        return true
    }

    @Synchronized
    fun pause(id: String): Boolean {
        val item = items[id] ?: return false
        if (item.kind != ItemKind.TIMER || item.status != ItemStatus.ACTIVE) return false
        // Never persist a paused remainder the timer could not have counted down from.
        val remainingMs = timerRemaining(item, clock.millis(), elapsedTimeSource.nowMillis())
            .coerceAtMost(item.durationMs)
        if (remainingMs < 1) return false
        store(item.copy(status = ItemStatus.PAUSED, remainingMs = remainingMs))
        timerDeadlines.remove(id)
        restoredActiveIdsAwaitingStartup.remove(id)
        return true
    }

    @Synchronized
    fun resume(id: String): Boolean {
        val item = items[id] ?: return false
        if (item.kind != ItemKind.TIMER || item.status != ItemStatus.PAUSED || item.remainingMs < 1) return false
        store(item.copy(status = ItemStatus.ACTIVE, targetEpochMs = clock.millis() + item.remainingMs, remainingMs = 0))
        timerDeadlines[id] = elapsedTimeSource.nowMillis() + item.remainingMs
        restoredActiveIdsAwaitingStartup.remove(id)
        return true
    }

    @Synchronized
    fun restart(id: String): Boolean {
        val item = items[id] ?: return false
        if (item.kind != ItemKind.TIMER || item.durationMs < 1_000) return false
        store(item.copy(
            status = ItemStatus.ACTIVE,
            targetEpochMs = clock.millis() + item.durationMs,
            remainingMs = 0,
            firedAtEpochMs = 0,
            deliveryPending = false,
        ))
        restoredActiveIdsAwaitingStartup.remove(id)
        restoredPendingIdsAwaitingStartup.remove(id)
        timerDeadlines[id] = elapsedTimeSource.nowMillis() + item.durationMs
        return true
    }

    @Synchronized
    fun cancel(id: String): Set<String> {
        if (items.remove(id) == null) return emptySet()
        activeItemIds.remove(id)
        timerDeadlines.remove(id)
        restoredActiveIdsAwaitingStartup.remove(id)
        restoredPendingIdsAwaitingStartup.remove(id)
        return setOf(id)
    }

    @Synchronized
    fun clearCompleted(): Set<String> {
        val removedIds = items.values
            .filter { it.status == ItemStatus.COMPLETED || it.status == ItemStatus.MISSED }
            .mapTo(linkedSetOf()) { it.id }
        removedIds.forEach(items::remove)
        activeItemIds.removeAll(removedIds)
        removedIds.forEach(timerDeadlines::remove)
        restoredActiveIdsAwaitingStartup.removeAll(removedIds)
        restoredPendingIdsAwaitingStartup.removeAll(removedIds)
        return removedIds
    }

    @Synchronized
    fun checkDue(gracePeriodMs: Long): List<DueItem> = checkDue(gracePeriodMs, recoverFromWallClock = false)

    @Synchronized
    fun checkDueAfterStartup(gracePeriodMs: Long): List<DueItem> =
        checkDue(gracePeriodMs, recoverFromWallClock = true)

    @Synchronized
    fun checkDueAfterActivation(gracePeriodMs: Long): List<DueItem> =
        checkDue(gracePeriodMs, recoverFromWallClock = false)

    private fun checkDue(gracePeriodMs: Long, recoverFromWallClock: Boolean): List<DueItem> {
        val wallNow = clock.millis()
        val elapsedNow = elapsedTimeSource.nowMillis()
        val grace = gracePeriodMs.coerceAtLeast(0)
        val due = mutableListOf<DueItem>()
        activeItemIds.toList().mapNotNull(items::get).forEach { item ->
            val recoverItemFromWallClock =
                recoverFromWallClock && restoredActiveIdsAwaitingStartup.remove(item.id)
            val lateness = when {
                item.kind == ItemKind.ALARM || recoverItemFromWallClock -> wallNow - item.targetEpochMs
                else -> elapsedNow - timerDeadline(item, wallNow, elapsedNow)
            }
            if (lateness < 0) {
                if (recoverItemFromWallClock && item.kind == ItemKind.TIMER) {
                    val restoredDeadline = timerDeadlines.getValue(item.id)
                    val wallDerivedDeadline = elapsedNow + (item.targetEpochMs - wallNow).coerceAtLeast(0)
                    timerDeadlines[item.id] = minOf(restoredDeadline, wallDerivedDeadline)
                }
                return@forEach
            }
            val shouldAlert = lateness <= grace
            val updated = item.copy(
                targetEpochMs = if (item.kind == ItemKind.TIMER && !recoverItemFromWallClock) {
                    wallNow - lateness
                } else {
                    item.targetEpochMs
                },
                status = if (shouldAlert) ItemStatus.COMPLETED else ItemStatus.MISSED,
                firedAtEpochMs = if (shouldAlert) wallNow else 0,
                deliveryPending = shouldAlert,
            )
            store(updated)
            timerDeadlines.remove(item.id)
            restoredActiveIdsAwaitingStartup.remove(item.id)
            restoredPendingIdsAwaitingStartup.remove(item.id)
            due += DueItem(updated, shouldAlert)
        }
        return due
    }

    @Synchronized
    fun recoverPendingAfterActivation(): List<DueItem> = items.values.mapNotNull { item ->
        if (!item.deliveryPending || item.id in restoredPendingIdsAwaitingStartup) return@mapNotNull null
        DueItem(item, true)
    }

    @Synchronized
    fun recoverPendingAfterStartup(gracePeriodMs: Long): List<DueItem> {
        val now = clock.millis()
        val grace = gracePeriodMs.coerceAtLeast(0)
        return items.values.toList().mapNotNull { item ->
            if (!item.deliveryPending) {
                restoredPendingIdsAwaitingStartup.remove(item.id)
                return@mapNotNull null
            }
            if (!restoredPendingIdsAwaitingStartup.remove(item.id)) return@mapNotNull DueItem(item, true)
            val shouldAlert = now - item.targetEpochMs <= grace
            if (shouldAlert) {
                DueItem(item, true)
            } else {
                val missed = item.copy(
                    status = ItemStatus.MISSED,
                    firedAtEpochMs = 0,
                    deliveryPending = false,
                )
                store(missed)
                DueItem(missed, false)
            }
        }
    }

    @Synchronized
    fun acknowledgeDelivery(id: String, firedAtEpochMs: Long): Boolean {
        val item = items[id] ?: return false
        if (item.status != ItemStatus.COMPLETED ||
            !item.deliveryPending ||
            item.firedAtEpochMs != firedAtEpochMs
        ) return false
        store(item.copy(deliveryPending = false))
        restoredActiveIdsAwaitingStartup.remove(id)
        restoredPendingIdsAwaitingStartup.remove(id)
        return true
    }

    private fun remaining(item: ScheduledItem, wallNow: Long, elapsedNow: Long): Long = when (item.status) {
        ItemStatus.PAUSED -> item.remainingMs.coerceAtLeast(0)
        ItemStatus.ACTIVE -> when (item.kind) {
            ItemKind.ALARM -> (item.targetEpochMs - wallNow).coerceAtLeast(0)
            ItemKind.TIMER -> timerRemaining(item, wallNow, elapsedNow)
        }
        ItemStatus.COMPLETED, ItemStatus.MISSED -> 0
    }

    private fun timerRemaining(item: ScheduledItem, wallNow: Long, elapsedNow: Long): Long =
        (timerDeadline(item, wallNow, elapsedNow) - elapsedNow).coerceAtLeast(0)

    private fun timerDeadline(item: ScheduledItem, wallNow: Long, elapsedNow: Long): Long =
        timerDeadlines.getOrPut(item.id) {
            elapsedNow + (item.targetEpochMs - wallNow).coerceAtLeast(0)
        }

    private fun store(item: ScheduledItem): ScheduledItem {
        items[item.id] = item
        if (item.status == ItemStatus.ACTIVE) activeItemIds.add(item.id) else activeItemIds.remove(item.id)
        return item
    }
}
