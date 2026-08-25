package com.onlinealarmkur.jetbrains.domain

import com.onlinealarmkur.jetbrains.persistence.StateCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AlarmEngineTest {
    private val clock = MutableClock(Instant.parse("2026-07-15T12:00:00Z"))
    private val elapsedTime = MutableElapsedTimeSource()
    private val engine = AlarmEngine(clock, elapsedTime)

    @Test
    fun `active item index follows every state transition`() {
        assertFalse(engine.hasActiveItems())

        val timer = engine.startTimer(60_000, "Focus", "timer")
        assertTrue(engine.hasActiveItems())

        assertTrue(engine.pause(timer.id))
        assertFalse(engine.hasActiveItems())

        assertTrue(engine.resume(timer.id))
        assertTrue(engine.hasActiveItems())

        elapsedTime.advanceMillis(60_000)
        assertEquals(ItemStatus.COMPLETED, engine.checkDue(0).single().item.status)
        assertFalse(engine.hasActiveItems())

        assertTrue(engine.restart(timer.id))
        assertTrue(engine.hasActiveItems())
        assertEquals(setOf(timer.id), engine.cancel(timer.id))
        assertFalse(engine.hasActiveItems())

        engine.restore(
            listOf(
                restoredItem("paused", ItemKind.TIMER, ItemStatus.PAUSED, remainingMs = 1_000),
                restoredItem("completed", ItemKind.ALARM, ItemStatus.COMPLETED),
            ),
        )
        assertFalse(engine.hasActiveItems())

        engine.scheduleAlarm(clock.millis() + 60_000, "Replacement", "paused")
        assertTrue(engine.hasActiveItems())
    }

    @Test
    fun `nearest active remaining uses wall time for alarms and elapsed time for timers`() {
        engine.scheduleAlarm(clock.millis() + 4 * 60 * 60_000L, "Meeting", "alarm")
        engine.startTimer(3 * 60 * 60_000L, "Focus", "timer")

        assertEquals(3 * 60 * 60_000L, engine.nearestActiveRemainingMs())

        clock.advanceMillis(3 * 60 * 60_000L + 30 * 60_000L)
        assertEquals(30 * 60_000L, engine.nearestActiveRemainingMs())

        clock.advanceMillis(-(3 * 60 * 60_000L + 30 * 60_000L))
        elapsedTime.advanceMillis(2 * 60 * 60_000L + 45 * 60_000L)
        assertEquals(15 * 60_000L, engine.nearestActiveRemainingMs())
    }

    @Test
    fun `nearest active remaining ignores every inactive status`() {
        engine.restore(
            listOf(
                restoredItem("paused", ItemKind.TIMER, ItemStatus.PAUSED, remainingMs = 1),
                restoredItem("completed", ItemKind.ALARM, ItemStatus.COMPLETED),
                restoredItem("missed", ItemKind.TIMER, ItemStatus.MISSED),
            ),
        )

        assertEquals(null, engine.nearestActiveRemainingMs())

        engine.scheduleAlarm(clock.millis() + 60_000, "Meeting", "active")
        assertEquals(60_000, engine.nearestActiveRemainingMs())
    }

    @Test
    fun `nearest active remaining samples each time source once without changing items`() {
        val now = Instant.parse("2026-07-15T12:00:00Z").toEpochMilli()
        var wallReads = 0
        var elapsedReads = 0
        val samplingClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(millis())
            override fun millis(): Long {
                wallReads++
                return now
            }
        }
        val samplingElapsedTime = ElapsedTimeSource {
            elapsedReads++
            20_000
        }
        val samplingEngine = AlarmEngine(samplingClock, samplingElapsedTime)
        samplingEngine.restore(
            listOf(
                ScheduledItem("alarm", ItemKind.ALARM, "Meeting", now, now + 3 * 60 * 60_000L),
                ScheduledItem("timer", ItemKind.TIMER, "Focus", now, now + 2 * 60 * 60_000L, 2 * 60 * 60_000L),
            ),
        )
        wallReads = 0
        elapsedReads = 0
        val before = samplingEngine.snapshot()

        assertEquals(2 * 60 * 60_000L, samplingEngine.nearestActiveRemainingMs())

        assertEquals(1, wallReads)
        assertEquals(1, elapsedReads)
        assertEquals(before, samplingEngine.snapshot())
    }

    @Test
    fun `nearest active remaining rebuilds a missing timer deadline instead of failing`() {
        val timer = engine.startTimer(60_000, "Focus", "timer")
        advanceBoth(20_000)
        dropStoredTimerDeadlines()

        assertEquals(40_000, engine.nearestActiveRemainingMs())
        assertEquals(40_000, engine.remainingMs(timer.id))
    }

    @Test
    fun `nearest active reports the soonest item and the remaining time live polling reads`() {
        engine.scheduleAlarm(clock.millis() + 4 * 60 * 60_000L, "Meeting", "alarm")
        val timer = engine.startTimer(3 * 60 * 60_000L, "Focus", "timer")

        assertEquals(timer to 3 * 60 * 60_000L, engine.nearestActive())
        assertEquals(engine.nearestActiveRemainingMs(), engine.nearestActive()?.second)

        clock.advanceMillis(3 * 60 * 60_000L + 30 * 60_000L)
        val nearest = engine.nearestActive()
        assertEquals("alarm", nearest?.first?.id)
        assertEquals(30 * 60_000L, nearest?.second)
        assertEquals(engine.nearestActiveRemainingMs(), nearest?.second)
    }

    @Test
    fun `nearest active ignores every inactive status`() {
        engine.restore(
            listOf(
                restoredItem("paused", ItemKind.TIMER, ItemStatus.PAUSED, remainingMs = 1),
                restoredItem("completed", ItemKind.ALARM, ItemStatus.COMPLETED),
                restoredItem("missed", ItemKind.TIMER, ItemStatus.MISSED),
            ),
        )

        assertEquals(null, engine.nearestActive())

        val alarm = engine.scheduleAlarm(clock.millis() + 60_000, "Meeting", "active")
        assertEquals(alarm to 60_000L, engine.nearestActive())
    }

    @Test
    fun `nearest active rebuilds a missing timer deadline instead of failing`() {
        val timer = engine.startTimer(60_000, "Focus", "timer")
        advanceBoth(20_000)
        dropStoredTimerDeadlines()

        assertEquals(timer to 40_000L, engine.nearestActive())
        assertEquals(40_000, engine.nearestActiveRemainingMs())
    }

    @Test
    fun `pause and resume use remaining milliseconds and a new absolute target`() {
        val timer = engine.startTimer(60_000, "", "timer")
        advanceBoth(20_000)
        assertTrue(engine.pause(timer.id))
        assertEquals(
            timer.copy(status = ItemStatus.PAUSED, remainingMs = 40_000),
            engine.snapshot().single(),
        )
        advanceBoth(30_000)
        assertTrue(engine.resume(timer.id))
        assertEquals(
            timer.copy(targetEpochMs = clock.millis() + 40_000),
            engine.snapshot().single(),
        )
    }

    @Test
    fun `active timer remaining and due state ignore wall clock corrections`() {
        val timer = engine.startTimer(60_000, "Tea", "timer")

        clock.advanceMillis(6 * 60 * 60_000L)
        assertEquals(60_000, engine.remainingMs(timer.id))
        assertTrue(engine.checkDue(0).isEmpty())

        clock.advanceMillis(-12 * 60 * 60_000L)
        assertEquals(60_000, engine.remainingMs(timer.id))
        assertTrue(engine.checkDue(0).isEmpty())

        elapsedTime.advanceMillis(59_999)
        assertEquals(1, engine.remainingMs(timer.id))
        assertTrue(engine.checkDue(0).isEmpty())

        elapsedTime.advanceMillis(1)
        val due = engine.checkDue(0).single()
        assertTrue(due.shouldAlert)
        assertEquals(clock.millis(), due.item.targetEpochMs)
        assertEquals(ItemStatus.COMPLETED, due.item.status)
    }

    @Test
    fun `alarms remain wall clock based`() {
        val alarm = engine.scheduleAlarm(clock.millis() + 60_000, "Wake", "alarm")

        elapsedTime.advanceMillis(60_000)
        assertEquals(60_000, engine.remainingMs(alarm.id))
        assertTrue(engine.checkDue(0).isEmpty())

        clock.advanceMillis(60_000)
        assertEquals(0, engine.remainingMs(alarm.id))
        assertTrue(engine.checkDue(0).single().shouldAlert)
    }

    @Test
    fun `pause and resume use elapsed remaining across wall corrections`() {
        val timer = engine.startTimer(60_000, "Tea", "timer")
        elapsedTime.advanceMillis(20_000)
        clock.advanceMillis(6 * 60 * 60_000L)

        assertTrue(engine.pause(timer.id))
        assertEquals(40_000, engine.snapshot().single().remainingMs)

        clock.advanceMillis(-12 * 60 * 60_000L)
        assertTrue(engine.resume(timer.id))
        assertEquals(clock.millis() + 40_000, engine.snapshot().single().targetEpochMs)
        clock.advanceMillis(24 * 60 * 60_000L)
        assertEquals(40_000, engine.remainingMs(timer.id))

        elapsedTime.advanceMillis(40_000)
        assertTrue(engine.checkDue(0).single().shouldAlert)
    }

    @Test
    fun `persistence snapshot rebases active timer without persisting elapsed readings`() {
        val timer = engine.startTimer(60_000, "Tea", "timer")
        elapsedTime.advanceMillis(20_000)
        clock.advanceMillis(-5 * 60_000L)

        assertEquals(timer, engine.snapshot().single())
        assertEquals(
            timer.copy(targetEpochMs = clock.millis() + 40_000),
            engine.persistenceSnapshot().single(),
        )
    }

    @Test
    fun `restored timer anchors to elapsed time and startup recovery handles suspend`() {
        val restored = restoredItem(
            id = "timer",
            kind = ItemKind.TIMER,
            durationMs = 60_000,
        )
        engine.restore(listOf(restored))

        clock.advanceMillis(20_000)
        assertEquals(60_000, engine.remainingMs(restored.id))
        assertTrue(engine.checkDueAfterStartup(5_000).isEmpty())
        assertEquals(40_000, engine.remainingMs(restored.id))

        clock.advanceMillis(60 * 60_000L)
        assertEquals(40_000, engine.remainingMs(restored.id))
        elapsedTime.advanceMillis(40_000)
        assertTrue(engine.checkDue(0).single().shouldAlert)
    }

    @Test
    fun `restored timer past its duration keeps a paused remainder the codec accepts`() {
        val restored = restoredItem(
            id = "timer",
            kind = ItemKind.TIMER,
            durationMs = 10_000,
        )
        engine.restore(listOf(restored))

        assertEquals(restored.durationMs, engine.remainingMs(restored.id))
        assertTrue(engine.pause(restored.id))

        val paused = engine.snapshot().single()
        assertEquals(ItemStatus.PAUSED, paused.status)
        assertEquals(restored.durationMs, paused.remainingMs)
        assertEquals(
            listOf(paused),
            StateCodec.decode(StateCodec.encode(AlarmTimerSettings(), listOf(paused))).items,
        )
    }

    @Test
    fun `startup recovery preserves the bounded restored timer deadline`() {
        listOf(
            Triple(11 * 60_000L, 60_000L, 60_000L),
            Triple(30_000L, 60_000L, 30_000L),
        ).forEachIndexed { index, (futureWallDeltaMs, durationMs, expectedRemainingMs) ->
            val restoredClock = MutableClock(Instant.parse("2026-07-15T12:00:00Z"))
            val restoredElapsedTime = MutableElapsedTimeSource()
            val restoredEngine = AlarmEngine(restoredClock, restoredElapsedTime)
            val restored = ScheduledItem(
                id = "timer-$index",
                kind = ItemKind.TIMER,
                label = "Timer $index",
                createdAtEpochMs = restoredClock.millis() - durationMs,
                targetEpochMs = restoredClock.millis() + futureWallDeltaMs,
                durationMs = durationMs,
            )

            restoredEngine.restore(listOf(restored))

            assertEquals(expectedRemainingMs, restoredEngine.remainingMs(restored.id))
            assertTrue(restoredEngine.checkDueAfterStartup(5_000).isEmpty())
            assertEquals(expectedRemainingMs, restoredEngine.remainingMs(restored.id))

            restoredElapsedTime.advanceMillis(expectedRemainingMs)
            assertTrue(restoredEngine.checkDue(0).single().shouldAlert)
        }
    }

    @Test
    fun `startup recovery marks timer missed when wall sleep exceeds grace`() {
        engine.restore(
            listOf(
                restoredItem(
                    id = "timer",
                    kind = ItemKind.TIMER,
                    durationMs = 10_000,
                ).copy(targetEpochMs = clock.millis() + 10_000),
            ),
        )
        clock.advanceMillis(70_000)

        val due = engine.checkDueAfterStartup(5_000).single()

        assertFalse(due.shouldAlert)
        assertEquals(ItemStatus.MISSED, due.item.status)
        assertFalse(due.item.deliveryPending)
    }

    @Test
    fun `startup recovery never applies wall correction to a newly created live timer`() {
        engine.restore(
            listOf(
                restoredItem(
                    id = "restored-alarm",
                    kind = ItemKind.ALARM,
                ).copy(targetEpochMs = clock.millis() + 2 * 60 * 60_000),
            ),
        )
        engine.startTimer(10_000, "Tea", "live-timer")
        clock.advanceMillis(70_000)

        assertTrue(engine.checkDueAfterStartup(5_000).isEmpty())
        assertEquals(10_000, engine.remainingMs("live-timer"))
        assertEquals(ItemStatus.ACTIVE, engine.snapshot().first { it.id == "live-timer" }.status)
    }

    @Test
    fun `only the first startup recovery rebases a restored timer from wall time`() {
        val restored = restoredItem(
            id = "timer",
            kind = ItemKind.TIMER,
            durationMs = 60_000,
        )
        engine.restore(listOf(restored))
        clock.advanceMillis(20_000)

        assertTrue(engine.checkDueAfterStartup(5_000).isEmpty())
        assertEquals(40_000, engine.remainingMs(restored.id))

        clock.advanceMillis(60 * 60_000)

        assertTrue(engine.checkDueAfterStartup(5_000).isEmpty())
        assertEquals(40_000, engine.remainingMs(restored.id))
        assertEquals(ItemStatus.ACTIVE, engine.snapshot().single().status)
    }

    @Test
    fun `activation preserves live timer deadline across wall corrections`() {
        listOf(10 * 60_000L, -10 * 60_000L).forEach { wallCorrection ->
            engine.startTimer(10_000, "Tea", "timer")

            clock.advanceMillis(wallCorrection)

            assertTrue(engine.checkDueAfterActivation(5_000).isEmpty())
            assertEquals(10_000, engine.remainingMs("timer"))
            assertEquals(ItemStatus.ACTIVE, engine.snapshot().single().status)
            engine.cancel("timer")
        }
    }

    @Test
    fun `activation checks alarm against corrected wall clock`() {
        engine.scheduleAlarm(clock.millis() + 1_000, "Meeting", "alarm")
        clock.advanceMillis(1_500)

        val due = engine.checkDueAfterActivation(500).single()

        assertTrue(due.shouldAlert)
        assertEquals(ItemStatus.COMPLETED, due.item.status)
        assertEquals(listOf(due), engine.recoverPendingAfterActivation())
        assertTrue(engine.acknowledgeDelivery(due.item.id, due.item.firedAtEpochMs))
        assertTrue(engine.recoverPendingAfterActivation().isEmpty())
    }

    @Test
    fun `restart establishes an elapsed deadline`() {
        val timer = restoredItem(
            id = "restart",
            kind = ItemKind.TIMER,
            status = ItemStatus.COMPLETED,
            durationMs = 60_000,
        )
        engine.restore(listOf(timer))

        assertTrue(engine.restart(timer.id))
        assertEquals(60_000, engine.remainingMs(timer.id))

        clock.advanceMillis(6 * 60 * 60_000L)
        assertEquals(60_000, engine.remainingMs(timer.id))
    }

    @Test
    fun `missing ids reject every mutable transition without changing the snapshot`() {
        engine.scheduleAlarm(clock.millis() + 60_000, "alarm", "alarm")
        engine.startTimer(60_000, "timer", "timer")

        assertRejected {
            engine.editAlarm(
                restoredItem(id = "missing", kind = ItemKind.ALARM, status = ItemStatus.ACTIVE),
                clock.millis() + 120_000,
                "edited",
            )
        }
        assertRejected { engine.pause("missing") }
        assertRejected { engine.resume("missing") }
        assertRejected { engine.restart("missing") }
    }

    @Test
    fun `kind restricted transitions reject alarms or timers without changing the snapshot`() {
        engine.scheduleAlarm(clock.millis() + 60_000, "alarm", "alarm")
        val timer = engine.startTimer(60_000, "timer", "timer")

        assertRejected { engine.editAlarm(timer, clock.millis() + 120_000, "edited") }
        assertRejected { engine.pause("alarm") }
        assertRejected { engine.resume("alarm") }
        assertRejected { engine.restart("alarm") }
    }

    @Test
    fun `edit alarm reactivates and clears fired time while preserving other fields`() {
        val alarm = restoredItem(
            id = "alarm",
            kind = ItemKind.ALARM,
            status = ItemStatus.COMPLETED,
            firedAtEpochMs = clock.millis() - 1_000,
        )
        engine.restore(listOf(alarm))
        val newTarget = clock.millis() + 120_000

        assertTrue(engine.editAlarm(alarm, newTarget, "  edited  "))

        assertEquals(
            alarm.copy(
                label = "edited",
                targetEpochMs = newTarget,
                status = ItemStatus.ACTIVE,
                firedAtEpochMs = 0,
            ),
            engine.snapshot().single(),
        )
    }

    @Test
    fun `edit alarm rejects equal and past targets without changing the snapshot`() {
        val alarm = restoredItem(
            id = "alarm",
            kind = ItemKind.ALARM,
            status = ItemStatus.MISSED,
        )
        engine.restore(listOf(alarm))

        listOf(clock.millis(), clock.millis() - 1).forEach { targetEpochMs ->
            assertRejected { engine.editAlarm(alarm, targetEpochMs, "edited") }
        }
    }

    @Test
    fun `edit alarm rejects a stale snapshot without overwriting a newer change`() {
        val original = engine.scheduleAlarm(clock.millis() + 60_000, "original", "alarm")
        val newerTarget = clock.millis() + 90_000
        assertTrue(engine.editAlarm(original, newerTarget, "newer"))

        assertFalse(engine.editAlarm(original, clock.millis() + 120_000, "stale"))

        assertEquals("newer", engine.item(original.id)?.label)
        assertEquals(newerTarget, engine.item(original.id)?.targetEpochMs)
    }

    @Test
    fun `pause one millisecond before target preserves positive remaining time`() {
        val timer = engine.startTimer(1_000, "", "timer")
        advanceBoth(999)

        assertTrue(engine.pause(timer.id))

        val paused = engine.snapshot().single()
        assertEquals(ItemStatus.PAUSED, paused.status)
        assertEquals(1, paused.remainingMs)
        assertEquals(timer.targetEpochMs, paused.targetEpochMs)
    }

    @Test
    fun `pause exactly at target is rejected and timer remains due`() {
        val timer = engine.startTimer(1_000, "", "timer")
        advanceBoth(1_000)

        assertFalse(engine.pause(timer.id))
        assertEquals(timer, engine.snapshot().single())

        val due = engine.checkDue(0).single()
        assertTrue(due.shouldAlert)
        assertEquals(ItemStatus.COMPLETED, due.item.status)
    }

    @Test
    fun `pause after target is rejected and timer remains due`() {
        val timer = engine.startTimer(1_000, "", "timer")
        advanceBoth(1_001)

        assertFalse(engine.pause(timer.id))
        assertEquals(timer, engine.snapshot().single())

        val due = engine.checkDue(1).single()
        assertTrue(due.shouldAlert)
        assertEquals(ItemStatus.COMPLETED, due.item.status)
    }

    @Test
    fun `pause rejects every inactive timer status without changing the snapshot`() {
        listOf(ItemStatus.PAUSED, ItemStatus.COMPLETED, ItemStatus.MISSED).forEach { status ->
            engine.restore(
                listOf(
                    restoredItem(
                        id = "timer-$status",
                        kind = ItemKind.TIMER,
                        status = status,
                        remainingMs = 30_000,
                    ),
                ),
            )

            assertRejected { engine.pause("timer-$status") }
        }
    }

    @Test
    fun `resume rejects invalid statuses and nonpositive remaining time without changing the snapshot`() {
        listOf(ItemStatus.ACTIVE, ItemStatus.COMPLETED, ItemStatus.MISSED).forEach { status ->
            engine.restore(
                listOf(
                    restoredItem(
                        id = "timer-$status",
                        kind = ItemKind.TIMER,
                        status = status,
                        remainingMs = 30_000,
                    ),
                ),
            )

            assertRejected { engine.resume("timer-$status") }
        }

        listOf(0L, -1L).forEach { remainingMs ->
            engine.restore(
                listOf(
                    restoredItem(
                        id = "timer-$remainingMs",
                        kind = ItemKind.TIMER,
                        status = ItemStatus.PAUSED,
                        remainingMs = remainingMs,
                    ),
                ),
            )

            assertRejected { engine.resume("timer-$remainingMs") }
        }
    }

    @Test
    fun `restart reactivates timers from every status using their original duration`() {
        ItemStatus.entries.forEach { status ->
            val timer = restoredItem(
                id = "timer-$status",
                kind = ItemKind.TIMER,
                status = status,
                durationMs = 60_000,
                remainingMs = 12_345,
                firedAtEpochMs = clock.millis() - 1_000,
            )
            engine.restore(listOf(timer))

            assertTrue(engine.restart(timer.id))

            assertEquals(
                timer.copy(
                    status = ItemStatus.ACTIVE,
                    targetEpochMs = clock.millis() + timer.durationMs,
                    remainingMs = 0,
                    firedAtEpochMs = 0,
                ),
                engine.snapshot().single(),
            )
        }
    }

    @Test
    fun `restart rejects restored timers below the minimum duration without changing the snapshot`() {
        listOf(-1L, 0L, 999L).forEach { durationMs ->
            engine.restore(
                listOf(
                    restoredItem(
                        id = "timer-$durationMs",
                        kind = ItemKind.TIMER,
                        durationMs = durationMs,
                    ),
                ),
            )

            assertRejected { engine.restart("timer-$durationMs") }
        }
    }

    @Test
    fun `restart clears pending delivery from a completed timer`() {
        val timer = restoredItem(
            id = "timer",
            kind = ItemKind.TIMER,
            status = ItemStatus.COMPLETED,
            durationMs = 60_000,
            firedAtEpochMs = clock.millis() - 1_000,
            deliveryPending = true,
        )
        engine.restore(listOf(timer))

        assertTrue(engine.restart(timer.id))

        assertEquals(
            timer.copy(
                status = ItemStatus.ACTIVE,
                targetEpochMs = clock.millis() + timer.durationMs,
                remainingMs = 0,
                firedAtEpochMs = 0,
                deliveryPending = false,
            ),
            engine.snapshot().single(),
        )
    }

    @Test
    fun `delayed check within grace fires once`() {
        engine.startTimer(10_000, "", "timer")
        advanceBoth(12_000)
        val first = engine.checkDue(5_000)
        assertEquals(1, first.size)
        assertTrue(first.single().shouldAlert)
        assertTrue(first.single().item.deliveryPending)
        assertTrue(engine.checkDue(5_000).isEmpty())
        assertTrue(engine.snapshot().single().deliveryPending)
    }

    @Test
    fun `laptop sleep beyond grace marks item missed`() {
        engine.startTimer(10_000, "", "timer")
        advanceBoth(70_000)
        val result = engine.checkDue(5_000).single()
        assertFalse(result.shouldAlert)
        assertEquals(ItemStatus.MISSED, result.item.status)
        assertFalse(result.item.deliveryPending)
    }

    @Test
    fun `delivery acknowledgment is atomic and rejects stale tokens`() {
        val timer = engine.startTimer(1_000, "Tea", "timer")
        advanceBoth(1_000)
        val pending = engine.checkDue(0).single().item
        assertEquals(
            timer.copy(
                status = ItemStatus.COMPLETED,
                firedAtEpochMs = clock.millis(),
                deliveryPending = true,
            ),
            pending,
        )

        assertRejected { engine.acknowledgeDelivery("missing", pending.firedAtEpochMs) }
        assertRejected { engine.acknowledgeDelivery(timer.id, pending.firedAtEpochMs - 1) }
        assertTrue(engine.acknowledgeDelivery(timer.id, pending.firedAtEpochMs))
        assertEquals(pending.copy(deliveryPending = false), engine.snapshot().single())
        assertRejected { engine.acknowledgeDelivery(timer.id, pending.firedAtEpochMs) }
    }

    @Test
    fun `activation recovery retries runtime pending across wall clock corrections`() {
        listOf(-12 * 60 * 60_000L, 12 * 60 * 60_000L).forEachIndexed { index, wallCorrection ->
            val timer = engine.startTimer(1_000, "Tea", "timer-$index")
            advanceBoth(1_000)
            val pending = engine.checkDue(0).single().item

            clock.advanceMillis(wallCorrection)

            assertEquals(listOf(DueItem(pending, true)), engine.recoverPendingAfterActivation())
            assertEquals(listOf(pending), engine.snapshot())
            assertTrue(engine.acknowledgeDelivery(timer.id, pending.firedAtEpochMs))
            assertTrue(engine.recoverPendingAfterActivation().isEmpty())
            engine.cancel(timer.id)
        }
    }

    @Test
    fun `restored pending waits for startup and accepts negative through exact grace`() {
        val gracePeriodMs = 5_000L
        val wallCorrectionMs = -60_000L
        val startupWallNow = clock.millis() + wallCorrectionMs
        val negative = restoredItem(
            id = "negative",
            kind = ItemKind.ALARM,
            status = ItemStatus.COMPLETED,
            firedAtEpochMs = clock.millis() - 500,
            deliveryPending = true,
        ).copy(targetEpochMs = clock.millis() - 500)
        val boundary = restoredItem(
            id = "boundary",
            kind = ItemKind.ALARM,
            status = ItemStatus.COMPLETED,
            firedAtEpochMs = startupWallNow - gracePeriodMs,
            deliveryPending = true,
        ).copy(targetEpochMs = startupWallNow - gracePeriodMs)
        val stale = restoredItem(
            id = "stale",
            kind = ItemKind.ALARM,
            status = ItemStatus.COMPLETED,
            firedAtEpochMs = startupWallNow - gracePeriodMs - 1,
            deliveryPending = true,
        ).copy(targetEpochMs = startupWallNow - gracePeriodMs - 1)
        engine.restore(listOf(negative, boundary, stale))
        clock.advanceMillis(wallCorrectionMs)

        assertTrue(engine.recoverPendingAfterActivation().isEmpty())

        val missed = stale.copy(
            status = ItemStatus.MISSED,
            firedAtEpochMs = 0,
            deliveryPending = false,
        )
        assertEquals(
            listOf(
                DueItem(negative, true),
                DueItem(boundary, true),
                DueItem(missed, false),
            ),
            engine.recoverPendingAfterStartup(gracePeriodMs),
        )
        assertEquals(listOf(negative, boundary, missed), engine.snapshot())
        assertEquals(
            listOf(DueItem(negative, true), DueItem(boundary, true)),
            engine.recoverPendingAfterActivation(),
        )
    }

    @Test
    fun `startup recovery does not reclassify pending created by startup due check`() {
        val timer = restoredItem(
            id = "timer",
            kind = ItemKind.TIMER,
            durationMs = 60_000,
        )
        engine.restore(listOf(timer))
        clock.advanceMillis(65_000)

        val due = engine.checkDueAfterStartup(5_000).single()
        clock.advanceMillis(1)

        assertTrue(due.shouldAlert)
        assertEquals(listOf(due), engine.recoverPendingAfterStartup(5_000))
    }

    @Test
    fun `multiple close items fire in one check`() {
        engine.startTimer(10_000, "one", "one")
        engine.startTimer(11_000, "two", "two")
        advanceBoth(12_000)
        assertEquals(setOf("one", "two"), engine.checkDue(5_000).map { it.item.id }.toSet())
    }

    @Test
    fun `cancel reports only the removed id`() {
        engine.startTimer(10_000, "", "timer")

        assertEquals(setOf("timer"), engine.cancel("timer"))
        assertTrue(engine.cancel("timer").isEmpty())
        assertTrue(engine.snapshot().isEmpty())
    }

    @Test
    fun `cancel removes pending delivery`() {
        engine.restore(
            listOf(
                restoredItem(
                    id = "pending",
                    kind = ItemKind.ALARM,
                    status = ItemStatus.COMPLETED,
                    firedAtEpochMs = clock.millis(),
                    deliveryPending = true,
                ),
            ),
        )

        assertEquals(setOf("pending"), engine.cancel("pending"))
        assertTrue(engine.snapshot().isEmpty())
    }

    @Test
    fun `clear completed reports removed ids and preserves active items`() {
        engine.startTimer(1_000, "", "missed")
        engine.startTimer(10_000, "", "completed")
        advanceBoth(20_000)
        engine.checkDue(15_000)
        engine.startTimer(10_000, "", "active")

        assertEquals(setOf("missed", "completed"), engine.clearCompleted())
        assertEquals(listOf("active"), engine.snapshot().map { it.id })
        assertTrue(engine.clearCompleted().isEmpty())
    }

    @Test
    fun `create and edit operations apply one label normalization contract`() {
        val alarm = engine.scheduleAlarm(
            clock.millis() + 60_000,
            "  ${"a".repeat(199)}  ",
            "alarm",
        )
        val timer = engine.startTimer(60_000, "b".repeat(200), "timer")

        assertTrue(engine.editAlarm(alarm, clock.millis() + 120_000, "c".repeat(201)))

        val items = engine.snapshot().associateBy { it.id }
        assertEquals("c".repeat(MAX_LABEL_LENGTH), items.getValue(alarm.id).label)
        assertEquals("b".repeat(MAX_LABEL_LENGTH), timer.label)
        assertEquals(200, items.getValue(alarm.id).label.length)
        assertEquals(200, items.getValue(timer.id).label.length)
    }

    @Test
    fun `one hundred ninety-nine character label is trimmed without truncation`() {
        val label = "x".repeat(199)

        val alarm = engine.scheduleAlarm(clock.millis() + 60_000, "  $label  ", "alarm")

        assertEquals(label, alarm.label)
        assertEquals(199, alarm.label.length)
    }

    @Test
    fun `surrogate pair fitting exactly within label limit is retained`() {
        val label = "x".repeat(198) + "\uD83D\uDE00"

        val alarm = engine.scheduleAlarm(clock.millis() + 60_000, label, "alarm")

        assertEquals(label, alarm.label)
        assertEquals(MAX_LABEL_LENGTH, alarm.label.length)
    }

    @Test
    fun `surrogate pair straddling label limit is dropped whole`() {
        val prefix = "x".repeat(199)

        val alarm = engine.scheduleAlarm(clock.millis() + 60_000, prefix + "\uD83D\uDE00", "alarm")

        assertEquals(prefix, alarm.label)
        assertEquals(199, alarm.label.length)
        assertTrue(alarm.label.none(Character::isSurrogate))
    }

    private fun assertRejected(operation: () -> Boolean) {
        val before = engine.snapshot()

        assertFalse(operation())
        assertEquals(before, engine.snapshot())
    }

    // The engine keeps monotonic deadlines in a private map that no public call can empty while a
    // timer stays active. Emptying it here is the only way to prove the live-polling read rebuilds
    // a missing entry from the persisted wall target instead of failing on it.
    private fun dropStoredTimerDeadlines() {
        val deadlines = AlarmEngine::class.java.getDeclaredField("timerDeadlines")
        deadlines.isAccessible = true
        (deadlines.get(engine) as MutableMap<*, *>).clear()
    }

    private fun advanceBoth(millis: Long) {
        clock.advanceMillis(millis)
        elapsedTime.advanceMillis(millis)
    }

    private fun restoredItem(
        id: String,
        kind: ItemKind,
        status: ItemStatus = ItemStatus.ACTIVE,
        durationMs: Long = 0,
        remainingMs: Long = 0,
        firedAtEpochMs: Long = 0,
        deliveryPending: Boolean = false,
    ) = ScheduledItem(
        id = id,
        kind = kind,
        label = "label-$id",
        createdAtEpochMs = clock.millis() - 120_000,
        targetEpochMs = clock.millis() + 60_000,
        durationMs = durationMs,
        remainingMs = remainingMs,
        status = status,
        firedAtEpochMs = firedAtEpochMs,
        deliveryPending = deliveryPending,
    )
}
