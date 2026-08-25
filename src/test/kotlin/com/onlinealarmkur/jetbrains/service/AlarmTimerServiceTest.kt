package com.onlinealarmkur.jetbrains.service

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.onlinealarmkur.jetbrains.domain.AlarmTimerSettings
import com.onlinealarmkur.jetbrains.domain.AlarmTimerSettingsPolicy
import com.onlinealarmkur.jetbrains.domain.ItemKind
import com.onlinealarmkur.jetbrains.domain.ItemStatus
import com.onlinealarmkur.jetbrains.domain.MutableClock
import com.onlinealarmkur.jetbrains.domain.MutableElapsedTimeSource
import com.onlinealarmkur.jetbrains.domain.ScheduledItem
import com.onlinealarmkur.jetbrains.persistence.CURRENT_SCHEMA_VERSION
import com.onlinealarmkur.jetbrains.persistence.PersistedItem
import com.onlinealarmkur.jetbrains.persistence.PersistedState
import com.onlinealarmkur.jetbrains.persistence.StateCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AlarmTimerServiceTest {
    @Test
    fun `live polling cadence has exact one hour boundary and no idle task`() {
        assertEquals(null, LivePollingCadence.intervalSeconds(null))
        assertEquals(1L, LivePollingCadence.intervalSeconds(3_599_999))
        assertEquals(1L, LivePollingCadence.intervalSeconds(3_600_000))
        assertEquals(60L, LivePollingCadence.intervalSeconds(3_600_001))
    }

    @Test
    fun `runtime settings sanitization clamps outside values and preserves exact boundaries`() {
        assertEquals(1, AlarmTimerSettingsPolicy.MIN_VOLUME_PERCENT)
        val fixture = Fixture()
        val outside = AlarmTimerSettings(
            defaultTimerMs = Long.MIN_VALUE,
            use24HourTime = false,
            showStatusBarWidget = false,
            overdueGracePeriodMs = Long.MAX_VALUE,
            soundEnabled = false,
            volumePercent = Int.MIN_VALUE,
        )

        fixture.service.updateSettings(outside)

        assertEquals(
            outside.copy(
                defaultTimerMs = AlarmTimerSettingsPolicy.MIN_DURATION_MS,
                overdueGracePeriodMs = AlarmTimerSettingsPolicy.MAX_OVERDUE_GRACE_PERIOD_MS,
                volumePercent = AlarmTimerSettingsPolicy.MIN_VOLUME_PERCENT,
            ),
            fixture.service.settings(),
        )

        val boundaries = AlarmTimerSettings(
            defaultTimerMs = AlarmTimerSettingsPolicy.MAX_DURATION_MS,
            overdueGracePeriodMs = AlarmTimerSettingsPolicy.MIN_OVERDUE_GRACE_PERIOD_MS,
            volumePercent = AlarmTimerSettingsPolicy.MAX_VOLUME_PERCENT,
        )

        fixture.service.updateSettings(boundaries)

        assertEquals(boundaries, fixture.service.settings())
    }

    @Test
    fun `idle tick does not queue delivery even for interested listener`() {
        val fixture = Fixture()
        val parent = Disposer.newDisposable()
        val events = mutableListOf<AlarmTimerEvent>()
        fixture.service.addListener(parent, wantsClockPulses = { true }) { events += it }

        assertTrue(fixture.scheduler.periodicTasks.isEmpty())
        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
        fixture.scheduler.runPeriodic()

        assertEquals(0, fixture.stateSaver.saveCount)
        assertTrue(fixture.notifier.calls.isEmpty())
        assertTrue(fixture.sound.plays.isEmpty())
        assertEquals(0, fixture.ui.pendingCount)
        assertTrue(events.isEmpty())
        Disposer.dispose(parent)
    }

    @Test
    fun `live polling follows active item transitions without duplicate schedules`() {
        val fixture = Fixture()

        val first = fixture.service.startTimer(10_000, "First")
        assertEquals(1, fixture.scheduler.periodicTasks.size)
        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)
        assertEquals(1L, fixture.scheduler.activePeriodicTasks.single().initialDelay)
        assertEquals(1L, fixture.scheduler.activePeriodicTasks.single().delay)
        assertEquals(TimeUnit.SECONDS, fixture.scheduler.activePeriodicTasks.single().unit)

        val second = fixture.service.startTimer(20_000, "Second")
        assertEquals(1, fixture.scheduler.periodicTasks.size)
        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)

        assertTrue(fixture.service.pause(first.id))
        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)
        assertTrue(fixture.service.cancel(second.id))
        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
        assertEquals(1, fixture.scheduler.periodicCancellationCount)

        assertTrue(fixture.service.resume(first.id))
        assertEquals(2, fixture.scheduler.periodicTasks.size)
        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)
        assertTrue(fixture.service.pause(first.id))
        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
        assertEquals(2, fixture.scheduler.periodicCancellationCount)
        assertTrue(fixture.service.resume(first.id))
        assertEquals(3, fixture.scheduler.periodicTasks.size)
        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)
        assertTrue(fixture.service.restart(first.id))
        assertEquals(3, fixture.scheduler.periodicTasks.size)

        fixture.advanceMillis(10_000)
        fixture.scheduler.runPeriodic()
        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
        assertEquals(3, fixture.scheduler.periodicCancellationCount)

        fixture.service.clearCompleted()
        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
        val alarm = fixture.service.scheduleAlarm(fixture.clock.millis() + 10_000, "Meeting")
        assertEquals(4, fixture.scheduler.periodicTasks.size)
        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)
        assertTrue(fixture.service.cancel(alarm.id))
        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
        assertEquals(4, fixture.scheduler.periodicCancellationCount)
    }

    @Test
    fun `week away alarm uses minute cadence and ten minute timer replaces it with second cadence`() {
        val fixture = Fixture()
        val alarm = fixture.service.scheduleAlarm(fixture.clock.millis() + 7 * 24 * 60 * 60_000L, "Trip")

        val farTask = fixture.scheduler.activePeriodicTasks.single()
        assertEquals(60L, farTask.initialDelay)
        assertEquals(60L, farTask.delay)
        assertEquals(TimeUnit.SECONDS, farTask.unit)

        val timer = fixture.service.startTimer(10 * 60_000L, "Tea")

        assertTrue(farTask.isCancelled)
        assertEquals(1, fixture.scheduler.periodicCancellationCount)
        assertEquals(2, fixture.scheduler.periodicTasks.size)
        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)
        assertEquals(1L, fixture.scheduler.activePeriodicTasks.single().delay)

        assertTrue(fixture.service.cancel(timer.id))
        assertEquals(60L, fixture.scheduler.activePeriodicTasks.single().delay)
        assertTrue(fixture.service.cancel(alarm.id))
        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
    }

    @Test
    fun `far polling crosses the one hour threshold and rearms exactly once`() {
        val fixture = Fixture()
        fixture.service.scheduleAlarm(fixture.clock.millis() + 3_660_001, "Meeting")
        assertEquals(60L, fixture.scheduler.activePeriodicTasks.single().delay)

        fixture.advanceMillis(60_001)
        fixture.scheduler.runPeriodic()

        assertEquals(2, fixture.scheduler.periodicTasks.size)
        assertEquals(1, fixture.scheduler.periodicCancellationCount)
        assertEquals(1L, fixture.scheduler.activePeriodicTasks.single().delay)

        fixture.scheduler.runPeriodic()

        assertEquals(2, fixture.scheduler.periodicTasks.size)
        assertEquals(1, fixture.scheduler.periodicCancellationCount)
        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)
    }

    @Test
    fun `far alarm cadence observes wall corrections on the next periodic check`() {
        val forward = Fixture()
        forward.service.scheduleAlarm(forward.clock.millis() + 2 * 60 * 60_000L, "Forward")
        val forwardFarTask = forward.scheduler.activePeriodicTasks.single()
        forward.clock.advanceMillis(90 * 60_000L)

        assertEquals(60L, forwardFarTask.delay)
        forward.scheduler.runPeriodic()
        assertTrue(forwardFarTask.isCancelled)
        assertEquals(1L, forward.scheduler.activePeriodicTasks.single().delay)

        val backward = Fixture()
        val alarm = backward.service.scheduleAlarm(backward.clock.millis() + 2 * 60 * 60_000L, "Backward")
        val backwardFarTask = backward.scheduler.activePeriodicTasks.single()
        backward.clock.advanceMillis(-90 * 60_000L)

        backward.scheduler.runPeriodic()

        assertEquals(3 * 60 * 60_000L + 30 * 60_000L, backward.service.remainingMs(alarm.id))
        assertEquals(backwardFarTask, backward.scheduler.activePeriodicTasks.single())
        assertEquals(0, backward.scheduler.periodicCancellationCount)
    }

    @Test
    fun `far live timer cadence and remaining ignore wall corrections`() {
        listOf(6 * 60 * 60_000L, -6 * 60 * 60_000L).forEach { wallCorrection ->
            val fixture = Fixture()
            val timer = fixture.service.startTimer(2 * 60 * 60_000L, "Focus")
            val farTask = fixture.scheduler.activePeriodicTasks.single()

            fixture.clock.advanceMillis(wallCorrection)
            fixture.scheduler.runPeriodic()

            assertEquals(2 * 60 * 60_000L, fixture.service.remainingMs(timer.id))
            assertEquals(farTask, fixture.scheduler.activePeriodicTasks.single())
            assertEquals(60L, farTask.delay)
            assertEquals(0, fixture.scheduler.periodicCancellationCount)
        }
    }

    @Test
    fun `pause resume cancel and completion reconcile to the remaining cadence tier`() {
        val fixture = Fixture()
        val alarm = fixture.service.scheduleAlarm(fixture.clock.millis() + 7 * 24 * 60 * 60_000L, "Trip")
        val timer = fixture.service.startTimer(10 * 60_000L, "Tea")
        assertEquals(1L, fixture.scheduler.activePeriodicTasks.single().delay)

        assertTrue(fixture.service.pause(timer.id))
        assertEquals(60L, fixture.scheduler.activePeriodicTasks.single().delay)
        assertTrue(fixture.service.resume(timer.id))
        assertEquals(1L, fixture.scheduler.activePeriodicTasks.single().delay)
        assertTrue(fixture.service.cancel(timer.id))
        assertEquals(60L, fixture.scheduler.activePeriodicTasks.single().delay)

        val shortTimer = fixture.service.startTimer(1_000, "Short")
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()

        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single { it.id == shortTimer.id }.status)
        assertEquals(60L, fixture.scheduler.activePeriodicTasks.single().delay)
        assertTrue(fixture.service.cancel(alarm.id))
        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
    }

    @Test
    fun `periodic replacement failure clears ownership and later transition retries`() {
        val fixture = Fixture()
        fixture.service.scheduleAlarm(fixture.clock.millis() + 2 * 60 * 60_000L, "Meeting")
        val farTask = fixture.scheduler.activePeriodicTasks.single()
        fixture.scheduler.nextPeriodicFailure = RejectedExecutionException("Rejected")

        val timer = fixture.service.startTimer(10 * 60_000L, "Tea")

        assertTrue(farTask.isCancelled)
        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
        assertEquals(1, fixture.scheduler.periodicCancellationCount)

        assertTrue(fixture.service.restart(timer.id))

        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)
        assertEquals(1L, fixture.scheduler.activePeriodicTasks.single().delay)
    }

    @Test
    fun `periodic cancellation failure does not prevent replacement polling`() {
        val fixture = Fixture()
        fixture.service.scheduleAlarm(fixture.clock.millis() + 2 * 60 * 60_000L, "Meeting")
        val farTask = fixture.scheduler.activePeriodicTasks.single()
        fixture.scheduler.nextPeriodicCancelFailure = IllegalStateException("Cancellation failed")

        fixture.service.startTimer(10 * 60_000L, "Tea")

        assertTrue(farTask.isCancelled)
        assertEquals(1, fixture.scheduler.periodicCancellationCount)
        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)
        assertEquals(1L, fixture.scheduler.activePeriodicTasks.single().delay)
    }

    @Test
    fun `periodic replacement propagates cancellation only after arming its successor`() {
        val fixture = Fixture()
        fixture.service.scheduleAlarm(fixture.clock.millis() + 2 * 60 * 60_000L, "Meeting")
        val farTask = fixture.scheduler.activePeriodicTasks.single()
        val cancellation = ProcessCanceledException()
        fixture.scheduler.nextPeriodicCancelFailure = cancellation

        val thrown = assertThrows(ProcessCanceledException::class.java) {
            fixture.service.startTimer(10 * 60_000L, "Tea")
        }

        assertSame(cancellation, thrown)
        assertTrue(farTask.isCancelled)
        assertEquals(1, fixture.scheduler.periodicCancellationCount)
        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)
        assertEquals(1L, fixture.scheduler.activePeriodicTasks.single().delay)
    }

    @Test
    fun `live poll that fails inside its own body keeps polling alive`() {
        val fixture = Fixture()
        val parent = Disposer.newDisposable()
        fixture.service.addListener(parent, wantsClockPulses = { true }) { }
        fixture.service.startTimer(2_000, "Tea")
        fixture.ui.drain()
        val poller = fixture.scheduler.activePeriodicTasks.single()
        fixture.advanceMillis(500)
        fixture.ui.failure = IllegalStateException("Queue failed")

        fixture.scheduler.runPeriodic()

        assertEquals(listOf(poller), fixture.scheduler.activePeriodicTasks)
        fixture.advanceMillis(1_500)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()

        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertEquals(1, fixture.notifier.calls.size)
        Disposer.dispose(parent)
    }

    @Test
    fun `dead live polling task is replaced instead of trusted`() {
        val fixture = Fixture()
        fixture.service.startTimer(60_000, "Tea")
        fixture.ui.drain()
        val dead = fixture.scheduler.activePeriodicTasks.single()

        dead.kill()

        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
        assertFalse(dead.isCancelled)

        fixture.service.startTimer(30_000, "Second")
        fixture.ui.drain()

        assertEquals(2, fixture.scheduler.periodicTasks.size)
        val revived = fixture.scheduler.activePeriodicTasks.single()
        assertTrue(revived !== dead)
        assertEquals(1L, revived.delay)
        assertEquals(1, fixture.scheduler.periodicCancellationCount)

        fixture.advanceMillis(30_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()

        assertEquals(1, fixture.notifier.calls.size)
    }

    @Test
    fun `unexpected immediate check scheduling failure does not strand later checks`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.nextOneShotFailure = IllegalStateException("Scheduler unavailable")

        fixture.service.requestActivationCheck()

        assertTrue(fixture.scheduler.oneShots.isEmpty())
        assertEquals(ItemStatus.ACTIVE, fixture.service.items().single().status)

        fixture.service.requestActivationCheck()

        assertEquals(1, fixture.scheduler.oneShots.size)
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        fixture.ui.drain()

        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertEquals(1, fixture.notifier.calls.size)
    }

    @Test
    fun `cancellation during an immediate lifecycle check restores its latch and retries`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        val cancellation = ProcessCanceledException()
        fixture.ui.failure = cancellation

        fixture.service.requestActivationCheck()
        val thrown = assertThrows(ProcessCanceledException::class.java) {
            fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        }

        assertSame(cancellation, thrown)
        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertTrue(fixture.service.items().single().deliveryPending)
        assertEquals(1, fixture.scheduler.oneShots.count { it.delay == 0L && it.unit == TimeUnit.MILLISECONDS })

        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        fixture.ui.drain()

        assertEquals(1, fixture.notifier.calls.size)
        assertFalse(fixture.service.items().single().deliveryPending)
    }

    @Test
    fun `restored active items arm polling while idle lifecycle checks stay one shot`() {
        val fixture = Fixture()
        fixture.service.requestActivationCheck()

        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
        assertEquals(1, fixture.scheduler.oneShots.size)
        fixture.scheduler.oneShots.single().task()
        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())

        val active = ScheduledItem(
            id = "restored",
            kind = ItemKind.TIMER,
            label = "Tea",
            createdAtEpochMs = fixture.clock.millis(),
            targetEpochMs = fixture.clock.millis() + 60_000,
            durationMs = 60_000,
        )
        fixture.service.loadState(StateCodec.encode(fixture.service.settings(), listOf(active)))

        assertEquals(1, fixture.scheduler.periodicTasks.size)
        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)

        fixture.service.loadState(StateCodec.encode(fixture.service.settings(), emptyList()))
        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
        assertEquals(1, fixture.scheduler.periodicCancellationCount)
    }

    @Test
    fun `disposal cancels active periodic polling exactly once`() {
        val fixture = Fixture()
        fixture.service.startTimer(60_000, "Tea")
        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)

        fixture.service.dispose()
        fixture.service.dispose()

        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
        assertEquals(1, fixture.scheduler.periodicCancellationCount)
        assertEquals(1, fixture.scheduler.shutdownCount)
        fixture.scheduler.runPeriodic()
        assertEquals(0, fixture.scheduler.postShutdownScheduleCount)
    }

    @Test
    fun `due item is saved before notification and sound delivery`() {
        val fixture = Fixture()
        val item = fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)

        fixture.scheduler.runPeriodic()

        assertEquals(1, fixture.stateSaver.saveCount)
        assertTrue(fixture.notifier.calls.isEmpty())
        assertTrue(fixture.sound.plays.isEmpty())
        assertTrue(fixture.service.items().single().deliveryPending)
        fixture.ui.drain()
        assertEquals(listOf(item.id), fixture.notifier.calls.map { it.item.id })
        assertTrue(fixture.notifier.calls.single().handle.delivered)
        assertEquals(listOf(70), fixture.sound.plays)
        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertFalse(fixture.service.items().single().deliveryPending)
        assertEquals(1, fixture.stateSaver.saveCount)
        assertEquals(listOf(false), fixture.stateSaver.dispatchThreadValues)
        assertEquals(1, fixture.scheduler.oneShots.count { it.delay == 0L && it.unit == TimeUnit.MILLISECONDS })

        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(2, fixture.stateSaver.saveCount)
        assertEquals(listOf(false, false), fixture.stateSaver.dispatchThreadValues)
        assertEquals(listOf(true), fixture.stateSaver.deliveryPendingSnapshots.first())
        assertEquals(listOf(false), fixture.stateSaver.deliveryPendingSnapshots.last())
        assertEquals(listOf(30L), fixture.scheduler.oneShots.map { it.delay })
        assertEquals(listOf(TimeUnit.SECONDS), fixture.scheduler.oneShots.map { it.unit })
    }

    @Test
    fun `delivery burst queues one background save containing every acknowledgement`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "First")
        fixture.service.startTimer(1_000, "Second")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)

        fixture.scheduler.runPeriodic()

        assertEquals(1, fixture.stateSaver.saveCount)
        assertEquals(listOf(true, true), fixture.stateSaver.deliveryPendingSnapshots.single())
        fixture.ui.drain()

        assertEquals(2, fixture.notifier.calls.size)
        assertTrue(fixture.service.items().none { it.deliveryPending })
        assertEquals(1, fixture.stateSaver.saveCount)
        assertEquals(1, fixture.scheduler.oneShots.count { it.delay == 0L && it.unit == TimeUnit.MILLISECONDS })
        assertEquals(listOf(false), fixture.stateSaver.dispatchThreadValues)

        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(2, fixture.stateSaver.saveCount)
        assertEquals(listOf(false, false), fixture.stateSaver.dispatchThreadValues)
        assertEquals(listOf(false, false), fixture.stateSaver.deliveryPendingSnapshots.last())
    }

    @Test
    fun `acknowledgement save failure leaves delivered alert and sound intact`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        fixture.stateSaver.failure = IllegalStateException("Persistence unavailable")

        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        val notification = fixture.notifier.calls.single()
        assertTrue(notification.handle.delivered)
        assertEquals(0, notification.handle.expireCount)
        assertEquals(listOf(70), fixture.sound.plays)
        assertFalse(fixture.service.items().single().deliveryPending)
        assertEquals(2, fixture.stateSaver.saveCount)
    }

    @Test
    fun `cancellation during acknowledgement persistence restores its latch and retries`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        val cancellation = ProcessCanceledException()
        fixture.stateSaver.failure = cancellation

        val thrown = assertThrows(ProcessCanceledException::class.java) {
            fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        }

        assertSame(cancellation, thrown)
        assertEquals(1, fixture.scheduler.oneShots.count { it.delay == 0L && it.unit == TimeUnit.MILLISECONDS })
        fixture.stateSaver.failure = null
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(3, fixture.stateSaver.saveCount)
        assertEquals(listOf(false), fixture.stateSaver.deliveryPendingSnapshots.last())
        assertTrue(fixture.scheduler.oneShots.none { it.delay == 0L && it.unit == TimeUnit.MILLISECONDS })
    }

    @Test
    fun `acknowledgement arriving during save triggers one follow-up save`() {
        val acknowledgementSaveEntered = CountDownLatch(1)
        val releaseAcknowledgementSave = CountDownLatch(1)
        val saveAttempts = AtomicInteger()
        val stateSaver = FakeStateSaver {
            if (saveAttempts.incrementAndGet() == 2) {
                acknowledgementSaveEntered.countDown()
                check(releaseAcknowledgementSave.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release acknowledgement persistence."
                }
            }
        }
        val fixture = Fixture(stateSaver = stateSaver)
        fixture.service.startTimer(1_000, "First")
        fixture.service.startTimer(1_000, "Second")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()

        assertTrue(fixture.ui.drainOne())
        val save = ConcurrentTask("blocked-acknowledgement-save") {
            fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        }
        await(acknowledgementSaveEntered, "acknowledgement save to start")

        assertTrue(fixture.ui.drainOne())
        assertTrue(fixture.service.items().none { it.deliveryPending })
        releaseAcknowledgementSave.countDown()
        save.await()

        assertEquals(3, fixture.stateSaver.saveCount)
        assertEquals(3, saveAttempts.get())
        assertEquals(listOf(false, false), fixture.stateSaver.deliveryPendingSnapshots.last())
        assertEquals(0, fixture.scheduler.oneShots.count { it.delay == 0L && it.unit == TimeUnit.MILLISECONDS })
    }

    @Test
    fun `disposal before queued acknowledgement save prevents persistence`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()

        assertEquals(1, fixture.stateSaver.saveCount)
        assertFalse(fixture.service.items().single().deliveryPending)
        fixture.service.dispose()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(1, fixture.stateSaver.saveCount)
        assertEquals(0, fixture.scheduler.postShutdownScheduleCount)
    }

    @Test
    fun `disposal during acknowledgement save does not wait or run a dirty follow-up`() {
        val acknowledgementSaveEntered = CountDownLatch(1)
        val releaseAcknowledgementSave = CountDownLatch(1)
        val saveAttempts = AtomicInteger()
        val stateSaver = FakeStateSaver {
            if (saveAttempts.incrementAndGet() == 2) {
                acknowledgementSaveEntered.countDown()
                check(releaseAcknowledgementSave.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release acknowledgement persistence."
                }
            }
        }
        val fixture = Fixture(stateSaver = stateSaver)
        fixture.service.startTimer(1_000, "First")
        fixture.service.startTimer(1_000, "Second")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        assertTrue(fixture.ui.drainOne())
        val save = ConcurrentTask("acknowledgement-save-during-disposal") {
            fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        }
        await(acknowledgementSaveEntered, "acknowledgement save to start")
        assertTrue(fixture.ui.drainOne())

        val dispose = ConcurrentTask("dispose-during-acknowledgement-save") { fixture.service.dispose() }
        try {
            dispose.await()
        } finally {
            releaseAcknowledgementSave.countDown()
        }
        save.await()

        assertEquals(2, fixture.stateSaver.saveCount)
        assertEquals(2, saveAttempts.get())
        assertTrue(fixture.scheduler.isShutdown)
        assertEquals(0, fixture.scheduler.postShutdownScheduleCount)
        assertEquals(0, fixture.scheduler.oneShots.count { it.delay == 0L && it.unit == TimeUnit.MILLISECONDS })
    }

    @Test
    fun `rejected acknowledgement schedule keeps persisted pending fallback`() {
        val fixture = Fixture()
        fixture.service.updateSettings(fixture.service.settings().copy(soundEnabled = false))
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.scheduler.nextOneShotFailure = RejectedExecutionException("Scheduler unavailable")

        fixture.ui.drain()

        assertTrue(fixture.notifier.calls.single().handle.delivered)
        assertFalse(fixture.service.items().single().deliveryPending)
        assertEquals(1, fixture.stateSaver.saveCount)
        assertEquals(listOf(true), fixture.stateSaver.deliveryPendingSnapshots.single())
        assertTrue(fixture.scheduler.oneShots.isEmpty())
    }

    @Test
    fun `zero grace live polling slightly late alerts exactly once`() {
        val fixture = Fixture()
        fixture.service.updateSettings(fixture.service.settings().copy(overdueGracePeriodMs = 0))
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(2_100)

        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(2, fixture.stateSaver.saveCount)
        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertEquals(1, fixture.notifier.calls.size)
        assertEquals(listOf(70), fixture.sound.plays)

        fixture.scheduler.runPeriodic()
        fixture.ui.drain()

        assertEquals(2, fixture.stateSaver.saveCount)
        assertEquals(1, fixture.notifier.calls.size)
        assertEquals(listOf(70), fixture.sound.plays)
    }

    @Test
    fun `substantially late periodic work is saved as missed without alerting`() {
        val fixture = Fixture()
        fixture.service.updateSettings(fixture.service.settings().copy(overdueGracePeriodMs = 0))
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(10_000)

        fixture.scheduler.runPeriodic()
        fixture.ui.drain()

        assertEquals(1, fixture.stateSaver.saveCount)
        assertEquals(ItemStatus.MISSED, fixture.service.items().single().status)
        assertTrue(fixture.notifier.calls.isEmpty())
        assertTrue(fixture.sound.plays.isEmpty())
        assertTrue(fixture.scheduler.oneShots.isEmpty())
    }

    @Test
    fun `periodic work beyond live tolerance alerts within configured grace`() {
        val fixture = Fixture()
        fixture.service.updateSettings(fixture.service.settings().copy(overdueGracePeriodMs = 5_000))
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(4_000)

        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(2, fixture.stateSaver.saveCount)
        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertEquals(1, fixture.notifier.calls.size)
        assertEquals(listOf(70), fixture.sound.plays)
    }

    @Test
    fun `periodic work beyond configured grace is saved as missed without alerting`() {
        val fixture = Fixture()
        fixture.service.updateSettings(fixture.service.settings().copy(overdueGracePeriodMs = 5_000))
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(6_001)

        fixture.scheduler.runPeriodic()
        fixture.ui.drain()

        assertEquals(1, fixture.stateSaver.saveCount)
        assertEquals(ItemStatus.MISSED, fixture.service.items().single().status)
        assertTrue(fixture.notifier.calls.isEmpty())
        assertTrue(fixture.sound.plays.isEmpty())
        assertTrue(fixture.scheduler.oneShots.isEmpty())
    }

    @Test
    fun `save failure does not suppress alert listener delivery or duplicate later`() {
        val fixture = Fixture()
        val parent = Disposer.newDisposable()
        var listenerDeliveries = 0
        fixture.service.addListener(parent) { listenerDeliveries++ }
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        listenerDeliveries = 0
        fixture.stateSaver.failure = IllegalStateException("Persistence unavailable")
        fixture.advanceMillis(1_000)

        fixture.scheduler.runPeriodic()

        assertEquals(1, fixture.stateSaver.saveCount)
        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertTrue(fixture.notifier.calls.isEmpty())
        fixture.ui.drain()
        assertEquals(1, listenerDeliveries)
        assertEquals(1, fixture.notifier.calls.size)
        assertTrue(fixture.notifier.calls.single().handle.delivered)
        assertFalse(fixture.service.items().single().deliveryPending)

        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        assertEquals(2, fixture.stateSaver.saveCount)
        assertEquals(1, fixture.notifier.calls.size)
        assertEquals(1, listenerDeliveries)
        Disposer.dispose(parent)
    }

    @Test
    fun `notification creation failure remains pending until lifecycle retry across backward wall correction`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.notifier.createFailure = IllegalStateException("Creation failed")
        fixture.advanceMillis(1_000)

        fixture.scheduler.runPeriodic()
        fixture.ui.drain()

        assertEquals(1, fixture.stateSaver.saveCount)
        assertTrue(fixture.service.items().single().deliveryPending)
        assertTrue(fixture.notifier.calls.isEmpty())
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        assertTrue(fixture.notifier.calls.isEmpty())
        assertEquals(1, fixture.stateSaver.saveCount)

        fixture.clock.advanceMillis(-12 * 60 * 60_000L)
        fixture.notifier.createFailure = null
        fixture.service.requestActivationCheck()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        fixture.service.requestActivationCheck()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(1, fixture.notifier.calls.size)
        assertTrue(fixture.notifier.calls.single().handle.delivered)
        assertFalse(fixture.service.items().single().deliveryPending)
        assertEquals(2, fixture.stateSaver.saveCount)
    }

    @Test
    fun `notification queue failure survives forward wall correction and is not retried by live polling`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.ui.failure = IllegalStateException("Queue failed")
        fixture.advanceMillis(1_000)

        fixture.scheduler.runPeriodic()

        assertEquals(1, fixture.stateSaver.saveCount)
        assertTrue(fixture.service.items().single().deliveryPending)
        assertTrue(fixture.notifier.calls.isEmpty())
        fixture.ui.failure = null
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        assertTrue(fixture.notifier.calls.isEmpty())
        assertEquals(1, fixture.stateSaver.saveCount)

        fixture.clock.advanceMillis(12 * 60 * 60_000L)
        fixture.service.requestActivationCheck()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(1, fixture.notifier.calls.size)
        assertFalse(fixture.service.items().single().deliveryPending)
        assertEquals(2, fixture.stateSaver.saveCount)
    }

    @Test
    fun `notification delivery failure survives forward wall correction until lifecycle retry`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.notifier.nextDeliverFailure = IllegalStateException("Delivery failed")
        fixture.advanceMillis(1_000)

        fixture.scheduler.runPeriodic()
        fixture.ui.drain()

        assertEquals(1, fixture.stateSaver.saveCount)
        assertTrue(fixture.service.items().single().deliveryPending)
        assertEquals(1, fixture.notifier.calls.size)
        assertFalse(fixture.notifier.calls.single().handle.delivered)
        assertEquals(1, fixture.notifier.calls.single().handle.expireCount)
        assertTrue(fixture.sound.plays.isEmpty())
        fixture.service.dismissAllAlerts()
        assertEquals(1, fixture.notifier.calls.single().handle.expireCount)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        assertEquals(1, fixture.notifier.calls.size)

        fixture.clock.advanceMillis(12 * 60 * 60_000L)
        fixture.service.requestActivationCheck()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(2, fixture.notifier.calls.size)
        assertEquals(1, fixture.notifier.calls.first().handle.expireCount)
        assertTrue(fixture.notifier.calls.last().handle.delivered)
        assertFalse(fixture.service.items().single().deliveryPending)
        assertEquals(2, fixture.stateSaver.saveCount)
        assertEquals(listOf(70), fixture.sound.plays)
    }

    @Test
    fun `notification that expires synchronously during delivery remains pending without sound`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.notifier.expireOnNextDelivery = true
        fixture.advanceMillis(1_000)

        fixture.scheduler.runPeriodic()
        fixture.ui.drain()

        val notification = fixture.notifier.calls.single()
        assertTrue(notification.handle.delivered)
        assertEquals(1, notification.handle.expireCount)
        assertTrue(fixture.service.items().single().deliveryPending)
        assertTrue(fixture.sound.plays.isEmpty())
        fixture.service.dismissAllAlerts()
        assertEquals(1, notification.handle.expireCount)
    }

    @Test
    fun `listeners are delivered on fake EDT and removed with parent`() {
        val fixture = Fixture()
        val parent = Disposer.newDisposable()
        var deliveries = 0
        fixture.service.addListener(parent) {
            assertTrue(fixture.ui.isDispatchThread())
            deliveries++
        }

        fixture.service.startTimer(1_000, "")
        assertEquals(0, deliveries)
        fixture.ui.drain()
        assertEquals(1, deliveries)

        Disposer.dispose(parent)
        fixture.service.startTimer(2_000, "")
        fixture.ui.drain()
        assertEquals(1, deliveries)
    }

    @Test
    fun `active timer does not pulse when no listener is interested`() {
        val fixture = Fixture()
        val parent = Disposer.newDisposable()
        val events = mutableListOf<AlarmTimerEvent>()
        fixture.service.addListener(parent) { events += it }
        fixture.service.startTimer(10_000, "Tea")
        fixture.ui.drain()
        events.clear()

        fixture.scheduler.runPeriodic()

        assertEquals(0, fixture.ui.pendingCount)
        assertTrue(events.isEmpty())
        Disposer.dispose(parent)
    }

    @Test
    fun `active clock pulses are coalesced while EDT delivery is pending`() {
        val fixture = Fixture()
        val parent = Disposer.newDisposable()
        val events = mutableListOf<AlarmTimerEvent>()
        fixture.service.addListener(parent, wantsClockPulses = { true }) { events += it }
        fixture.service.startTimer(10_000, "Tea")
        fixture.ui.drain()
        events.clear()

        fixture.scheduler.runPeriodic()
        fixture.scheduler.runPeriodic()

        assertEquals(1, fixture.ui.pendingCount)
        fixture.ui.drain()
        assertEquals(listOf(AlarmTimerEvent.CLOCK_PULSE), events)

        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        assertEquals(
            listOf(AlarmTimerEvent.CLOCK_PULSE, AlarmTimerEvent.CLOCK_PULSE),
            events,
        )
        Disposer.dispose(parent)
    }

    @Test
    fun `throwing structure listener does not suppress later listener`() {
        val fixture = Fixture()
        val firstParent = Disposer.newDisposable()
        val secondParent = Disposer.newDisposable()
        val secondEvents = mutableListOf<AlarmTimerEvent>()
        fixture.service.addListener(firstParent) { throw IllegalStateException("Listener failure") }
        fixture.service.addListener(secondParent) { secondEvents += it }

        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()

        assertEquals(listOf(AlarmTimerEvent.STRUCTURE_CHANGED), secondEvents)
        Disposer.dispose(firstParent)
        Disposer.dispose(secondParent)
    }

    @Test
    fun `structure listener cancellation propagates unchanged`() {
        val fixture = Fixture()
        val parent = Disposer.newDisposable()
        val cancellation = ProcessCanceledException()
        fixture.service.addListener(parent) { throw cancellation }

        fixture.service.startTimer(1_000, "Tea")

        val thrown = assertThrows(ProcessCanceledException::class.java) { fixture.ui.drain() }
        assertSame(cancellation, thrown)
        Disposer.dispose(parent)
    }

    @Test
    fun `throwing clock interest predicate does not suppress interested listener`() {
        val fixture = Fixture()
        val firstParent = Disposer.newDisposable()
        val secondParent = Disposer.newDisposable()
        val secondEvents = mutableListOf<AlarmTimerEvent>()
        fixture.service.startTimer(10_000, "Tea")
        fixture.ui.drain()
        fixture.service.addListener(
            firstParent,
            wantsClockPulses = { throw IllegalStateException("Interest failure") },
        ) { }
        fixture.service.addListener(secondParent, wantsClockPulses = { true }) { secondEvents += it }

        fixture.scheduler.runPeriodic()
        fixture.ui.drain()

        assertEquals(listOf(AlarmTimerEvent.CLOCK_PULSE), secondEvents)
        Disposer.dispose(firstParent)
        Disposer.dispose(secondParent)
    }

    @Test
    fun `clock interest cancellation propagates unchanged`() {
        val fixture = Fixture()
        val parent = Disposer.newDisposable()
        val cancellation = ProcessCanceledException()
        fixture.service.startTimer(10_000, "Tea")
        fixture.ui.drain()
        fixture.service.addListener(
            parent,
            wantsClockPulses = { throw cancellation },
        ) { }

        val thrown = assertThrows(ProcessCanceledException::class.java) { fixture.scheduler.runPeriodic() }

        assertSame(cancellation, thrown)
        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
        Disposer.dispose(parent)

        assertEquals(1, fixture.scheduler.oneShots.count { it.delay == 0L && it.unit == TimeUnit.MILLISECONDS })
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)

        fixture.advanceMillis(10_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()

        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertEquals(1, fixture.notifier.calls.size)
    }

    @Test
    fun `listener disposed during the interest scan does not hide a later interested listener`() {
        val fixture = Fixture()
        val firstParent = Disposer.newDisposable()
        val secondParent = Disposer.newDisposable()
        val secondEvents = mutableListOf<AlarmTimerEvent>()
        fixture.service.startTimer(10_000, "Tea")
        fixture.ui.drain()
        fixture.service.addListener(
            firstParent,
            // Interested, but gone by the time the scan confirms it. That says nothing about the
            // listeners after it.
            wantsClockPulses = {
                Disposer.dispose(firstParent)
                true
            },
        ) { }
        fixture.service.addListener(secondParent, wantsClockPulses = { true }) { secondEvents += it }

        fixture.scheduler.runPeriodic()
        fixture.ui.drain()

        assertEquals(listOf(AlarmTimerEvent.CLOCK_PULSE), secondEvents)
        Disposer.dispose(secondParent)
    }

    @Test
    fun `blocked listener callback does not retain lifecycle lock`() {
        val fixture = Fixture()
        val firstParent = Disposer.newDisposable()
        val secondParent = Disposer.newDisposable()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val secondCalls = AtomicInteger()
        fixture.service.addListener(firstParent) {
            callbackEntered.countDown()
            check(releaseCallback.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release listener callback." }
        }
        fixture.service.addListener(secondParent) { secondCalls.incrementAndGet() }
        fixture.service.startTimer(1_000, "Tea")

        val delivery = ConcurrentTask("blocked-listener-delivery") { fixture.ui.drain() }
        await(callbackEntered, "first listener callback")
        val dispose = ConcurrentTask("dispose-during-listener") { fixture.service.dispose() }
        dispose.await()
        releaseCallback.countDown()
        delivery.await()

        assertEquals(0, secondCalls.get())
        assertTrue(fixture.scheduler.isShutdown)
        assertEquals(1, fixture.scheduler.shutdownCount)
        Disposer.dispose(firstParent)
        Disposer.dispose(secondParent)
    }

    @Test
    fun `blocked clock interest predicate does not retain lifecycle lock`() {
        val fixture = Fixture()
        val parent = Disposer.newDisposable()
        val predicateEntered = CountDownLatch(1)
        val releasePredicate = CountDownLatch(1)
        val callbackCalls = AtomicInteger()
        fixture.service.startTimer(10_000, "Tea")
        fixture.ui.drain()
        fixture.service.addListener(
            parent,
            wantsClockPulses = {
                predicateEntered.countDown()
                check(releasePredicate.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release interest predicate." }
                true
            },
        ) { callbackCalls.incrementAndGet() }

        val tick = ConcurrentTask("blocked-interest-tick") { fixture.scheduler.runPeriodic() }
        await(predicateEntered, "clock interest predicate")
        val dispose = ConcurrentTask("dispose-during-interest") { fixture.service.dispose() }
        dispose.await()
        releasePredicate.countDown()
        tick.await()
        fixture.ui.drain()

        assertEquals(0, callbackCalls.get())
        assertTrue(fixture.scheduler.isShutdown)
        assertEquals(1, fixture.scheduler.shutdownCount)
        Disposer.dispose(parent)
    }

    @Test
    fun `registration disposed before queued delivery is not called`() {
        val fixture = Fixture()
        val parent = Disposer.newDisposable()
        val callbackCalls = AtomicInteger()
        fixture.service.addListener(parent) { callbackCalls.incrementAndGet() }
        fixture.service.startTimer(1_000, "Tea")

        Disposer.dispose(parent)
        fixture.ui.drain()

        assertEquals(0, callbackCalls.get())
    }

    @Test
    fun `queued listener delivery is rejected after service disposal wins`() {
        val fixture = Fixture()
        val parent = Disposer.newDisposable()
        val callbackCalls = AtomicInteger()
        fixture.service.addListener(parent) { callbackCalls.incrementAndGet() }
        fixture.service.startTimer(1_000, "Tea")

        val dispose = ConcurrentTask("dispose-before-listener-delivery") { fixture.service.dispose() }
        dispose.await()
        fixture.ui.drain()

        assertEquals(0, callbackCalls.get())
        assertTrue(fixture.scheduler.isShutdown)
        Disposer.dispose(parent)
    }

    @Test
    fun `state round trip restores settings and scheduled items`() {
        val fixture = Fixture()
        val settings = AlarmTimerSettings(defaultTimerMs = 90_000, volumePercent = 25)
        val item = ScheduledItem(
            id = "saved",
            kind = ItemKind.TIMER,
            label = "Saved timer",
            createdAtEpochMs = fixture.clock.millis(),
            targetEpochMs = fixture.clock.millis() + 90_000,
            durationMs = 90_000,
        )

        fixture.service.loadState(StateCodec.encode(settings, listOf(item)))
        fixture.ui.drain()
        val encoded = StateCodec.decode(fixture.service.state)

        assertEquals(settings, fixture.service.settings())
        assertEquals(listOf(item), fixture.service.items())
        assertEquals(settings, encoded.settings)
        assertEquals(listOf(item), encoded.items)
    }

    @Test
    fun `state from a newer schema is saved back unchanged until the user changes something`() {
        val fixture = Fixture()
        val stored = StateCodec.encode(
            AlarmTimerSettings(defaultTimerMs = 90_000),
            listOf(
                ScheduledItem(
                    id = "saved",
                    kind = ItemKind.TIMER,
                    label = "Saved timer",
                    createdAtEpochMs = fixture.clock.millis(),
                    targetEpochMs = fixture.clock.millis() + 90_000,
                    durationMs = 90_000,
                ),
            ),
        ).apply { schemaVersion = 999 }

        fixture.service.loadState(stored)
        fixture.ui.drain()

        assertTrue(fixture.service.items().isEmpty())
        assertEquals(AlarmTimerSettingsPolicy.DEFAULTS, fixture.service.settings())
        assertSame(stored, fixture.service.state)
        assertEquals(999, fixture.service.state.schemaVersion)
        assertEquals(listOf("saved"), fixture.service.state.items.map { it.id })

        fixture.service.startTimer(60_000, "Tea")
        fixture.ui.drain()

        val reencoded = fixture.service.state
        assertEquals(CURRENT_SCHEMA_VERSION, reencoded.schemaVersion)
        assertEquals(listOf("Tea"), reencoded.items.map { it.label })
    }

    @Test
    fun `settings change never discards state from a newer schema`() {
        val fixture = Fixture()
        val stored = StateCodec.encode(
            AlarmTimerSettings(defaultTimerMs = 90_000),
            listOf(
                ScheduledItem(
                    id = "saved",
                    kind = ItemKind.TIMER,
                    label = "Saved timer",
                    createdAtEpochMs = fixture.clock.millis(),
                    targetEpochMs = fixture.clock.millis() + 90_000,
                    durationMs = 90_000,
                ),
            ),
        ).apply { schemaVersion = 999 }

        fixture.service.loadState(stored)
        fixture.ui.drain()

        fixture.service.updateSettings(fixture.service.settings().copy(volumePercent = 33))
        fixture.ui.drain()

        // The setting applies in memory, and the file this build cannot read stays protected.
        assertEquals(33, fixture.service.settings().volumePercent)
        assertSame(stored, fixture.service.state)
        assertEquals(999, fixture.service.state.schemaVersion)
        assertEquals(listOf("saved"), fixture.service.state.items.map { it.id })

        fixture.service.startTimer(60_000, "Tea")
        fixture.ui.drain()

        val reencoded = fixture.service.state
        assertEquals(CURRENT_SCHEMA_VERSION, reencoded.schemaVersion)
        assertEquals(listOf("Tea"), reencoded.items.map { it.label })
        assertEquals(33, reencoded.settings.volumePercent)
    }

    @Test
    fun `undecodable state keeps defaults in memory and saves the stored state back unchanged`() {
        val fixture = Fixture()
        val stored = StateCodec.encode(
            AlarmTimerSettings(defaultTimerMs = 90_000),
            listOf(
                ScheduledItem(
                    id = "saved",
                    kind = ItemKind.TIMER,
                    label = "Saved timer",
                    createdAtEpochMs = fixture.clock.millis(),
                    targetEpochMs = fixture.clock.millis() + 90_000,
                    durationMs = 90_000,
                ),
            ),
        ).apply { items = UnreadableItemList(items) }

        fixture.service.loadState(stored)
        fixture.ui.drain()

        assertTrue(fixture.service.items().isEmpty())
        assertEquals(AlarmTimerSettingsPolicy.DEFAULTS, fixture.service.settings())
        assertSame(stored, fixture.service.state)

        fixture.service.startTimer(60_000, "Tea")
        fixture.ui.drain()

        val reencoded = fixture.service.state
        assertEquals(CURRENT_SCHEMA_VERSION, reencoded.schemaVersion)
        assertEquals(listOf("Tea"), reencoded.items.map { it.label })
    }

    @Test
    fun `undecodable legacy state is not partially migrated before read-only preservation`() {
        val fixture = Fixture()
        val stored = PersistedState().apply {
            schemaVersion = 1
            settings.soundEnabled = true
            settings.volumePercent = 0
            items = UnreadableItemList(mutableListOf())
        }

        fixture.service.loadState(stored)
        fixture.ui.drain()

        assertSame(stored, fixture.service.state)
        assertEquals(1, stored.schemaVersion)
        assertTrue(stored.settings.soundEnabled)
        assertEquals(0, stored.settings.volumePercent)
        assertEquals(AlarmTimerSettingsPolicy.DEFAULTS, fixture.service.settings())
        assertTrue(fixture.service.items().isEmpty())
    }

    @Test
    fun `live timer polling ignores forward and backward wall corrections`() {
        listOf(10 * 60_000L, -10 * 60_000L).forEach { wallCorrection ->
            val fixture = Fixture()
            fixture.service.startTimer(1_000, "Tea")
            fixture.ui.drain()

            fixture.clock.advanceMillis(wallCorrection)
            fixture.scheduler.runPeriodic()
            fixture.ui.drain()

            assertEquals(1_000, fixture.service.remainingMs(fixture.service.items().single().id))
            assertEquals(ItemStatus.ACTIVE, fixture.service.items().single().status)
            assertTrue(fixture.notifier.calls.isEmpty())

            fixture.elapsedTime.advanceMillis(1_000)
            fixture.scheduler.runPeriodic()
            fixture.ui.drain()

            val completed = fixture.service.items().single()
            assertEquals(ItemStatus.COMPLETED, completed.status)
            assertEquals(fixture.clock.millis(), completed.targetEpochMs)
            assertEquals(1, fixture.notifier.calls.size)
        }
    }

    @Test
    fun `ordinary activation preserves live timer across forward and backward wall corrections`() {
        listOf(10 * 60_000L, -10 * 60_000L).forEach { wallCorrection ->
            val fixture = Fixture()
            val timer = fixture.service.startTimer(1_000, "Tea")
            fixture.ui.drain()

            fixture.clock.advanceMillis(wallCorrection)
            fixture.service.requestActivationCheck()
            fixture.scheduler.oneShots.single().task()
            fixture.ui.drain()

            assertEquals(1_000, fixture.service.remainingMs(timer.id))
            assertEquals(ItemStatus.ACTIVE, fixture.service.items().single().status)
            assertTrue(fixture.notifier.calls.isEmpty())

            fixture.elapsedTime.advanceMillis(1_000)
            fixture.scheduler.runPeriodic()
            fixture.ui.drain()

            assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
            assertEquals(1, fixture.notifier.calls.size)
        }
    }

    @Test
    fun `ordinary activation resolves alarms from corrected wall time`() {
        val fixture = Fixture()
        fixture.service.updateSettings(fixture.service.settings().copy(overdueGracePeriodMs = 500))
        fixture.service.scheduleAlarm(fixture.clock.millis() + 1_000, "Meeting")
        fixture.ui.drain()
        fixture.clock.advanceMillis(1_500)

        fixture.service.requestActivationCheck()
        fixture.scheduler.oneShots.single().task()
        fixture.ui.drain()

        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertEquals(1, fixture.notifier.calls.size)
    }

    @Test
    fun `zero grace activation alerts slightly late alarm exactly once`() {
        val fixture = Fixture()
        fixture.service.updateSettings(fixture.service.settings().copy(overdueGracePeriodMs = 0))
        fixture.service.scheduleAlarm(fixture.clock.millis() + 1_000, "Meeting")
        fixture.ui.drain()
        fixture.clock.advanceMillis(1_001)

        fixture.service.requestActivationCheck()
        fixture.scheduler.oneShots.single().task()
        fixture.ui.drain()

        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertEquals(1, fixture.notifier.calls.size)
        assertEquals(listOf(70), fixture.sound.plays)

        fixture.service.requestActivationCheck()
        fixture.scheduler.oneShots.last().task()
        fixture.ui.drain()

        assertEquals(1, fixture.notifier.calls.size)
        assertEquals(listOf(70), fixture.sound.plays)
    }

    @Test
    fun `zero grace activation uses monotonic timer deadline when slightly late`() {
        val fixture = Fixture()
        fixture.service.updateSettings(fixture.service.settings().copy(overdueGracePeriodMs = 0))
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.clock.advanceMillis(-10 * 60_000L)
        fixture.elapsedTime.advanceMillis(1_001)

        fixture.service.requestActivationCheck()
        fixture.scheduler.oneShots.single().task()
        fixture.ui.drain()

        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertEquals(1, fixture.notifier.calls.size)
        assertEquals(listOf(70), fixture.sound.plays)
    }

    @Test
    fun `zero grace activation alerts alarm at live tolerance boundary`() {
        val fixture = Fixture()
        fixture.service.updateSettings(fixture.service.settings().copy(overdueGracePeriodMs = 0))
        fixture.service.scheduleAlarm(fixture.clock.millis() + 1_000, "Meeting")
        fixture.ui.drain()
        fixture.clock.advanceMillis(2_250)

        fixture.service.requestActivationCheck()
        fixture.scheduler.oneShots.single().task()
        fixture.ui.drain()

        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertEquals(1, fixture.notifier.calls.size)
        assertEquals(listOf(70), fixture.sound.plays)
    }

    @Test
    fun `zero grace activation misses alarm beyond live tolerance boundary`() {
        val fixture = Fixture()
        fixture.service.updateSettings(fixture.service.settings().copy(overdueGracePeriodMs = 0))
        fixture.service.scheduleAlarm(fixture.clock.millis() + 1_000, "Meeting")
        fixture.ui.drain()
        fixture.clock.advanceMillis(2_251)

        fixture.service.requestActivationCheck()
        fixture.scheduler.oneShots.single().task()
        fixture.ui.drain()

        assertEquals(ItemStatus.MISSED, fixture.service.items().single().status)
        assertTrue(fixture.notifier.calls.isEmpty())
        assertTrue(fixture.sound.plays.isEmpty())
    }

    @Test
    fun `activation honors configured grace beyond live tolerance`() {
        val fixture = Fixture()
        fixture.service.updateSettings(fixture.service.settings().copy(overdueGracePeriodMs = 5_000))
        fixture.service.scheduleAlarm(fixture.clock.millis() + 1_000, "Meeting")
        fixture.ui.drain()
        fixture.clock.advanceMillis(5_000)

        fixture.service.requestActivationCheck()
        fixture.scheduler.oneShots.single().task()
        fixture.ui.drain()

        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertEquals(1, fixture.notifier.calls.size)
        assertEquals(listOf(70), fixture.sound.plays)
    }

    @Test
    fun `active timer state rebases to wall time and restores remaining duration`() {
        val fixture = Fixture()
        val timer = fixture.service.startTimer(60_000, "Tea")
        fixture.ui.drain()
        fixture.elapsedTime.advanceMillis(20_000)
        fixture.clock.advanceMillis(-5 * 60_000L)

        val persisted = fixture.service.state
        val persistedTimer = StateCodec.decode(persisted).items.single()

        assertEquals(timer.id, persistedTimer.id)
        assertEquals(fixture.clock.millis() + 40_000, persistedTimer.targetEpochMs)
        assertEquals(timer.targetEpochMs, fixture.service.items().single().targetEpochMs)

        val restarted = Fixture(clock = MutableClock(fixture.clock.instant()))
        restarted.service.loadState(persisted)
        restarted.ui.drain()
        assertEquals(40_000, restarted.service.remainingMs(timer.id))

        restarted.clock.advanceMillis(60 * 60_000L)
        assertEquals(40_000, restarted.service.remainingMs(timer.id))
        restarted.elapsedTime.advanceMillis(40_000)
        restarted.scheduler.runPeriodic()
        restarted.ui.drain()
        assertEquals(ItemStatus.COMPLETED, restarted.service.items().single().status)
    }

    @Test
    fun `paused timer preserves elapsed remaining through wall correction and state round trip`() {
        val fixture = Fixture()
        val timer = fixture.service.startTimer(60_000, "Tea")
        fixture.ui.drain()
        fixture.elapsedTime.advanceMillis(20_000)
        fixture.clock.advanceMillis(6 * 60 * 60_000L)

        assertTrue(fixture.service.pause(timer.id))
        val pausedState = fixture.service.state
        assertEquals(40_000, StateCodec.decode(pausedState).items.single().remainingMs)

        val restarted = Fixture(clock = MutableClock(fixture.clock.instant()))
        restarted.service.loadState(pausedState)
        restarted.ui.drain()
        restarted.clock.advanceMillis(-12 * 60 * 60_000L)
        assertTrue(restarted.service.resume(timer.id))
        assertEquals(40_000, restarted.service.remainingMs(timer.id))
        restarted.clock.advanceMillis(24 * 60 * 60_000L)
        assertEquals(40_000, restarted.service.remainingMs(timer.id))
    }

    @Test
    fun `subsecond paused timer survives state round trip and fires once after resume`() {
        val fixture = Fixture()
        val timer = fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(999)

        assertTrue(fixture.service.pause(timer.id))
        val pausedState = fixture.service.state

        val restarted = Fixture(clock = MutableClock(fixture.clock.instant()))
        restarted.service.loadState(pausedState)
        restarted.ui.drain()
        val restoredTimer = restarted.service.items().single()
        assertEquals(ItemStatus.PAUSED, restoredTimer.status)
        assertEquals(1L, restoredTimer.remainingMs)

        assertTrue(restarted.service.resume(timer.id))
        restarted.elapsedTime.advanceMillis(1)
        restarted.scheduler.runPeriodic()
        restarted.ui.drain()

        assertEquals(ItemStatus.COMPLETED, restarted.service.items().single().status)
        assertEquals(listOf(timer.id), restarted.notifier.calls.map { it.item.id })
        restarted.scheduler.runPeriodic()
        restarted.ui.drain()
        assertEquals(1, restarted.notifier.calls.size)
    }

    @Test
    fun `dispose expires active alert stops sound and shuts down scheduler`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        val handle = fixture.notifier.calls.single().handle
        val queuedSoundStop = fixture.scheduler.oneShots.single()

        fixture.service.dispose()
        fixture.service.dispose()
        queuedSoundStop.task()

        assertTrue(handle.expired)
        assertEquals(1, fixture.sound.stopCount)
        assertEquals(1, fixture.sound.disposeCount)
        assertTrue(fixture.scheduler.isShutdown)
        assertEquals(1, fixture.scheduler.shutdownCount)
    }

    @Test
    fun `dispose attempts every cleanup when sound disposal cancellation and expiration fail`() {
        val fixture = Fixture()
        val calls = fixture.deliverTwoTimers()
        fixture.service.startTimer(60_000, "Future")
        fixture.ui.drain()
        calls.first().handle.nextExpireFailure = IllegalStateException("Expiration failed")
        fixture.sound.nextDisposeFailure = IllegalStateException("Disposal failed")
        fixture.scheduler.nextPeriodicCancelFailure = IllegalStateException("Cancellation failed")
        val cancellationsBeforeDispose = fixture.scheduler.periodicCancellationCount

        fixture.service.dispose()
        fixture.service.dispose()

        assertEquals(listOf(1, 1), calls.map { it.handle.expireCount })
        assertTrue(calls.last().handle.expired)
        assertEquals(1, fixture.sound.disposeCount)
        assertEquals(cancellationsBeforeDispose + 1, fixture.scheduler.periodicCancellationCount)
        assertTrue(fixture.scheduler.isShutdown)
        assertEquals(1, fixture.scheduler.shutdownCount)
        fixture.scheduler.runPeriodic()
        assertEquals(0, fixture.scheduler.postShutdownScheduleCount)
    }

    @Test
    fun `dispose propagates cancellation only after every cleanup has been attempted`() {
        val fixture = Fixture()
        val calls = fixture.deliverTwoTimers()
        fixture.service.startTimer(60_000, "Future")
        fixture.ui.drain()
        val cancellation = ProcessCanceledException()
        fixture.sound.nextDisposeFailure = cancellation
        calls.first().handle.nextExpireFailure = ProcessCanceledException()
        val cancellationsBeforeDispose = fixture.scheduler.periodicCancellationCount

        val thrown = assertThrows(ProcessCanceledException::class.java) { fixture.service.dispose() }

        assertSame(cancellation, thrown)
        assertEquals(1, thrown.suppressed.size)
        assertEquals(listOf(1, 1), calls.map { it.handle.expireCount })
        assertTrue(calls.last().handle.expired)
        assertEquals(1, fixture.sound.disposeCount)
        assertEquals(cancellationsBeforeDispose + 1, fixture.scheduler.periodicCancellationCount)
        assertTrue(fixture.scheduler.isShutdown)
        assertEquals(1, fixture.scheduler.shutdownCount)
    }

    @Test
    fun `natural expiration of sole alert stops sound once`() {
        val fixture = Fixture()
        assertFalse(fixture.service.hasActiveAlerts())
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        val alert = fixture.notifier.calls.single()
        assertTrue(fixture.service.hasActiveAlerts())

        alert.handle.expire()

        assertEquals(1, alert.handle.expireCount)
        assertEquals(1, fixture.sound.stopCount)
        assertFalse(fixture.service.hasActiveAlerts())
    }

    @Test
    fun `natural expiration stops sound only after last alert`() {
        val fixture = Fixture()
        val calls = fixture.deliverTwoTimers()

        calls.first().handle.expire()

        assertEquals(listOf(1, 0), calls.map { it.handle.expireCount })
        assertEquals(0, fixture.sound.stopCount)

        calls.last().handle.expire()

        assertEquals(listOf(1, 1), calls.map { it.handle.expireCount })
        assertEquals(1, fixture.sound.stopCount)
    }

    @Test
    fun `programmatic dismiss does not double stop through expiration callback`() {
        val dismissed = Fixture()
        dismissed.service.startTimer(1_000, "Dismiss")
        dismissed.ui.drain()
        dismissed.advanceMillis(1_000)
        dismissed.scheduler.runPeriodic()
        dismissed.ui.drain()
        val dismissAlert = dismissed.notifier.calls.single()

        dismissAlert.dismiss()
        dismissAlert.expired()

        assertEquals(1, dismissAlert.handle.expireCount)
        assertEquals(1, dismissed.sound.stopCount)
    }

    @Test
    fun `old handle callbacks cannot retire newer alert with same item id`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        val firstItem = fixture.service.items().single()

        fixture.service.loadState(
            StateCodec.encode(
                fixture.service.settings(),
                listOf(firstItem.copy(deliveryPending = true)),
            ),
        )
        fixture.ui.drain()
        fixture.service.requestStartupRecovery()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        fixture.ui.drain()

        val first = fixture.notifier.calls.first()
        val replacement = fixture.notifier.calls.last()
        assertEquals(2, fixture.notifier.calls.size)
        assertEquals(first.item.id, replacement.item.id)
        assertEquals(1, first.handle.expireCount)
        assertEquals(0, replacement.handle.expireCount)
        assertEquals(1, fixture.sound.stopCount)

        first.dismiss()

        assertEquals(1, first.handle.expireCount)
        assertEquals(0, replacement.handle.expireCount)
        assertTrue(fixture.service.hasActiveAlerts())
        assertEquals(1, fixture.sound.stopCount)

        replacement.handle.expire()

        assertEquals(1, replacement.handle.expireCount)
        assertEquals(2, fixture.sound.stopCount)
    }

    @Test
    fun `state replacement invalidates queued delivery from the replaced state`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Old state")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        assertTrue(fixture.ui.pendingCount > 0)

        fixture.service.loadState(StateCodec.encode(fixture.service.settings(), emptyList()))
        fixture.ui.drain()

        assertTrue(fixture.service.items().isEmpty())
        assertFalse(fixture.service.hasActiveAlerts())
        assertTrue(fixture.notifier.calls.isEmpty())
        assertTrue(fixture.sound.plays.isEmpty())
    }

    @Test
    fun `replacement expiration failure cannot prevent acknowledgement persistence`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        val first = fixture.notifier.calls.single()
        first.handle.nextExpireFailure = IllegalStateException("Expiration failed")
        val savesBeforeReplacement = fixture.stateSaver.saveCount

        fixture.service.loadState(
            StateCodec.encode(
                fixture.service.settings(),
                listOf(fixture.service.items().single().copy(deliveryPending = true)),
            ),
        )
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        fixture.ui.drain()

        assertEquals(2, fixture.notifier.calls.size)
        assertEquals(1, first.handle.expireCount)
        assertFalse(fixture.service.items().single().deliveryPending)
        assertEquals(1, fixture.scheduler.oneShots.count { it.delay == 0L && it.unit == TimeUnit.MILLISECONDS })

        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(savesBeforeReplacement + 1, fixture.stateSaver.saveCount)
        assertEquals(listOf(false), fixture.stateSaver.deliveryPendingSnapshots.last())
    }

    @Test
    fun `expiration callback after disposal is harmless`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        val alert = fixture.notifier.calls.single()

        fixture.service.dispose()
        alert.expired()

        assertEquals(1, alert.handle.expireCount)
        assertEquals(1, fixture.sound.stopCount)
        assertEquals(1, fixture.sound.disposeCount)
    }

    @Test
    fun `each balloon dismisses only its own notification in either order`() {
        listOf(listOf(0, 1), listOf(1, 0)).forEach { order ->
            val fixture = Fixture()
            val calls = fixture.deliverTwoTimers()
            val first = calls[order.first()]
            val second = calls[order.last()]

            first.dismiss()

            assertEquals(1, first.handle.expireCount)
            assertEquals(0, second.handle.expireCount)
            assertEquals(0, fixture.sound.stopCount)

            second.dismiss()

            assertEquals(1, first.handle.expireCount)
            assertEquals(1, second.handle.expireCount)
            assertEquals(1, fixture.sound.stopCount)
        }
    }

    @Test
    fun `removing one of two completed alerts expires only its handle`() {
        val fixture = Fixture()
        val calls = fixture.deliverTwoTimers()
        val removed = calls.first()
        val remaining = calls.last()

        assertTrue(fixture.service.cancel(removed.item.id))

        assertEquals(listOf(remaining.item.id), fixture.service.items().map { it.id })
        assertEquals(1, removed.handle.expireCount)
        assertEquals(0, remaining.handle.expireCount)
        assertEquals(0, fixture.sound.stopCount)
    }

    @Test
    fun `clearing completed alerts expires every handle and stops sound once`() {
        val fixture = Fixture()
        val calls = fixture.deliverTwoTimers()

        fixture.service.clearCompleted()
        fixture.service.clearCompleted()

        assertTrue(fixture.service.items().isEmpty())
        assertEquals(listOf(1, 1), calls.map { it.handle.expireCount })
        assertEquals(1, fixture.sound.stopCount)
    }

    @Test
    fun `clearing completed before queued delivery does not create a stale alert`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()

        fixture.service.clearCompleted()
        fixture.ui.drain()

        assertTrue(fixture.service.items().isEmpty())
        assertTrue(fixture.notifier.calls.isEmpty())
        assertTrue(fixture.sound.plays.isEmpty())
        assertEquals(0, fixture.sound.stopCount)
    }

    @Test
    fun `removed alert ignores repeated stale balloon actions`() {
        val fixture = Fixture()
        val calls = fixture.deliverTwoTimers()
        val removed = calls.first()
        val remaining = calls.last()
        fixture.service.cancel(removed.item.id)

        repeat(2) {
            removed.dismiss()
        }

        assertEquals(listOf(remaining.item.id), fixture.service.items().map { it.id })
        assertEquals(1, removed.handle.expireCount)
        assertEquals(0, remaining.handle.expireCount)
        assertEquals(0, fixture.sound.stopCount)
    }

    @Test
    fun `global dismiss after removal retires remaining alert and stops once`() {
        val fixture = Fixture()
        val calls = fixture.deliverTwoTimers()
        fixture.service.cancel(calls.first().item.id)

        fixture.service.dismissAllAlerts()

        assertEquals(listOf(1, 1), calls.map { it.handle.expireCount })
        assertEquals(1, fixture.sound.stopCount)
    }

    @Test
    fun `global dismiss expires all active notifications and stops shared sound`() {
        val fixture = Fixture()
        assertFalse(fixture.service.hasActiveAlerts())
        val calls = fixture.deliverTwoTimers()
        assertTrue(fixture.service.hasActiveAlerts())

        fixture.service.dismissAllAlerts()

        assertEquals(listOf(1, 1), calls.map { it.handle.expireCount })
        assertEquals(1, fixture.sound.stopCount)
        assertFalse(fixture.service.hasActiveAlerts())
        calls.first().dismiss()
        assertEquals(listOf(1, 1), calls.map { it.handle.expireCount })
    }

    @Test
    fun `adapter failures cannot abort bulk dismiss or retain sound ownership`() {
        val fixture = Fixture()
        val calls = fixture.deliverTwoTimers()
        calls.first().handle.nextExpireFailure = IllegalStateException("Expiration failed")
        fixture.sound.nextStopFailure = IllegalStateException("Stop failed")

        fixture.service.dismissAllAlerts()

        assertEquals(listOf(1, 1), calls.map { it.handle.expireCount })
        assertTrue(calls.last().handle.expired)
        assertEquals(1, fixture.sound.stopCount)

        fixture.service.testSound(enabled = true, volumePercent = 40)

        assertEquals(listOf(70, 70, 40), fixture.sound.plays)
        assertEquals(1, fixture.scheduler.oneShots.count { it.delay == 2L && it.unit == TimeUnit.SECONDS })
    }

    @Test
    fun `adapter failures cannot abort individual retirement or retain sound ownership`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        val alert = fixture.notifier.calls.single()
        alert.handle.nextExpireFailure = IllegalStateException("Expiration failed")
        fixture.sound.nextStopFailure = IllegalStateException("Stop failed")

        assertTrue(fixture.service.cancel(alert.item.id))

        assertTrue(fixture.service.items().isEmpty())
        assertEquals(1, alert.handle.expireCount)
        assertEquals(1, fixture.sound.stopCount)

        fixture.service.testSound(enabled = true, volumePercent = 40)

        assertEquals(listOf(70, 40), fixture.sound.plays)
        assertEquals(1, fixture.scheduler.oneShots.count { it.delay == 2L && it.unit == TimeUnit.SECONDS })
    }

    @Test
    fun `disabling sound immediately stops active alert audio without expiring its notification`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        val notification = fixture.notifier.calls.single()
        val soundTimeout = fixture.scheduler.oneShots.single()

        fixture.service.updateSettings(fixture.service.settings().copy(soundEnabled = false))

        assertFalse(fixture.service.settings().soundEnabled)
        assertEquals(1, fixture.sound.stopCount)
        assertEquals(0, notification.handle.expireCount)

        soundTimeout.task()
        notification.handle.expire()

        assertEquals(1, fixture.sound.stopCount)
        assertEquals(1, notification.handle.expireCount)
    }

    @Test
    fun `disabling sound while idle does not issue unnecessary audio stop`() {
        val fixture = Fixture()

        fixture.service.updateSettings(fixture.service.settings().copy(soundEnabled = false))

        assertFalse(fixture.service.settings().soundEnabled)
        assertEquals(0, fixture.sound.stopCount)
    }

    @Test
    fun `disabling sound immediately stops an active preview and invalidates its timeout`() {
        val fixture = Fixture()
        fixture.service.updateSettings(fixture.service.settings().copy(soundEnabled = false))
        fixture.service.testSound(enabled = true, volumePercent = 37)
        val previewTimeout = fixture.scheduler.oneShots.single()

        fixture.service.updateSettings(fixture.service.settings().copy(soundEnabled = false))

        assertFalse(fixture.service.settings().soundEnabled)
        assertEquals(1, fixture.sound.stopCount)

        previewTimeout.task()

        assertEquals(1, fixture.sound.stopCount)
    }

    @Test
    fun `test sound uses explicit unsaved preview without mutating saved settings`() {
        val fixture = Fixture()
        val saved = fixture.service.settings().copy(soundEnabled = false, volumePercent = 12)
        fixture.service.updateSettings(saved)

        fixture.service.testSound(enabled = true, volumePercent = 37)

        assertEquals(listOf(37), fixture.sound.plays)
        assertEquals(listOf(2L), fixture.scheduler.oneShots.map { it.delay })
        assertEquals(saved, fixture.service.settings())
    }

    @Test
    fun `test sound honors unsaved disabled preview without mutating saved settings`() {
        val fixture = Fixture()
        val saved = fixture.service.settings().copy(soundEnabled = true, volumePercent = 12)
        fixture.service.updateSettings(saved)

        fixture.service.testSound(enabled = false, volumePercent = 91)

        assertTrue(fixture.sound.plays.isEmpty())
        assertTrue(fixture.scheduler.oneShots.isEmpty())
        assertEquals(saved, fixture.service.settings())
    }

    @Test
    fun `test sound clamps preview volume to shared settings bounds`() {
        val belowMinimum = Fixture()
        val aboveMaximum = Fixture()

        belowMinimum.service.testSound(
            enabled = true,
            volumePercent = AlarmTimerSettingsPolicy.MIN_VOLUME_PERCENT - 1,
        )
        aboveMaximum.service.testSound(
            enabled = true,
            volumePercent = AlarmTimerSettingsPolicy.MAX_VOLUME_PERCENT + 1,
        )

        assertEquals(listOf(AlarmTimerSettingsPolicy.MIN_VOLUME_PERCENT), belowMinimum.sound.plays)
        assertEquals(listOf(AlarmTimerSettingsPolicy.MAX_VOLUME_PERCENT), aboveMaximum.sound.plays)
    }

    @Test
    fun `failed preview start clears ownership and permits a later preview`() {
        val fixture = Fixture()
        val failure = IllegalStateException("Playback failed")
        fixture.sound.nextPlayFailure = failure

        val thrown = assertThrows(IllegalStateException::class.java) {
            fixture.service.testSound(enabled = true, volumePercent = 37)
        }

        assertSame(failure, thrown)
        assertEquals(1, fixture.sound.stopCount)
        assertTrue(fixture.scheduler.oneShots.isEmpty())

        fixture.service.testSound(enabled = true, volumePercent = 41)

        assertEquals(listOf(41), fixture.sound.plays)
        assertEquals(listOf(2L), fixture.scheduler.oneShots.map { it.delay })
    }

    @Test
    fun `test sound timeout does not stop a newer real alert`() {
        val fixture = Fixture()
        fixture.service.testSound(enabled = true, volumePercent = 70)
        val testSoundTimeout = fixture.scheduler.oneShots.single()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        val alertTimeout = fixture.scheduler.oneShots.last { it.delay == 30L && it.unit == TimeUnit.SECONDS }

        testSoundTimeout.task()

        assertEquals(0, fixture.sound.stopCount)
        fixture.notifier.calls.single().dismiss()
        assertEquals(1, fixture.sound.stopCount)
        alertTimeout.task()
        assertEquals(1, fixture.sound.stopCount)
    }

    @Test
    fun `timed out alert history does not keep a newer alert sound alive`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "First")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        val first = fixture.notifier.calls.single()
        val firstTimeout = fixture.scheduler.oneShots.single()

        firstTimeout.task()

        assertEquals(1, fixture.sound.stopCount)
        assertEquals(0, first.handle.expireCount)
        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)

        fixture.service.startTimer(1_000, "Second")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        val second = fixture.notifier.calls.last()

        second.dismiss()

        assertEquals(0, first.handle.expireCount)
        assertEquals(1, second.handle.expireCount)
        assertEquals(2, fixture.sound.stopCount)
    }

    @Test
    fun `test sound is ignored while a real alert owns playback and works after timeout`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        val alertTimeout = fixture.scheduler.oneShots.single()

        fixture.service.testSound(enabled = true, volumePercent = 70)

        assertEquals(listOf(70), fixture.sound.plays)
        assertEquals(listOf(30L), fixture.scheduler.oneShots.map { it.delay })

        alertTimeout.task()
        fixture.service.testSound(enabled = true, volumePercent = 70)

        assertEquals(1, fixture.sound.stopCount)
        assertEquals(listOf(70, 70), fixture.sound.plays)
        assertEquals(listOf(30L, 2L), fixture.scheduler.oneShots.map { it.delay })
    }

    @Test
    fun `alert sound play failure clears ownership without expiring delivered notification`() {
        val fixture = Fixture()
        fixture.sound.nextPlayFailure = IllegalStateException("Playback failed")
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)

        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        val notification = fixture.notifier.calls.single()
        assertTrue(notification.handle.delivered)
        assertEquals(0, notification.handle.expireCount)
        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertFalse(fixture.service.items().single().deliveryPending)
        assertEquals(2, fixture.stateSaver.saveCount)
        assertTrue(fixture.sound.plays.isEmpty())
        assertEquals(1, fixture.sound.stopCount)
        assertTrue(fixture.scheduler.oneShots.isEmpty())

        fixture.service.testSound(enabled = true, volumePercent = 70)

        assertEquals(listOf(70), fixture.sound.plays)
        assertEquals(listOf(2L), fixture.scheduler.oneShots.map { it.delay })
    }

    @Test
    fun `sound cancellation propagates after acknowledgement persistence is queued`() {
        val fixture = Fixture()
        val cancellation = ProcessCanceledException()
        fixture.sound.nextPlayFailure = cancellation
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)

        fixture.scheduler.runPeriodic()
        val thrown = assertThrows(ProcessCanceledException::class.java) { fixture.ui.drain() }

        assertSame(cancellation, thrown)
        assertFalse(fixture.service.items().single().deliveryPending)
        assertEquals(
            1,
            fixture.scheduler.oneShots.count { it.delay == 0L && it.unit == TimeUnit.MILLISECONDS },
        )

        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(2, fixture.stateSaver.saveCount)
        assertEquals(listOf(false), fixture.stateSaver.deliveryPendingSnapshots.last())
    }

    @Test
    fun `alert sound timeout scheduling failure stops playback and clears ownership`() {
        val fixture = Fixture()
        fixture.scheduler.nextDelayedOneShotFailure = IllegalStateException("Scheduling failed")
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)

        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        val notification = fixture.notifier.calls.single()
        assertTrue(notification.handle.delivered)
        assertEquals(0, notification.handle.expireCount)
        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertFalse(fixture.service.items().single().deliveryPending)
        assertEquals(2, fixture.stateSaver.saveCount)
        assertEquals(listOf(70), fixture.sound.plays)
        assertEquals(1, fixture.sound.stopCount)
        assertTrue(fixture.scheduler.oneShots.isEmpty())

        fixture.service.testSound(enabled = true, volumePercent = 70)

        assertEquals(listOf(70, 70), fixture.sound.plays)
        assertEquals(listOf(2L), fixture.scheduler.oneShots.map { it.delay })
    }

    @Test
    fun `later alert sound play failure preserves first alert ownership`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "First")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        val first = fixture.notifier.calls.single()
        val firstTimeout = fixture.scheduler.oneShots.single()

        fixture.service.startTimer(1_000, "Second")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.sound.nextPlayFailure = IllegalStateException("Playback failed")
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(2, fixture.notifier.calls.size)
        assertEquals(listOf(70), fixture.sound.plays)
        assertEquals(0, fixture.sound.stopCount)
        assertEquals(listOf(firstTimeout), fixture.scheduler.oneShots)

        first.dismiss()
        firstTimeout.task()

        assertEquals(1, fixture.sound.stopCount)
    }

    @Test
    fun `first alert timeout still bounds shared sound after a later play fails`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "First")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        val firstTimeout = fixture.scheduler.oneShots.single()

        fixture.service.startTimer(1_000, "Second")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.sound.nextPlayFailure = IllegalStateException("Playback failed")
        fixture.ui.drain()

        assertEquals(listOf(70), fixture.sound.plays)
        assertEquals(0, fixture.sound.stopCount)
        assertEquals(
            listOf(firstTimeout),
            fixture.scheduler.oneShots.filter { it.unit == TimeUnit.SECONDS },
        )

        firstTimeout.task()

        assertEquals(1, fixture.sound.stopCount)
    }

    @Test
    fun `later alert sound timeout scheduling failure preserves first timeout`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "First")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        val firstTimeout = fixture.scheduler.oneShots.single()

        fixture.service.startTimer(1_000, "Second")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        fixture.scheduler.nextDelayedOneShotFailure = IllegalStateException("Scheduling failed")
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(2, fixture.notifier.calls.size)
        assertEquals(listOf(70, 70), fixture.sound.plays)
        assertEquals(0, fixture.sound.stopCount)
        assertEquals(listOf(firstTimeout), fixture.scheduler.oneShots)

        firstTimeout.task()

        assertEquals(1, fixture.sound.stopCount)
    }

    @Test
    fun `only newest alert timeout owns shared playback even out of order`() {
        val fixture = Fixture()
        fixture.deliverTwoTimers()
        val firstTimeout = fixture.scheduler.oneShots.first()
        val latestTimeout = fixture.scheduler.oneShots.last()

        latestTimeout.task()
        firstTimeout.task()

        assertEquals(1, fixture.sound.stopCount)
    }

    @Test
    fun `explicit dismiss stops current playback and invalidates its timeout`() {
        val fixture = Fixture()
        fixture.service.testSound(enabled = true, volumePercent = 70)
        val timeout = fixture.scheduler.oneShots.single()

        fixture.service.dismissAllAlerts()
        timeout.task()

        assertEquals(1, fixture.sound.stopCount)
    }

    @Test
    fun `queued due alert is discarded when service is disposed before EDT delivery`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)
        fixture.scheduler.runPeriodic()
        assertTrue(fixture.notifier.calls.isEmpty())

        fixture.service.dispose()
        val stopCountAfterDispose = fixture.sound.stopCount
        fixture.ui.drain()

        assertTrue(fixture.notifier.calls.isEmpty())
        assertTrue(fixture.sound.plays.isEmpty())
        assertEquals(stopCountAfterDispose, fixture.sound.stopCount)
        assertTrue(fixture.scheduler.oneShots.isEmpty())
        assertEquals(0, fixture.scheduler.postShutdownScheduleCount)

        fixture.scheduler.runPeriodic()
        fixture.service.testSound(enabled = true, volumePercent = 70)
        assertEquals(1, fixture.stateSaver.saveCount)
        assertTrue(fixture.service.items().single().deliveryPending)
        assertTrue(fixture.notifier.calls.isEmpty())
        assertTrue(fixture.sound.plays.isEmpty())
        assertTrue(fixture.scheduler.oneShots.isEmpty())
        assertEquals(0, fixture.scheduler.postShutdownScheduleCount)
    }

    @Test
    fun `disposal during due calculation prevents later persistence and delivery gateways`() {
        val clock = MutableClock(Instant.parse("2026-07-15T12:00:00Z"))
        val dueCheckEntered = CountDownLatch(1)
        val releaseDueCheck = CountDownLatch(1)
        val blockNextInstant = AtomicBoolean()
        val serviceClock = object : Clock() {
            override fun getZone(): ZoneId = clock.zone
            override fun withZone(zone: ZoneId): Clock = clock.withZone(zone)
            override fun instant(): Instant {
                if (blockNextInstant.compareAndSet(true, false)) {
                    dueCheckEntered.countDown()
                    check(releaseDueCheck.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release due calculation." }
                }
                return clock.instant()
            }
        }
        val fixture = Fixture(clock = clock, serviceClock = serviceClock)
        val parent = Disposer.newDisposable()
        val listenerCalls = AtomicInteger()
        fixture.service.addListener(parent) { listenerCalls.incrementAndGet() }
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        listenerCalls.set(0)
        fixture.advanceMillis(1_000)
        blockNextInstant.set(true)

        val tick = ConcurrentTask("blocked-due-tick") { fixture.scheduler.runPeriodic() }
        await(dueCheckEntered, "tick to enter due calculation")
        val dispose = ConcurrentTask("dispose-during-due") { fixture.service.dispose() }
        dispose.await()
        releaseDueCheck.countDown()
        tick.await()

        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertEquals(0, fixture.stateSaver.saveCount)
        assertTrue(fixture.service.items().single().deliveryPending)
        assertEquals(0, fixture.ui.pendingCount)
        assertTrue(fixture.notifier.calls.isEmpty())
        assertTrue(fixture.sound.plays.isEmpty())
        assertEquals(0, listenerCalls.get())
        assertTrue(fixture.scheduler.isShutdown)
        assertEquals(1, fixture.scheduler.shutdownCount)
        Disposer.dispose(parent)
    }

    @Test
    fun `disposal during persistence prevents later UI and listener gateways`() {
        val saveEntered = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val stateSaver = FakeStateSaver {
            saveEntered.countDown()
            check(releaseSave.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release persistence." }
        }
        val fixture = Fixture(stateSaver = stateSaver)
        val parent = Disposer.newDisposable()
        val listenerCalls = AtomicInteger()
        fixture.service.addListener(parent) { listenerCalls.incrementAndGet() }
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        listenerCalls.set(0)
        fixture.advanceMillis(1_000)

        val tick = ConcurrentTask("blocked-save-tick") { fixture.scheduler.runPeriodic() }
        await(saveEntered, "tick to enter persistence")
        val dispose = ConcurrentTask("dispose-during-save") { fixture.service.dispose() }
        dispose.await()
        releaseSave.countDown()
        tick.await()

        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertEquals(1, fixture.stateSaver.saveCount)
        assertTrue(fixture.service.items().single().deliveryPending)
        assertEquals(0, fixture.ui.pendingCount)
        assertTrue(fixture.notifier.calls.isEmpty())
        assertTrue(fixture.sound.plays.isEmpty())
        assertEquals(0, listenerCalls.get())
        assertTrue(fixture.scheduler.isShutdown)
        assertEquals(1, fixture.scheduler.shutdownCount)
        Disposer.dispose(parent)
    }

    @Test
    fun `queued UI work is rejected after disposal wins before delivery`() {
        val fixture = Fixture()
        val parent = Disposer.newDisposable()
        val listenerCalls = AtomicInteger()
        fixture.service.addListener(parent) { listenerCalls.incrementAndGet() }
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        listenerCalls.set(0)
        fixture.advanceMillis(1_000)

        val tick = ConcurrentTask("queue-alert-tick") { fixture.scheduler.runPeriodic() }
        tick.await()
        assertTrue(fixture.ui.pendingCount > 0)
        val dispose = ConcurrentTask("dispose-before-ui") { fixture.service.dispose() }
        dispose.await()
        fixture.ui.drain()

        assertEquals(1, fixture.stateSaver.saveCount)
        assertTrue(fixture.service.items().single().deliveryPending)
        assertTrue(fixture.notifier.calls.isEmpty())
        assertTrue(fixture.sound.plays.isEmpty())
        assertEquals(0, listenerCalls.get())
        assertTrue(fixture.scheduler.isShutdown)
        assertEquals(1, fixture.scheduler.shutdownCount)
        Disposer.dispose(parent)
    }

    @Test
    fun `tick delivers exactly once when it wins before disposal`() {
        val fixture = Fixture()
        fixture.service.startTimer(1_000, "Tea")
        fixture.ui.drain()
        fixture.advanceMillis(1_000)

        val tick = ConcurrentTask("winning-tick") { fixture.scheduler.runPeriodic() }
        tick.await()
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        val dispose = ConcurrentTask("dispose-after-delivery") { fixture.service.dispose() }
        dispose.await()
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()

        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertEquals(2, fixture.stateSaver.saveCount)
        assertFalse(fixture.service.items().single().deliveryPending)
        assertEquals(1, fixture.notifier.calls.size)
        assertEquals(listOf(70), fixture.sound.plays)
        assertTrue(fixture.scheduler.isShutdown)
        assertEquals(1, fixture.scheduler.shutdownCount)
    }

    @Test
    fun `UI-ready startup recovers saved pending delivery within grace and saves one acknowledgment`() {
        val fixture = Fixture(uiReadyInitially = false)
        val settings = fixture.service.settings().copy(overdueGracePeriodMs = 500)
        val pending = ScheduledItem(
            id = "pending",
            kind = ItemKind.ALARM,
            label = "Wake up",
            createdAtEpochMs = fixture.clock.millis() - 60_000,
            targetEpochMs = fixture.clock.millis() - 500,
            status = ItemStatus.COMPLETED,
            firedAtEpochMs = fixture.clock.millis() - 500,
            deliveryPending = true,
        )
        fixture.service.loadState(StateCodec.encode(settings, listOf(pending)))
        fixture.ui.drain()

        assertTrue(fixture.scheduler.oneShots.isEmpty())
        assertTrue(fixture.notifier.calls.isEmpty())
        fixture.service.requestUiReadyCheck()
        assertEquals(1, fixture.scheduler.oneShots.size)
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(0, fixture.stateSaver.saveCount)
        assertTrue(fixture.service.items().single().deliveryPending)
        assertTrue(fixture.notifier.calls.isEmpty())
        fixture.ui.drain()

        assertEquals(1, fixture.notifier.calls.size)
        assertTrue(fixture.notifier.calls.single().handle.delivered)
        assertFalse(fixture.service.items().single().deliveryPending)
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        assertEquals(1, fixture.stateSaver.saveCount)
    }

    @Test
    fun `UI-ready startup recovery delivers restored pending after backward wall correction`() {
        val fixture = Fixture(uiReadyInitially = false)
        val settings = fixture.service.settings().copy(overdueGracePeriodMs = 500)
        val pending = ScheduledItem(
            id = "pending",
            kind = ItemKind.ALARM,
            label = "Wake up",
            createdAtEpochMs = fixture.clock.millis() - 60_000,
            targetEpochMs = fixture.clock.millis() - 500,
            status = ItemStatus.COMPLETED,
            firedAtEpochMs = fixture.clock.millis() - 500,
            deliveryPending = true,
        )

        fixture.service.loadState(StateCodec.encode(settings, listOf(pending)))

        assertTrue(fixture.scheduler.oneShots.isEmpty())
        fixture.clock.advanceMillis(-60_000)
        fixture.service.requestUiReadyCheck()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(1, fixture.notifier.calls.size)
        assertTrue(fixture.notifier.calls.single().handle.delivered)
        assertFalse(fixture.service.items().single().deliveryPending)
        assertEquals(1, fixture.stateSaver.saveCount)
    }

    @Test
    fun `welcome and activation UI-ready requests coalesce without duplicate delivery`() {
        val fixture = Fixture(uiReadyInitially = false)
        val settings = fixture.service.settings().copy(overdueGracePeriodMs = 500)
        val pending = ScheduledItem(
            id = "pending",
            kind = ItemKind.ALARM,
            label = "Wake up",
            createdAtEpochMs = fixture.clock.millis() - 60_000,
            targetEpochMs = fixture.clock.millis() - 500,
            status = ItemStatus.COMPLETED,
            firedAtEpochMs = fixture.clock.millis() - 500,
            deliveryPending = true,
        )

        fixture.service.loadState(StateCodec.encode(settings, listOf(pending)))
        fixture.service.requestUiReadyCheck()
        fixture.service.requestUiReadyCheck()

        assertEquals(1, fixture.scheduler.oneShots.size)
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(1, fixture.notifier.calls.size)
        assertTrue(fixture.notifier.calls.single().handle.delivered)
        assertFalse(fixture.service.items().single().deliveryPending)
        assertEquals(1, fixture.stateSaver.saveCount)

        fixture.service.requestUiReadyCheck()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        fixture.ui.drain()
        assertEquals(1, fixture.notifier.calls.size)
    }

    @Test
    fun `UI-ready startup marks saved pending delivery beyond grace missed`() {
        val fixture = Fixture(uiReadyInitially = false)
        val settings = fixture.service.settings().copy(overdueGracePeriodMs = 500)
        val pending = ScheduledItem(
            id = "stale",
            kind = ItemKind.ALARM,
            label = "Wake up",
            createdAtEpochMs = fixture.clock.millis() - 60_000,
            targetEpochMs = fixture.clock.millis() - 501,
            status = ItemStatus.COMPLETED,
            firedAtEpochMs = fixture.clock.millis() - 501,
            deliveryPending = true,
        )
        fixture.service.loadState(StateCodec.encode(settings, listOf(pending)))
        fixture.ui.drain()

        assertTrue(fixture.scheduler.oneShots.isEmpty())
        fixture.service.requestUiReadyCheck()
        assertEquals(1, fixture.scheduler.oneShots.size)
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        fixture.ui.drain()

        val recovered = fixture.service.items().single()
        assertEquals(ItemStatus.MISSED, recovered.status)
        assertEquals(0, recovered.firedAtEpochMs)
        assertFalse(recovered.deliveryPending)
        assertEquals(1, fixture.stateSaver.saveCount)
        assertTrue(fixture.notifier.calls.isEmpty())
        assertTrue(fixture.sound.plays.isEmpty())
    }

    @Test
    fun `loading empty restored state does not queue lifecycle recovery`() {
        val fixture = Fixture(uiReadyInitially = false)

        fixture.service.loadState(StateCodec.encode(fixture.service.settings(), emptyList()))

        assertTrue(fixture.scheduler.oneShots.isEmpty())
        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
    }

    @Test
    fun `user-created timer confirms UI readiness and starts polling without a lifecycle callback`() {
        val fixture = Fixture(uiReadyInitially = false)

        fixture.service.startTimer(60_000, "Dynamic install")

        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)
        assertEquals(1, fixture.scheduler.oneShots.size)
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)
        assertTrue(fixture.notifier.calls.isEmpty())
    }

    @Test
    fun `active application state starts restored-item recovery without a lifecycle callback`() {
        val fixture = Fixture(uiReadyInitially = true)
        val timer = ScheduledItem(
            id = "restored",
            kind = ItemKind.TIMER,
            label = "Dynamic reload",
            createdAtEpochMs = fixture.clock.millis(),
            targetEpochMs = fixture.clock.millis() + 60_000,
            durationMs = 60_000,
        )

        fixture.service.loadState(StateCodec.encode(fixture.service.settings(), listOf(timer)))

        assertEquals(1, fixture.scheduler.activePeriodicTasks.size)
        assertEquals(1, fixture.scheduler.oneShots.size)
    }

    @Test
    fun `restored active timer does not poll or deliver before the first UI-ready check`() {
        val fixture = Fixture(uiReadyInitially = false)
        val timer = ScheduledItem(
            id = "restored",
            kind = ItemKind.TIMER,
            label = "Tea",
            createdAtEpochMs = fixture.clock.millis(),
            targetEpochMs = fixture.clock.millis() + 1_000,
            durationMs = 1_000,
        )

        fixture.service.loadState(StateCodec.encode(fixture.service.settings(), listOf(timer)))
        fixture.advanceMillis(1_000)

        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
        assertTrue(fixture.scheduler.oneShots.isEmpty())
        assertTrue(fixture.notifier.calls.isEmpty())

        fixture.service.requestUiReadyCheck()
        assertEquals(1, fixture.scheduler.oneShots.size)
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        fixture.ui.drain()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertEquals(1, fixture.notifier.calls.size)
        assertTrue(fixture.scheduler.activePeriodicTasks.isEmpty())
    }

    @Test
    fun `disposal before recovered pending UI delivery preserves pending state`() {
        val fixture = Fixture(uiReadyInitially = false)
        val settings = fixture.service.settings().copy(overdueGracePeriodMs = 500)
        val pending = ScheduledItem(
            id = "pending",
            kind = ItemKind.ALARM,
            label = "Wake up",
            createdAtEpochMs = fixture.clock.millis() - 60_000,
            targetEpochMs = fixture.clock.millis() - 500,
            status = ItemStatus.COMPLETED,
            firedAtEpochMs = fixture.clock.millis() - 500,
            deliveryPending = true,
        )
        fixture.service.loadState(StateCodec.encode(settings, listOf(pending)))
        fixture.ui.drain()
        fixture.service.requestUiReadyCheck()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)

        fixture.service.dispose()
        fixture.ui.drain()

        assertTrue(fixture.service.items().single().deliveryPending)
        assertEquals(0, fixture.stateSaver.saveCount)
        assertTrue(fixture.notifier.calls.isEmpty())
        assertTrue(fixture.sound.plays.isEmpty())
    }

    @Test
    fun `startup recovery reanchors a restored timer to elapsed time`() {
        val fixture = Fixture(uiReadyInitially = false)
        val settings = fixture.service.settings().copy(overdueGracePeriodMs = 5_000)
        val timer = ScheduledItem(
            id = "restored",
            kind = ItemKind.TIMER,
            label = "Tea",
            createdAtEpochMs = fixture.clock.millis(),
            targetEpochMs = fixture.clock.millis() + 10_000,
            durationMs = 10_000,
        )
        fixture.service.loadState(StateCodec.encode(settings, listOf(timer)))
        fixture.ui.drain()
        fixture.clock.advanceMillis(4_000)

        fixture.service.requestUiReadyCheck()
        fixture.scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
        fixture.ui.drain()

        assertEquals(ItemStatus.ACTIVE, fixture.service.items().single().status)
        assertEquals(6_000, fixture.service.remainingMs(timer.id))

        fixture.clock.advanceMillis(60 * 60_000L)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        assertEquals(ItemStatus.ACTIVE, fixture.service.items().single().status)
        assertEquals(6_000, fixture.service.remainingMs(timer.id))

        fixture.elapsedTime.advanceMillis(6_000)
        fixture.scheduler.runPeriodic()
        fixture.ui.drain()
        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
    }

    @Test
    fun `coalesced startup recovery supersedes queued activation check`() {
        val fixture = Fixture(uiReadyInitially = false)
        val settings = fixture.service.settings().copy(overdueGracePeriodMs = 500)
        val timer = ScheduledItem(
            id = "restored",
            kind = ItemKind.TIMER,
            label = "Tea",
            createdAtEpochMs = fixture.clock.millis(),
            targetEpochMs = fixture.clock.millis() + 1_000,
            durationMs = 1_000,
        )
        fixture.service.loadState(StateCodec.encode(settings, listOf(timer)))
        fixture.ui.drain()
        fixture.clock.advanceMillis(1_500)

        fixture.service.requestActivationCheck()
        fixture.service.requestStartupRecovery()

        assertEquals(1, fixture.scheduler.oneShots.size)
        assertEquals(0L, fixture.scheduler.oneShots.single().delay)
        assertEquals(TimeUnit.MILLISECONDS, fixture.scheduler.oneShots.single().unit)
        fixture.scheduler.oneShots.single().task()
        fixture.ui.drain()
        assertEquals(ItemStatus.COMPLETED, fixture.service.items().single().status)
        assertEquals(1, fixture.notifier.calls.size)
    }

    @Test
    fun `zero grace startup recovery marks slightly overdue item missed`() {
        val fixture = Fixture(uiReadyInitially = false)
        val settings = fixture.service.settings().copy(overdueGracePeriodMs = 0)
        val timer = ScheduledItem(
            id = "restored",
            kind = ItemKind.TIMER,
            label = "Tea",
            createdAtEpochMs = fixture.clock.millis(),
            targetEpochMs = fixture.clock.millis() + 1_000,
            durationMs = 1_000,
        )
        fixture.service.loadState(StateCodec.encode(settings, listOf(timer)))
        fixture.ui.drain()
        fixture.clock.advanceMillis(1_001)

        fixture.service.requestStartupRecovery()
        fixture.scheduler.oneShots.single().task()
        fixture.ui.drain()

        assertEquals(1, fixture.stateSaver.saveCount)
        assertEquals(ItemStatus.MISSED, fixture.service.items().single().status)
        assertTrue(fixture.notifier.calls.isEmpty())
        assertTrue(fixture.sound.plays.isEmpty())
    }

    @Test
    fun `activation check marks wall-clock alarm beyond grace as missed`() {
        val fixture = Fixture()
        fixture.service.scheduleAlarm(fixture.clock.millis() + 1_000, "Meeting")
        fixture.ui.drain()
        fixture.clock.advanceMillis(10 * 60_000)

        fixture.service.requestActivationCheck()
        fixture.scheduler.oneShots.single().task()
        fixture.ui.drain()

        assertEquals(ItemStatus.MISSED, fixture.service.items().single().status)
        assertTrue(fixture.notifier.calls.isEmpty())
    }

    @Test
    fun `disposed service ignores immediate lifecycle checks`() {
        val fixture = Fixture()
        fixture.service.dispose()

        fixture.service.requestActivationCheck()
        fixture.service.requestStartupRecovery()
        fixture.service.requestUiReadyCheck()

        assertTrue(fixture.scheduler.oneShots.isEmpty())
        assertEquals(0, fixture.scheduler.postShutdownScheduleCount)
    }

    private fun await(latch: CountDownLatch, description: String) {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for $description.")
    }

    private class Fixture(
        val clock: MutableClock = MutableClock(Instant.parse("2026-07-15T12:00:00Z")),
        serviceClock: Clock = clock,
        val stateSaver: FakeStateSaver = FakeStateSaver(),
        uiReadyInitially: Boolean = true,
    ) {
        val elapsedTime = MutableElapsedTimeSource()
        val scheduler = FakeScheduler()
        val sound = FakeSound()
        val ui = FakeUiDispatcher()
        val notifier = FakeAlertNotifier()
        val service = AlarmTimerService(
            serviceClock,
            elapsedTime,
            scheduler,
            sound,
            stateSaver,
            ui,
            notifier,
            uiReadyInitially,
        )

        init {
            stateSaver.dispatchThreadProbe = ui::isDispatchThread
            stateSaver.snapshotProvider = service::items
            assertTrue(scheduler.periodicTasks.isEmpty())
            assertTrue(scheduler.activePeriodicTasks.isEmpty())
        }

        fun deliverTwoTimers(): List<NotificationCall> {
            service.startTimer(1_000, "First")
            service.startTimer(1_000, "Second")
            ui.drain()
            advanceMillis(1_000)
            scheduler.runPeriodic()
            ui.drain()
            scheduler.runOneShot(0, TimeUnit.MILLISECONDS)
            assertEquals(2, notifier.calls.size)
            return notifier.calls
        }

        fun advanceMillis(millis: Long) {
            clock.advanceMillis(millis)
            elapsedTime.advanceMillis(millis)
        }
    }

    // Stands in for a persisted list the platform serializer filled with something this build
    // cannot read: iterating it fails the way a poisoned field would.
    private class UnreadableItemList(
        private val delegate: MutableList<PersistedItem>,
    ) : MutableList<PersistedItem> by delegate {
        override fun iterator(): MutableIterator<PersistedItem> =
            throw IllegalStateException("Persisted item list cannot be read.")
    }

    private class FakeScheduler : AlarmScheduler {
        val periodicTasks = CopyOnWriteArrayList<PeriodicTask>()
        val oneShots = CopyOnWriteArrayList<OneShotTask>()
        @Volatile var isShutdown = false
        @Volatile var nextOneShotFailure: RuntimeException? = null
        @Volatile var nextDelayedOneShotFailure: RuntimeException? = null
        @Volatile var nextPeriodicFailure: RejectedExecutionException? = null
        @Volatile var nextPeriodicCancelFailure: RuntimeException? = null
        private val periodicCancellations = AtomicInteger()
        private val shutdowns = AtomicInteger()
        private val postShutdownSchedules = AtomicInteger()
        val activePeriodicTasks: List<PeriodicTask> get() = periodicTasks.filter { it.isAlive() }
        val periodicCancellationCount: Int get() = periodicCancellations.get()
        val shutdownCount: Int get() = shutdowns.get()
        val postShutdownScheduleCount: Int get() = postShutdownSchedules.get()

        override fun scheduleWithFixedDelay(
            task: () -> Unit,
            initialDelay: Long,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledTask {
            nextPeriodicFailure?.let {
                nextPeriodicFailure = null
                throw it
            }
            if (isShutdown) postShutdownSchedules.incrementAndGet()
            return PeriodicTask(task, initialDelay, delay, unit) {
                periodicCancellations.incrementAndGet()
                nextPeriodicCancelFailure?.let {
                    nextPeriodicCancelFailure = null
                    throw it
                }
            }.also(periodicTasks::add)
        }

        override fun schedule(task: () -> Unit, delay: Long, unit: TimeUnit) {
            if (delay > 0) {
                nextDelayedOneShotFailure?.let {
                    nextDelayedOneShotFailure = null
                    throw it
                }
            }
            nextOneShotFailure?.let {
                nextOneShotFailure = null
                throw it
            }
            if (isShutdown) postShutdownSchedules.incrementAndGet()
            oneShots += OneShotTask(task, delay, unit)
        }

        override fun shutdownNow() {
            shutdowns.incrementAndGet()
            isShutdown = true
        }

        fun runPeriodic() {
            val active = activePeriodicTasks
            check(active.size <= 1) { "Expected at most one active periodic task, found ${active.size}." }
            active.singleOrNull()?.runOnce()
        }

        fun runOneShot(delay: Long, unit: TimeUnit) {
            val selected = oneShots.firstOrNull { it.delay == delay && it.unit == unit }
                ?: error("No one-shot task scheduled with delay=$delay and unit=$unit.")
            check(oneShots.remove(selected)) { "Selected one-shot task was no longer pending." }
            selected.task()
        }
    }

    private class PeriodicTask(
        private val task: () -> Unit,
        val initialDelay: Long,
        val delay: Long,
        val unit: TimeUnit,
        private val onCancel: () -> Unit,
    ) : ScheduledTask {
        private val cancelled = AtomicBoolean()
        private val ended = AtomicBoolean()
        val isCancelled: Boolean get() = cancelled.get()

        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) onCancel()
        }

        override fun isAlive(): Boolean = !cancelled.get() && !ended.get()

        // Stands in for the outcome scheduleWithFixedDelay produces when a run throws: the handle
        // stays non-null while the schedule itself is finished for good.
        fun kill() {
            ended.set(true)
        }

        // A real fixed-delay schedule ends for good when a run throws, so this fake must too;
        // otherwise a service that lets a throwable escape its task body would look healthy here.
        fun runOnce() {
            try {
                task()
            } catch (error: Throwable) {
                kill()
                throw error
            }
        }
    }

    private data class OneShotTask(val task: () -> Unit, val delay: Long, val unit: TimeUnit)

    private class FakeStateSaver(
        private val beforeSave: () -> Unit = {},
    ) : StateSaver {
        private val saves = AtomicInteger()
        val saveCount: Int get() = saves.get()
        val dispatchThreadValues = CopyOnWriteArrayList<Boolean>()
        val deliveryPendingSnapshots = CopyOnWriteArrayList<List<Boolean>>()
        @Volatile var dispatchThreadProbe: () -> Boolean = { false }
        @Volatile var snapshotProvider: () -> List<ScheduledItem> = { emptyList() }
        @Volatile var failure: RuntimeException? = null

        override fun save() {
            dispatchThreadValues += dispatchThreadProbe()
            beforeSave()
            saves.incrementAndGet()
            deliveryPendingSnapshots += snapshotProvider().map(ScheduledItem::deliveryPending)
            failure?.let { throw it }
        }
    }

    private class FakeUiDispatcher : UiDispatcher {
        private val pending = ConcurrentLinkedQueue<() -> Unit>()
        private val dispatchThread = ThreadLocal.withInitial { false }
        val pendingCount: Int get() = pending.size
        @Volatile var failure: RuntimeException? = null

        override fun isDispatchThread(): Boolean = dispatchThread.get()

        override fun invokeLater(task: () -> Unit) {
            failure?.let {
                failure = null
                throw it
            }
            pending.add(task)
        }

        fun drain() {
            while (drainOne()) {
                // Drain every task queued by the preceding task as well.
            }
        }

        fun drainOne(): Boolean {
            val task = pending.poll() ?: return false
            dispatchThread.set(true)
            try {
                task()
            } finally {
                dispatchThread.set(false)
            }
            return true
        }
    }

    private class FakeAlertNotifier : AlertNotifier {
        val calls = CopyOnWriteArrayList<NotificationCall>()
        @Volatile var createFailure: RuntimeException? = null
        @Volatile var nextDeliverFailure: RuntimeException? = null
        @Volatile var expireOnNextDelivery = false

        override fun create(
            item: ScheduledItem,
            dismiss: () -> Unit,
            expired: () -> Unit,
        ): AlertHandle {
            createFailure?.let { throw it }
            val handle = FakeAlertHandle(
                nextDeliverFailure.also { nextDeliverFailure = null },
                expireOnNextDelivery.also { expireOnNextDelivery = false },
                expired,
            )
            calls += NotificationCall(item, dismiss, expired, handle)
            return handle
        }
    }

    private data class NotificationCall(
        val item: ScheduledItem,
        val dismiss: () -> Unit,
        val expired: () -> Unit,
        val handle: FakeAlertHandle,
    )

    private class FakeAlertHandle(
        private val deliverFailure: RuntimeException? = null,
        private val expireOnDelivery: Boolean = false,
        private val expiredCallback: () -> Unit,
    ) : AlertHandle {
        var delivered = false
        private val expirationStarted = AtomicBoolean()
        private val expirations = AtomicInteger()
        @Volatile var nextExpireFailure: RuntimeException? = null
        val expired: Boolean get() = expirationStarted.get()
        val expireCount: Int get() = expirations.get()

        override fun deliver() {
            deliverFailure?.let { throw it }
            delivered = true
            if (expireOnDelivery) expire()
        }

        override fun expire() {
            expirations.incrementAndGet()
            nextExpireFailure?.let {
                nextExpireFailure = null
                throw it
            }
            if (expirationStarted.compareAndSet(false, true)) expiredCallback()
        }
    }

    private class FakeSound : AlarmSound {
        val plays = CopyOnWriteArrayList<Int>()
        @Volatile var nextPlayFailure: RuntimeException? = null
        @Volatile var nextStopFailure: RuntimeException? = null
        @Volatile var nextDisposeFailure: RuntimeException? = null
        private val stops = AtomicInteger()
        private val disposals = AtomicInteger()
        val stopCount: Int get() = stops.get()
        val disposeCount: Int get() = disposals.get()

        override fun play(volumePercent: Int) {
            nextPlayFailure?.let {
                nextPlayFailure = null
                throw it
            }
            plays += volumePercent
        }

        override fun stop() {
            stops.incrementAndGet()
            nextStopFailure?.let {
                nextStopFailure = null
                throw it
            }
        }

        override fun dispose() {
            disposals.incrementAndGet()
            nextDisposeFailure?.let {
                nextDisposeFailure = null
                throw it
            }
            stop()
        }
    }

    private class ConcurrentTask(name: String, action: () -> Unit) {
        private val failure = AtomicReference<Throwable?>()
        private val thread = Thread(
            { runCatching(action).onFailure(failure::set) },
            name,
        ).apply { start() }

        fun await() {
            thread.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse(thread.isAlive, "Timed out waiting for ${thread.name}.")
            failure.get()?.let { throw AssertionError("${thread.name} failed.", it) }
        }
    }
}
