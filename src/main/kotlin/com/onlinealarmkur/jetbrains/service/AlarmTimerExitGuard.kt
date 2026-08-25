package com.onlinealarmkur.jetbrains.service

import com.intellij.openapi.application.ApplicationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.onlinealarmkur.jetbrains.AlarmTimerBundle
import com.onlinealarmkur.jetbrains.domain.Formatters
import java.util.concurrent.CancellationException

/**
 * What the exit check concluded: whether to ask at all, and the remaining time the question quotes.
 * Kept separate from the listener so the whole decision is testable without a platform fixture.
 */
internal data class ExitWarningDecision(
    val warn: Boolean,
    val remainingMs: Long,
) {
    companion object {
        val ALLOW_EXIT = ExitWarningDecision(warn = false, remainingMs = 0)
    }
}

/**
 * The pure half of the exit warning: it decides, it does not ask.
 *
 * The plugin documents everywhere that alarms and timers alert only while the IDE runs, so quitting
 * with something about to fire is the one moment that caveat becomes actionable.
 */
internal object ExitWarningProjection {
    // A user holding a three-day alarm must not be asked to confirm every single quit, so only an
    // item inside one day counts as "about to fire". This is a product constant, not a setting.
    const val MAX_WARN_REMAINING_MS = 24L * 60 * 60_000

    /**
     * PAUSED timers and items awaiting delivery never reach this call: `nearestRemainingMs` reports
     * ACTIVE items only. Pausing already states the user's intent, and a pending delivery re-fires
     * on the next start by design, so neither is a reason to hold the IDE open.
     */
    fun decision(enabled: Boolean, headless: Boolean, nearestRemainingMs: Long?): ExitWarningDecision {
        if (!enabled || headless) return ExitWarningDecision.ALLOW_EXIT
        val remainingMs = nearestRemainingMs ?: return ExitWarningDecision.ALLOW_EXIT
        if (remainingMs > MAX_WARN_REMAINING_MS) return ExitWarningDecision.ALLOW_EXIT
        return ExitWarningDecision(warn = true, remainingMs = remainingMs)
    }

    fun title(): String = AlarmTimerBundle.message("dialog.exit.warning.title")

    fun message(remainingMs: Long): String =
        AlarmTimerBundle.message("dialog.exit.warning.message", Formatters.liveDuration(remainingMs))
}

/**
 * Asks before the IDE closes while an alarm or timer is about to fire.
 *
 * `canExitApplication` lives on [ApplicationListener], which carries no message-bus `Topic` and so
 * cannot be declared in `plugin.xml`. [AlarmTimerService] adds it programmatically instead, with
 * itself as the parent [com.intellij.openapi.Disposable].
 *
 * `canRestartApplication()` delegates to `canExitApplication()` by default, so an IDE restart also
 * asks. That is intended for this version: a restart closes the IDE just the same.
 */
internal class AlarmTimerExitGuard(
    private val exitCheck: () -> ExitWarningDecision,
    private val confirmExit: (String) -> Boolean,
) : ApplicationListener {
    constructor() : this(::productionExitCheck, ::productionConfirmExit)

    /**
     * Returning `false` vetoes the exit, so an ordinary failure in the optional warning answers
     * `true`: a broken decision or dialog must never trap a user inside the IDE. Platform control
     * flow and JVM errors are not warning failures and must keep propagating.
     */
    override fun canExitApplication(): Boolean = try {
        val decision = exitCheck()
        !decision.warn || confirmExit(ExitWarningProjection.message(decision.remainingMs))
    } catch (error: Throwable) {
        if (error is ControlFlowException || error is CancellationException || error is Error) throw error
        // A warning at most: an error-level log throws outside a running IDE and would defeat the
        // very guard this catch exists to provide.
        LOG.warn("Failed to check active alarms and timers before exit; allowing the IDE to close.", error)
        true
    }

    private companion object {
        val LOG = Logger.getInstance(AlarmTimerExitGuard::class.java)
    }
}

private fun productionExitCheck(): ExitWarningDecision {
    val service = AlarmTimerService.getInstance()
    return ExitWarningProjection.decision(
        enabled = service.settings().warnOnExitWithPendingItems,
        // Nothing may block a scripted or headless shutdown, which has nobody to answer the dialog.
        headless = ApplicationManager.getApplication().isHeadlessEnvironment,
        nearestRemainingMs = service.nearestActiveRemainingMs(),
    )
}

// The platform reaches its exit check from the event dispatch thread, so a modal question is safe
// here. Passing no owner and no button captions keeps the IDE's own localized Yes and No.
private fun productionConfirmExit(message: String): Boolean = Messages.showYesNoDialog(
    message,
    ExitWarningProjection.title(),
    null,
) == Messages.YES
