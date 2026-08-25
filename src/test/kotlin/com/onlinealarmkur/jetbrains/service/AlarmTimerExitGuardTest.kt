package com.onlinealarmkur.jetbrains.service

import com.intellij.openapi.progress.ProcessCanceledException
import com.onlinealarmkur.jetbrains.AlarmTimerBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.CancellationException

class AlarmTimerExitGuardTest {
    @Test
    fun `only an enabled visible check with a near active item warns`() {
        assertEquals(
            ExitWarningDecision.ALLOW_EXIT,
            ExitWarningProjection.decision(enabled = false, headless = false, nearestRemainingMs = EIGHT_MINUTES_MS),
        )
        assertEquals(
            ExitWarningDecision.ALLOW_EXIT,
            ExitWarningProjection.decision(enabled = true, headless = true, nearestRemainingMs = EIGHT_MINUTES_MS),
        )
        assertEquals(
            ExitWarningDecision.ALLOW_EXIT,
            ExitWarningProjection.decision(enabled = true, headless = false, nearestRemainingMs = null),
        )
        assertEquals(
            ExitWarningDecision(warn = true, remainingMs = EIGHT_MINUTES_MS),
            ExitWarningProjection.decision(enabled = true, headless = false, nearestRemainingMs = EIGHT_MINUTES_MS),
        )
    }

    @Test
    fun `the day-long warning threshold includes its own boundary`() {
        val threshold = ExitWarningProjection.MAX_WARN_REMAINING_MS

        assertEquals(24L * 60 * 60_000, threshold)
        assertTrue(warns(threshold))
        assertFalse(warns(threshold + 1))
        assertTrue(warns(0))
    }

    @Test
    fun `a warning the user declines vetoes the exit and one the user accepts does not`() {
        val prompts = mutableListOf<String>()
        val keepRunning = guard(decision = ExitWarningDecision(warn = true, remainingMs = EIGHT_MINUTES_MS)) {
            prompts += it
            false
        }

        assertFalse(keepRunning.canExitApplication())
        assertEquals(listOf(ExitWarningProjection.message(EIGHT_MINUTES_MS)), prompts)

        val exitAnyway = guard(decision = ExitWarningDecision(warn = true, remainingMs = EIGHT_MINUTES_MS)) {
            prompts += it
            true
        }

        assertTrue(exitAnyway.canExitApplication())
        assertEquals(2, prompts.size)
    }

    @Test
    fun `a decision that does not warn never asks the user`() {
        var prompts = 0
        val silentGuard = guard(decision = ExitWarningDecision.ALLOW_EXIT) {
            prompts++
            false
        }

        assertTrue(silentGuard.canExitApplication())
        assertEquals(0, prompts)
    }

    @Test
    fun `a failing check or a failing dialog still lets the user leave`() {
        var prompts = 0
        val brokenCheck = AlarmTimerExitGuard(
            exitCheck = { throw IllegalStateException("Exit check failed.") },
            confirmExit = {
                prompts++
                false
            },
        )
        val brokenDialog = AlarmTimerExitGuard(
            exitCheck = { ExitWarningDecision(warn = true, remainingMs = EIGHT_MINUTES_MS) },
            confirmExit = { throw IllegalStateException("Dialog failed.") },
        )

        assertTrue(brokenCheck.canExitApplication())
        assertEquals(0, prompts)
        assertTrue(brokenDialog.canExitApplication())
    }

    @Test
    fun `platform cancellation from the exit check propagates unchanged`() {
        val cancellation = ProcessCanceledException()
        val guard = AlarmTimerExitGuard(
            exitCheck = { throw cancellation },
            confirmExit = { true },
        )

        val thrown = assertThrows(ProcessCanceledException::class.java, guard::canExitApplication)

        assertSame(cancellation, thrown)
    }

    @Test
    fun `standard cancellation from the exit check propagates unchanged`() {
        val cancellation = CancellationException("Exit check cancelled.")
        val guard = AlarmTimerExitGuard(
            exitCheck = { throw cancellation },
            confirmExit = { true },
        )

        val thrown = assertThrows(CancellationException::class.java, guard::canExitApplication)

        assertSame(cancellation, thrown)
    }

    @Test
    fun `jvm error from the confirmation callback propagates unchanged`() {
        val failure = AssertionError("Confirmation failed.")
        val guard = AlarmTimerExitGuard(
            exitCheck = { ExitWarningDecision(warn = true, remainingMs = EIGHT_MINUTES_MS) },
            confirmExit = { throw failure },
        )

        val thrown = assertThrows(AssertionError::class.java, guard::canExitApplication)

        assertSame(failure, thrown)
    }

    @Test
    fun `the warning quotes the nearest remaining time in the live countdown format`() {
        assertEquals(
            AlarmTimerBundle.messageFor(Locale.ENGLISH, "dialog.exit.warning.message", "08:00"),
            ExitWarningProjection.message(EIGHT_MINUTES_MS),
        )
        assertEquals(
            AlarmTimerBundle.messageFor(Locale.ENGLISH, "dialog.exit.warning.message", "2h 00m"),
            ExitWarningProjection.message(2 * 60 * 60_000L),
        )
        assertEquals(
            AlarmTimerBundle.messageFor(Locale.ENGLISH, "dialog.exit.warning.title"),
            ExitWarningProjection.title(),
        )
    }

    private fun warns(nearestRemainingMs: Long): Boolean =
        ExitWarningProjection.decision(enabled = true, headless = false, nearestRemainingMs = nearestRemainingMs).warn

    private fun guard(
        decision: ExitWarningDecision,
        confirmExit: (String) -> Boolean,
    ) = AlarmTimerExitGuard(exitCheck = { decision }, confirmExit = confirmExit)

    private companion object {
        const val EIGHT_MINUTES_MS = 8 * 60_000L
    }
}
