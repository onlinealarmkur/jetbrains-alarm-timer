package com.onlinealarmkur.jetbrains.service

import com.onlinealarmkur.jetbrains.domain.AlarmEngine
import com.onlinealarmkur.jetbrains.domain.ElapsedTimeSource
import com.onlinealarmkur.jetbrains.domain.ItemStatus
import com.onlinealarmkur.jetbrains.domain.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SuspendAwareElapsedTimeSourceTest {
    @Test
    fun `suspend elapsed time expires timers even if JVM nano time stops and wall time moves backwards`() {
        var nativeMillis = 5_000L
        val source = SuspendAwareElapsedTimeSource(
            nativeClockFactory = { ElapsedTimeSource { nativeMillis } },
            fallback = ElapsedTimeSource { 0 },
            reportFailure = { throw AssertionError(it) },
        )
        val wall = MutableClock(Instant.parse("2026-07-15T12:00:00Z"))
        val engine = AlarmEngine(wall, source)
        engine.startTimer(60_000, "Sleep test")
        wall.advanceMillis(-3_600_000)
        assertEquals(60_000, engine.nearestActiveRemainingMs())
        nativeMillis += 70_000
        assertTrue(engine.checkDueAfterActivation(10_000).single().shouldAlert)
        assertTrue(engine.checkDueAfterActivation(10_000).isEmpty())
    }

    @Test
    fun `long suspend marks a timer missed without an old alert`() {
        var nativeMillis = 0L
        val source = SuspendAwareElapsedTimeSource(
            { ElapsedTimeSource { nativeMillis } }, ElapsedTimeSource { 0 }, { throw AssertionError(it) },
        )
        val engine = AlarmEngine(MutableClock(Instant.EPOCH), source)
        engine.startTimer(1_000, "Old")
        nativeMillis += 600_000
        assertEquals(ItemStatus.MISSED, engine.checkDueAfterActivation(300_000).single().item.status)
    }

    @Test
    fun `native read failure keeps the same epoch and only reports once`() {
        var fallbackMillis = 10L
        var failed = false
        val failures = mutableListOf<Throwable>()
        val source = SuspendAwareElapsedTimeSource(
            { ElapsedTimeSource { if (failed) throw NativeClockException("read failed") else 100_000L } },
            ElapsedTimeSource { fallbackMillis }, failures::add,
        )
        assertEquals(100_000, source.nowMillis())
        failed = true
        fallbackMillis += 25
        assertEquals(100_025, source.nowMillis())
        fallbackMillis += 25
        assertEquals(100_050, source.nowMillis())
        assertEquals(1, failures.size)
    }

    @Test
    fun `unavailable native clock falls back once without repeatedly loading it`() {
        var attempts = 0
        val failures = mutableListOf<Throwable>()
        val source = SuspendAwareElapsedTimeSource(
            { attempts++; throw UnsatisfiedLinkError("unavailable") }, ElapsedTimeSource { 123 }, failures::add,
        )
        assertEquals(123, source.nowMillis())
        assertEquals(123, source.nowMillis())
        assertEquals(1, attempts)
        assertEquals(1, failures.size)
    }

    @Test
    fun `unrelated failures are not hidden`() {
        val source = SuspendAwareElapsedTimeSource(
            { throw AssertionError("test") }, ElapsedTimeSource { 0 }, { error("Must not report") },
        )
        assertThrows(AssertionError::class.java) { source.nowMillis() }
    }

    @Test
    fun `real native backend is available on this supported CI or developer host`() {
        // No sleeps or duration assumptions: this checks the real ABI and monotonic ordering.
        val source = nativeElapsedTimeSource()
        val first = source.nowMillis()
        assertTrue(first >= 0)
        assertTrue(source.nowMillis() >= first)
    }
}
