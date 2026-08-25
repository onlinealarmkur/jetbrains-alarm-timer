package com.onlinealarmkur.jetbrains.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.onlinealarmkur.jetbrains.domain.AlarmEngine
import com.onlinealarmkur.jetbrains.domain.AlarmTimerSettings
import com.onlinealarmkur.jetbrains.domain.AlarmTimerSettingsPolicy
import com.onlinealarmkur.jetbrains.domain.DueItem
import com.onlinealarmkur.jetbrains.domain.ElapsedTimeSource
import com.onlinealarmkur.jetbrains.domain.ItemStatus
import com.onlinealarmkur.jetbrains.domain.LIVE_NEAR_THRESHOLD_MS
import com.onlinealarmkur.jetbrains.domain.ScheduledItem
import com.onlinealarmkur.jetbrains.domain.SystemElapsedTimeSource
import com.onlinealarmkur.jetbrains.notifications.SoundPlayer
import com.onlinealarmkur.jetbrains.persistence.PersistedState
import com.onlinealarmkur.jetbrains.persistence.StateCodec
import com.onlinealarmkur.jetbrains.persistence.ValidatedState
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CancellationException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class AlarmTimerEvent {
    STRUCTURE_CHANGED,
    CLOCK_PULSE,
}

private enum class DueCheckContext {
    LIVE_POLL,
    ACTIVATION_CHECK,
    STARTUP_RECOVERY,
}

internal object LivePollingCadence {
    const val NEAR_INTERVAL_SECONDS = 1L
    const val FAR_INTERVAL_SECONDS = 60L

    fun intervalSeconds(nearestActiveRemainingMs: Long?): Long? = when {
        nearestActiveRemainingMs == null -> null
        nearestActiveRemainingMs > LIVE_NEAR_THRESHOLD_MS -> FAR_INTERVAL_SECONDS
        else -> NEAR_INTERVAL_SECONDS
    }
}

@Service(Service.Level.APP)
@State(name = "com.onlinealarmkur.jetbrains.AlarmTimerState", storages = [Storage("alarm-timer.xml", roamingType = RoamingType.DISABLED)])
class AlarmTimerService internal constructor(
    private val clock: Clock,
    private val elapsedTimeSource: ElapsedTimeSource,
    private val scheduler: AlarmScheduler,
    private val soundPlayer: AlarmSound,
    private val stateSaver: StateSaver,
    private val uiDispatcher: UiDispatcher,
    private val alertNotifier: AlertNotifier,
    uiReadyInitially: Boolean = true,
) : PersistentStateComponent<PersistedState>, Disposable {
    constructor() : this(
        Clock.systemDefaultZone(),
        SystemElapsedTimeSource,
        ExecutorAlarmScheduler(),
        SoundPlayer(),
        PlatformStateSaver(),
        PlatformUiDispatcher(),
        PlatformAlertNotifier(),
        ApplicationManager.getApplication().isActive,
    ) {
        installExitGuard()
    }

    private val engine = AlarmEngine(clock, elapsedTimeSource)
    private val listeners = CopyOnWriteArrayList<ListenerRegistration>()
    private val disposed = AtomicBoolean()
    private val clockPulseQueued = AtomicBoolean()
    private val lifecycleLock = Any()
    // Serializes a due-state transition with wholesale PersistentStateComponent replacement without
    // making disposal wait for clock or engine work. The lifecycle lock remains deliberately short.
    private val stateReplacementLock = Any()
    private val activeNotifications = linkedMapOf<String, AlertRecord>()
    private val currentAlertSoundOwnerIds = linkedSetOf<String>()
    private var soundPreviewActive = false
    private var immediateCheckScheduled = false
    private var pendingImmediateCheckContext: DueCheckContext? = null
    private var uiReadyCheckReceived = uiReadyInitially
    private var deliveryAcknowledgementSaveQueued = false
    private var deliveryAcknowledgementSaveDirty = false
    private var periodicCheck: ScheduledTask? = null
    private var armedPollingIntervalSeconds: Long? = null
    private var pollingGeneration = 0L
    private var lifecycleGeneration = 0L
    private var soundPlaybackGeneration = 0L
    @Volatile private var settings = AlarmTimerSettings()

    // Holds the stored state whenever this build cannot represent it: a newer schema, or a file the
    // decoder could not read. While it is set, getState() rewrites that original state instead of
    // the empty in-memory one, so a save can never destroy items this build failed to understand.
    // Only a successful change to the items themselves clears it. A settings change does not,
    // because a lost volume tweak is recoverable and a lost file of alarms and timers is not.
    @Volatile private var readOnlyPersistedState: PersistedState? = null

    fun items(): List<ScheduledItem> = engine.snapshot()
    fun settings(): AlarmTimerSettings = settings
    fun now(): Long = clock.millis()
    fun remainingMs(id: String): Long = engine.remainingMs(id)
    fun hasActiveAlerts(): Boolean = synchronized(lifecycleLock) { activeNotifications.isNotEmpty() }
    internal fun nearestActive(): Pair<ScheduledItem, Long>? = engine.nearestActive()
    internal fun nearestActiveRemainingMs(): Long? = engine.nearestActiveRemainingMs()

    // ApplicationListener has no message-bus Topic, so the exit hook cannot be declared in
    // plugin.xml: only listeners added here are consulted by the IDE's exit pipeline. This service
    // is the parent Disposable, so the hook is removed with it. Deliberately outside lifecycleLock,
    // because Disposer registration takes locks of its own and this needs no lifecycle state.
    private fun installExitGuard() {
        try {
            ApplicationManager.getApplication().addApplicationListener(AlarmTimerExitGuard(), this)
        } catch (error: Throwable) {
            rethrowControlFlowOrError(error)
            LOG.warn("Could not install the exit warning for active alarms and timers.", error)
        }
    }

    internal fun addListener(
        parent: Disposable,
        // Queried from the scheduler thread; callers must keep this thread-safe and inexpensive.
        wantsClockPulses: () -> Boolean = { false },
        listener: (AlarmTimerEvent) -> Unit,
    ) {
        val registration = ListenerRegistration(wantsClockPulses, listener)
        listeners += registration
        Disposer.register(parent) {
            registration.active.set(false)
            listeners -= registration
        }
    }

    fun scheduleAlarm(targetEpochMs: Long, label: String) = changed { engine.scheduleAlarm(targetEpochMs, label) }
    fun startTimer(durationMs: Long, label: String) = changed { engine.startTimer(durationMs, label) }
    fun editAlarm(expected: ScheduledItem, targetEpochMs: Long, label: String) =
        changedIf { engine.editAlarm(expected, targetEpochMs, label) }
    fun pause(id: String) = changedIf { engine.pause(id) }
    fun resume(id: String) = changedIf { engine.resume(id) }
    fun restart(id: String) = changedIf { engine.restart(id) }
    fun cancel(id: String): Boolean {
        val removedIds = engine.cancel(id)
        if (removedIds.isEmpty()) return false
        resumePersistingCurrentState()
        reconcilePolling()
        retireAlerts(removedIds)
        fireStructureChanged()
        return true
    }

    fun clearCompleted() {
        val removedIds = engine.clearCompleted()
        if (removedIds.isNotEmpty()) resumePersistingCurrentState()
        retireAlerts(removedIds)
        reconcilePolling()
        fireStructureChanged()
    }

    fun updateSettings(next: AlarmTimerSettings) {
        val sanitized = AlarmTimerSettingsPolicy.sanitizeRuntime(next)
        synchronized(lifecycleLock) {
            val stopActiveAlertSound =
                !sanitized.soundEnabled &&
                    (currentAlertSoundOwnerIds.isNotEmpty() || soundPreviewActive)
            settings = sanitized
            if (stopActiveAlertSound) {
                stopCurrentSoundSafely("sound was disabled")
            }
        }
        // Deliberately does NOT clear the read-only latch. A settings change must never be able to
        // discard a stored file this build cannot read: losing a volume tweak is recoverable,
        // overwriting every stored alarm and timer is not.
        fireStructureChanged()
    }

    fun dismissAllAlerts() {
        val notifications = synchronized(lifecycleLock) {
            val retired = activeNotifications.values.map(AlertRecord::handle)
            activeNotifications.clear()
            stopCurrentSoundSafely("all alerts were dismissed")
            retired
        }
        expireAllSafely(notifications, "all alerts were dismissed")
    }

    fun testSound(enabled: Boolean, volumePercent: Int) {
        synchronized(lifecycleLock) {
            if (disposed.get() || !enabled || currentAlertSoundOwnerIds.isNotEmpty()) return
            playPreviewSound(
                timeoutSeconds = 2,
                volumePercent = volumePercent.coerceIn(
                    AlarmTimerSettingsPolicy.MIN_VOLUME_PERCENT,
                    AlarmTimerSettingsPolicy.MAX_VOLUME_PERCENT,
                ),
            )
        }
    }

    internal fun requestStartupRecovery() {
        requestImmediateCheck(DueCheckContext.STARTUP_RECOVERY)
    }

    internal fun requestActivationCheck() {
        requestImmediateCheck(DueCheckContext.ACTIVATION_CHECK)
    }

    internal fun requestUiReadyCheck() {
        val context = synchronized(lifecycleLock) {
            if (disposed.get()) return
            if (uiReadyCheckReceived) {
                DueCheckContext.ACTIVATION_CHECK
            } else {
                uiReadyCheckReceived = true
                DueCheckContext.STARTUP_RECOVERY
            }
        }
        requestImmediateCheck(context)
    }

    internal fun confirmUiReady() {
        val becameReady = synchronized(lifecycleLock) {
            if (disposed.get() || uiReadyCheckReceived) {
                false
            } else {
                uiReadyCheckReceived = true
                true
            }
        }
        if (!becameReady) return
        reconcilePolling()
        requestImmediateCheck(DueCheckContext.STARTUP_RECOVERY)
    }

    private fun requestImmediateCheck(context: DueCheckContext) {
        synchronized(lifecycleLock) {
            if (disposed.get()) return
            pendingImmediateCheckContext = coalesceImmediateCheck(pendingImmediateCheckContext, context)
            if (immediateCheckScheduled) return
            immediateCheckScheduled = true
            try {
                scheduler.schedule(
                    ::runImmediateChecks,
                    0,
                    TimeUnit.MILLISECONDS,
                )
            } catch (error: Throwable) {
                // Any throwable, not only a rejection, must clear the latch. A latch left standing
                // would drop every later activation and startup check for the life of the process.
                immediateCheckScheduled = false
                pendingImmediateCheckContext = null
                if (!disposed.get()) {
                    logSchedulingFailure(
                        "Could not queue an immediate alarm and timer check.",
                        "Unexpected failure while queueing an immediate alarm and timer check.",
                        error,
                    )
                }
            }
        }
    }

    private fun runImmediateChecks() {
        var inFlightContext: DueCheckContext? = null
        try {
            while (true) {
                val context = synchronized(lifecycleLock) {
                    val next = pendingImmediateCheckContext
                    pendingImmediateCheckContext = null
                    if (next == null) immediateCheckScheduled = false
                    next
                } ?: return
                inFlightContext = context
                tickSafely(context)
                inFlightContext = null
            }
        } catch (error: Throwable) {
            val interruptedContext = inFlightContext
            synchronized(lifecycleLock) {
                if (!disposed.get() && interruptedContext != null) {
                    pendingImmediateCheckContext = coalesceImmediateCheck(
                        pendingImmediateCheckContext,
                        interruptedContext,
                    )
                }
                immediateCheckScheduled = false
            }
            // Preserve platform cancellation semantics, but restore ownership first and arrange an
            // independent retry so a transient cancellation cannot disable lifecycle recovery.
            if (interruptedContext != null && isCancellation(error)) {
                try {
                    requestImmediateCheck(interruptedContext)
                } catch (retryError: Throwable) {
                    if (retryError !== error) error.addSuppressed(retryError)
                }
            }
            throw error
        }
    }

    override fun getState(): PersistedState =
        readOnlyPersistedState ?: StateCodec.encode(settings, engine.persistenceSnapshot())

    override fun loadState(state: PersistedState) {
        val decoded = try {
            StateCodec.decode(state)
        } catch (error: Throwable) {
            rethrowControlFlowOrError(error)
            // One unreadable field must not cost the user the whole file. A logger that reports
            // errors by throwing must not stop the read-only latch below from being set either.
            runCatching {
                LOG.error("Failed to decode persisted alarm and timer state; keeping the stored file untouched.", error)
            }
            null
        }
        val restored = decoded ?: ValidatedState(AlarmTimerSettingsPolicy.DEFAULTS, emptyList())
        val retiredNotifications = synchronized(stateReplacementLock) {
            synchronized(lifecycleLock) {
                if (disposed.get()) return
                // PersistentStateComponent can reload this application state after the backing XML
                // changes externally. No balloon, sound owner, or queued UI callback from the
                // replaced state may survive this generation boundary.
                lifecycleGeneration++
                val retired = activeNotifications.values.map(AlertRecord::handle)
                activeNotifications.clear()
                if (currentAlertSoundOwnerIds.isNotEmpty() || soundPreviewActive) {
                    stopCurrentSoundSafely("persisted state was reloaded")
                }
                readOnlyPersistedState = state.takeIf { decoded == null || decoded.foreignSchema }
                settings = restored.settings
                engine.restore(restored.items)
                retired
            }
        }
        expireAllSafely(retiredNotifications, "persisted state was reloaded")
        reconcilePolling()
        if (restored.items.isNotEmpty() && synchronized(lifecycleLock) { uiReadyCheckReceived }) {
            requestStartupRecovery()
        }
        fireStructureChanged()
    }

    override fun dispose() {
        val cleanup = synchronized(lifecycleLock) {
            if (!disposed.compareAndSet(false, true)) return
            lifecycleGeneration++
            soundPlaybackGeneration++
            val retired = activeNotifications.values.map(AlertRecord::handle)
            activeNotifications.clear()
            currentAlertSoundOwnerIds.clear()
            soundPreviewActive = false
            deliveryAcknowledgementSaveQueued = false
            deliveryAcknowledgementSaveDirty = false
            val retiredPeriodicCheck = periodicCheck
            periodicCheck = null
            armedPollingIntervalSeconds = null
            listeners.clear()
            DisposalCleanup(retired, retiredPeriodicCheck)
        }
        var propagatedFailure: Throwable? = null
        propagatedFailure = captureCleanupFailure(
            propagatedFailure,
            "Failed to dispose alert sound resources.",
        ) { soundPlayer.dispose() }
        cleanup.periodicCheck?.let { periodicCheck ->
            propagatedFailure = captureCleanupFailure(
                propagatedFailure,
                "Failed to cancel live alarm and timer polling during disposal.",
            ) { periodicCheck.cancel() }
        }
        propagatedFailure = captureCleanupFailure(
            propagatedFailure,
            "Failed to shut down the alarm and timer scheduler.",
        ) { scheduler.shutdownNow() }
        cleanup.notifications.forEach { notification ->
            propagatedFailure = captureCleanupFailure(
                propagatedFailure,
                "Failed to expire an alert handle after the alarm and timer service was disposed.",
            ) { notification.expire() }
        }
        propagatedFailure?.let { throw it }
    }

    private fun tickSafely(context: DueCheckContext) {
        val check = try {
            synchronized(stateReplacementLock) {
                val generation = activeLifecycleGeneration() ?: return
                val configuredGracePeriodMs = settings.overdueGracePeriodMs
                val activeDueGracePeriodMs = when (context) {
                    DueCheckContext.LIVE_POLL,
                    DueCheckContext.ACTIVATION_CHECK,
                    -> maxOf(configuredGracePeriodMs, LIVE_CHECK_TOLERANCE_MS)
                    DueCheckContext.STARTUP_RECOVERY -> configuredGracePeriodMs
                }
                val due = when (context) {
                    DueCheckContext.LIVE_POLL -> engine.checkDue(activeDueGracePeriodMs)
                    DueCheckContext.ACTIVATION_CHECK -> engine.checkDueAfterActivation(activeDueGracePeriodMs)
                    DueCheckContext.STARTUP_RECOVERY -> engine.checkDueAfterStartup(activeDueGracePeriodMs)
                }
                val recovered = when (context) {
                    DueCheckContext.LIVE_POLL -> emptyList()
                    DueCheckContext.ACTIVATION_CHECK -> engine.recoverPendingAfterActivation()
                    DueCheckContext.STARTUP_RECOVERY -> engine.recoverPendingAfterStartup(configuredGracePeriodMs)
                }
                DueCheckBatch(generation, due, recovered)
            }
        } catch (error: Throwable) {
            rethrowControlFlowOrError(error)
            LOG.warn("Failed to check due alarms and timers.", error)
            return
        }
        val generation = check.generation
        val due = check.due
        val recovered = check.recovered
        try {
            reconcilePolling()
        } catch (error: Throwable) {
            rethrowControlFlowOrError(error)
            LOG.warn("Failed to reconcile live alarm and timer polling after a due check.", error)
        }
        if (!isLifecycleActive(generation)) return
        val changed = due.isNotEmpty() || recovered.any { !it.shouldAlert }
        val alerts = when (context) {
            DueCheckContext.LIVE_POLL -> due.filter { it.shouldAlert }
            DueCheckContext.ACTIVATION_CHECK,
            DueCheckContext.STARTUP_RECOVERY,
            -> recovered.filter { it.shouldAlert }
        }
        if (changed) {
            try {
                stateSaver.save()
            } catch (error: Throwable) {
                rethrowControlFlowOrError(error)
                LOG.warn(
                    "Failed to persist due item transition(s); in-process alert delivery will continue.",
                    error,
                )
            }
        }
        if (!isLifecycleActive(generation)) return
        for (outcome in alerts) {
            if (!isLifecycleActive(generation)) return
            try {
                uiDispatcher.invokeLater { showNotification(outcome.item, generation) }
            } catch (error: Throwable) {
                rethrowControlFlowOrError(error)
                LOG.warn("Failed to queue a due alert for delivery; it remains pending for lifecycle recovery.", error)
            }
        }
        if (!isLifecycleActive(generation)) return
        try {
            if (due.isEmpty() && recovered.isEmpty()) fireClockPulse() else fireStructureChanged()
        } catch (error: Throwable) {
            rethrowControlFlowOrError(error)
            LOG.warn("Failed to queue alarm and timer state listener delivery.", error)
        }
    }

    private fun showNotification(item: ScheduledItem, generation: Long) {
        var acknowledged = false
        var replacedNotification: AlertHandle? = null
        synchronized(lifecycleLock) {
            if (disposed.get() || generation != lifecycleGeneration) return
            val current = engine.item(item.id)
            if (current?.status != ItemStatus.COMPLETED ||
                !current.deliveryPending ||
                current.firedAtEpochMs != item.firedAtEpochMs
            ) return
            val token = Any()
            val registration = AlertRegistration()
            val notification = try {
                alertNotifier.create(
                    item,
                    { dismissAlert(item.id, token) },
                    {
                        synchronized(lifecycleLock) {
                            if (!registration.completed) {
                                registration.expired = true
                            } else {
                                alertExpiredLocked(item.id, token)
                            }
                        }
                    },
                )
            } catch (error: Throwable) {
                rethrowControlFlowOrError(error)
                LOG.warn("Failed to create a due alert; it remains pending for lifecycle recovery.", error)
                return
            }
            if (registration.expired) {
                registration.completed = true
                return@synchronized
            }
            try {
                notification.deliver()
            } catch (error: Throwable) {
                registration.completed = true
                expireSafely(notification, "delivery failed")
                rethrowControlFlowOrError(error)
                LOG.warn("Failed to deliver a due alert; it remains pending for lifecycle recovery.", error)
                return@synchronized
            }
            if (registration.expired) {
                registration.completed = true
                return@synchronized
            }
            replacedNotification = activeNotifications.put(item.id, AlertRecord(notification, token))?.handle
            registration.completed = true
            acknowledged = engine.acknowledgeDelivery(item.id, item.firedAtEpochMs)
            if (acknowledged) {
                // A crash after delivery returns but before this save may duplicate the alert on recovery.
                // Queue it before sound, whose platform control flow and JVM errors must propagate.
                requestDeliveryAcknowledgementSave()
            }
            if (acknowledged && settings.soundEnabled) {
                try {
                    playAlertSound(item.id, 30)
                } catch (error: Throwable) {
                    rethrowControlFlowOrError(error)
                    LOG.warn("Failed to play sound for a delivered alert.", error)
                }
            }
        }
        replacedNotification?.let { expireSafely(it, "a newer alert replaced it") }
    }

    private fun requestDeliveryAcknowledgementSave() {
        synchronized(lifecycleLock) {
            if (disposed.get()) return
            deliveryAcknowledgementSaveDirty = true
            if (deliveryAcknowledgementSaveQueued) return
            deliveryAcknowledgementSaveQueued = true
            try {
                scheduler.schedule(
                    ::runDeliveryAcknowledgementSaves,
                    0,
                    TimeUnit.MILLISECONDS,
                )
            } catch (error: Throwable) {
                // The persisted pending flag is the fallback when this save never runs, but the
                // latch must still come down or no later acknowledgment could ever be queued.
                deliveryAcknowledgementSaveQueued = false
                if (!disposed.get()) {
                    logSchedulingFailure(
                        "Could not queue a delivered alert acknowledgment save.",
                        "Unexpected failure while queueing a delivered alert acknowledgment save.",
                        error,
                    )
                }
            }
        }
    }

    private fun runDeliveryAcknowledgementSaves() {
        while (true) {
            val shouldSave = synchronized(lifecycleLock) {
                when {
                    disposed.get() -> {
                        deliveryAcknowledgementSaveQueued = false
                        deliveryAcknowledgementSaveDirty = false
                        false
                    }
                    !deliveryAcknowledgementSaveDirty -> {
                        deliveryAcknowledgementSaveQueued = false
                        false
                    }
                    else -> {
                        deliveryAcknowledgementSaveDirty = false
                        true
                    }
                }
            }
            if (!shouldSave) return

            try {
                stateSaver.save()
            } catch (error: Throwable) {
                synchronized(lifecycleLock) {
                    deliveryAcknowledgementSaveQueued = false
                    if (disposed.get()) {
                        deliveryAcknowledgementSaveDirty = false
                    } else {
                        // The acknowledgement is already reflected in memory. Keep it dirty until a
                        // later save succeeds, and independently retry transient cancellation.
                        deliveryAcknowledgementSaveDirty = true
                    }
                }
                if (isCancellation(error)) {
                    try {
                        requestDeliveryAcknowledgementSave()
                    } catch (retryError: Throwable) {
                        if (retryError !== error) error.addSuppressed(retryError)
                    }
                }
                rethrowControlFlowOrError(error)
                if (!disposed.get()) LOG.warn("Failed to persist a delivered alert acknowledgment.", error)
                return
            }

            val saveAgain = synchronized(lifecycleLock) {
                when {
                    disposed.get() -> {
                        deliveryAcknowledgementSaveQueued = false
                        deliveryAcknowledgementSaveDirty = false
                        false
                    }
                    deliveryAcknowledgementSaveDirty -> true
                    else -> {
                        deliveryAcknowledgementSaveQueued = false
                        false
                    }
                }
            }
            if (!saveAgain) return
        }
    }

    private fun activeLifecycleGeneration(): Long? = synchronized(lifecycleLock) {
        lifecycleGeneration.takeUnless { disposed.get() }
    }

    private fun isLifecycleActive(generation: Long): Boolean = synchronized(lifecycleLock) {
        !disposed.get() && generation == lifecycleGeneration
    }

    private fun dismissAlert(itemId: String, token: Any) {
        val notification = synchronized(lifecycleLock) {
            val current = activeNotifications[itemId]
            if (current?.token !== token) return@synchronized null
            val retired = activeNotifications.remove(itemId)?.handle
            val removedSoundOwner = currentAlertSoundOwnerIds.remove(itemId)
            if (removedSoundOwner && currentAlertSoundOwnerIds.isEmpty()) {
                stopCurrentSoundSafely("an alert was dismissed")
            }
            retired
        }
        notification?.let { expireSafely(it, "its alert was dismissed") }
    }

    private fun alertExpiredLocked(itemId: String, token: Any) {
        val current = activeNotifications[itemId]
        if (current?.token !== token) return
        activeNotifications.remove(itemId)
        val removedSoundOwner = currentAlertSoundOwnerIds.remove(itemId)
        if (removedSoundOwner && currentAlertSoundOwnerIds.isEmpty()) {
            stopCurrentSoundSafely("the final visible alert expired")
        }
    }

    private fun retireAlerts(itemIds: Set<String>) {
        if (itemIds.isEmpty()) return
        val notifications = synchronized(lifecycleLock) {
            val retired = itemIds.mapNotNull { activeNotifications.remove(it)?.handle }
            val removedSoundOwner = currentAlertSoundOwnerIds.removeAll(itemIds)
            if (removedSoundOwner && currentAlertSoundOwnerIds.isEmpty()) {
                stopCurrentSoundSafely("alert items were retired")
            }
            retired
        }
        expireAllSafely(notifications, "alert items were retired")
    }

    private fun fireStructureChanged() {
        val generation = activeLifecycleGeneration() ?: return
        if (uiDispatcher.isDispatchThread()) notifyListeners(AlarmTimerEvent.STRUCTURE_CHANGED, generation)
        else uiDispatcher.invokeLater { notifyListeners(AlarmTimerEvent.STRUCTURE_CHANGED, generation) }
    }

    private fun fireClockPulse() {
        val generation = activeLifecycleGeneration() ?: return
        if (!engine.hasActiveItems()) return
        if (!hasInterestedClockPulseListener(generation) || !clockPulseQueued.compareAndSet(false, true)) return
        if (!isLifecycleActive(generation)) {
            clockPulseQueued.set(false)
            return
        }
        try {
            uiDispatcher.invokeLater { notifyClockPulseListeners(generation) }
        } catch (error: Throwable) {
            clockPulseQueued.set(false)
            throw error
        }
    }

    private fun notifyClockPulseListeners(generation: Long) {
        clockPulseQueued.set(false)
        if (!engine.hasActiveItems()) return
        notifyListeners(AlarmTimerEvent.CLOCK_PULSE, generation)
    }

    private fun hasInterestedClockPulseListener(generation: Long): Boolean {
        for (registration in listeners.toList()) {
            if (!isLifecycleActive(generation)) return false
            if (!registration.active.get()) continue
            if (!wantsClockPulses(registration)) continue
            // One registration that lost interest or was disposed during this scan says nothing
            // about the rest, so keep looking instead of reporting no interest for everyone.
            if (isLifecycleActive(generation) && registration.active.get()) return true
        }
        return false
    }

    private fun notifyListeners(event: AlarmTimerEvent, generation: Long) {
        for (registration in listeners.toList()) {
            if (!isLifecycleActive(generation)) return
            if (!registration.active.get()) continue
            if (event == AlarmTimerEvent.CLOCK_PULSE && !wantsClockPulses(registration)) continue
            if (!isLifecycleActive(generation) || !registration.active.get()) continue
            try {
                registration.listener(event)
            } catch (error: Throwable) {
                rethrowControlFlowOrError(error)
                LOG.warn("Alarm timer listener failed while handling $event.", error)
            }
        }
    }

    private fun wantsClockPulses(registration: ListenerRegistration): Boolean = try {
        registration.wantsClockPulses()
    } catch (error: Throwable) {
        rethrowControlFlowOrError(error)
        LOG.warn("Alarm timer listener interest check failed for ${AlarmTimerEvent.CLOCK_PULSE}.", error)
        false
    }

    // Reports whether a stop is now pending for the playback it started. A false answer means this
    // generation would play without any bound, so the caller has to end it another way.
    private fun playSound(timeoutSeconds: Long, volumePercent: Int = settings.volumePercent): Boolean {
        val playbackGeneration = ++soundPlaybackGeneration
        soundPlayer.play(volumePercent)
        return scheduleSoundStop(playbackGeneration, timeoutSeconds)
    }

    private fun playPreviewSound(timeoutSeconds: Long, volumePercent: Int) {
        val playbackGeneration = ++soundPlaybackGeneration
        try {
            soundPlayer.play(volumePercent)
            soundPreviewActive = true
        } catch (error: Throwable) {
            if (playbackGeneration == soundPlaybackGeneration) {
                soundPreviewActive = false
                stopCurrentSoundSafely("preview playback failed")
            }
            throw error
        }
        if (!scheduleSoundStop(playbackGeneration, timeoutSeconds)) {
            stopCurrentSoundSafely("the preview timeout could not be scheduled")
        }
    }

    private fun playAlertSound(itemId: String, timeoutSeconds: Long) {
        val priorPlaybackGeneration = soundPlaybackGeneration
        soundPreviewActive = false
        val addedSoundOwner = currentAlertSoundOwnerIds.add(itemId)
        val stopScheduled = try {
            playSound(timeoutSeconds)
        } catch (error: Throwable) {
            releaseUnstoppablePlayback(itemId, addedSoundOwner, priorPlaybackGeneration)
            throw error
        }
        if (!stopScheduled) releaseUnstoppablePlayback(itemId, addedSoundOwner, priorPlaybackGeneration)
    }

    // Gives up the playback generation this alert consumed, because nothing will ever stop it:
    // either play never started, or its timeout could not be queued. An earlier owner whose own
    // stop is still pending takes the shared sound back and bounds it at that owner's timeout, so
    // one alert's failure never silences another. With no owner left, nothing could stop the sound
    // at all, so it stops now. Rewinding is safe only because the abandoned generation never got a
    // stop task of its own and therefore cannot leave a stale one behind.
    private fun releaseUnstoppablePlayback(
        itemId: String,
        addedSoundOwner: Boolean,
        priorPlaybackGeneration: Long,
    ) {
        if (addedSoundOwner) currentAlertSoundOwnerIds.remove(itemId)
        if (currentAlertSoundOwnerIds.isNotEmpty()) {
            soundPlaybackGeneration = priorPlaybackGeneration
        } else {
            stopCurrentSoundSafely("alert playback could not be bounded")
        }
    }

    private fun stopCurrentSound() {
        currentAlertSoundOwnerIds.clear()
        soundPreviewActive = false
        soundPlaybackGeneration++
        soundPlayer.stop()
    }

    private fun stopCurrentSoundSafely(reason: String) {
        cleanupSafely("Failed to stop alert sound after $reason.") { stopCurrentSound() }
    }

    private fun expireAllSafely(notifications: Iterable<AlertHandle>, reason: String) {
        var propagatedFailure: Throwable? = null
        notifications.forEach { notification ->
            propagatedFailure = captureCleanupFailure(
                propagatedFailure,
                "Failed to expire an alert handle after $reason.",
            ) { notification.expire() }
        }
        propagatedFailure?.let { throw it }
    }

    private fun expireSafely(notification: AlertHandle, reason: String) {
        cleanupSafely("Failed to expire an alert handle after $reason.") { notification.expire() }
    }

    private inline fun cleanupSafely(message: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            rethrowControlFlowOrError(error)
            LOG.warn(message, error)
        }
    }

    private inline fun captureCleanupFailure(
        current: Throwable?,
        message: String,
        action: () -> Unit,
    ): Throwable? = try {
        action()
        current
    } catch (error: Throwable) {
        if (isControlFlowOrError(error)) {
            if (current == null) error else current.also { if (error !== it) it.addSuppressed(error) }
        } else {
            LOG.warn(message, error)
            current
        }
    }

    private fun isCancellation(error: Throwable): Boolean =
        error is ControlFlowException || error is CancellationException

    private fun isControlFlowOrError(error: Throwable): Boolean = isCancellation(error) || error is Error

    private fun rethrowControlFlowOrError(error: Throwable) {
        if (isControlFlowOrError(error)) throw error
    }

    private fun scheduleSoundStop(playbackGeneration: Long, delaySeconds: Long): Boolean {
        if (disposed.get()) return false
        return try {
            scheduler.schedule(
                {
                    synchronized(lifecycleLock) {
                        if (!disposed.get() && playbackGeneration == soundPlaybackGeneration) {
                            stopCurrentSoundSafely("its playback timeout elapsed")
                        }
                    }
                },
                delaySeconds,
                TimeUnit.SECONDS,
            )
            true
        } catch (error: Throwable) {
            rethrowControlFlowOrError(error)
            logSchedulingFailure(
                "Could not schedule the alert sound timeout.",
                "Unexpected failure while scheduling the alert sound timeout.",
                error,
            )
            false
        }
    }

    private fun reconcilePolling() {
        synchronized(lifecycleLock) {
            val desiredIntervalSeconds = if (disposed.get()) {
                null
            } else if (!uiReadyCheckReceived) {
                null
            } else {
                LivePollingCadence.intervalSeconds(engine.nearestActiveRemainingMs())
            }
            if (desiredIntervalSeconds == null) {
                pollingGeneration++
                val retiredPeriodicCheck = periodicCheck
                periodicCheck = null
                armedPollingIntervalSeconds = null
                retiredPeriodicCheck?.let {
                    cancelPeriodicCheckSafely(it, "live polling was no longer needed")
                }
                return
            }
            // A handle is not proof of a live task: a repeating task that threw is finished for
            // good while its handle stays non-null. Probe it, and replace a dead poller.
            if (periodicCheck?.isAlive() == true && armedPollingIntervalSeconds == desiredIntervalSeconds) return
            val retiredPeriodicCheck = periodicCheck
            periodicCheck = null
            armedPollingIntervalSeconds = null
            val generation = ++pollingGeneration
            var cancellationFailure: Throwable? = null
            retiredPeriodicCheck?.let {
                try {
                    cancelPeriodicCheckSafely(it, "live polling was replaced")
                } catch (error: Throwable) {
                    if (isCancellation(error)) cancellationFailure = error else throw error
                }
            }
            try {
                periodicCheck = scheduler.scheduleWithFixedDelay(
                    {
                        try {
                            tickSafely(DueCheckContext.LIVE_POLL)
                        } catch (error: Throwable) {
                            if (isCancellation(error)) schedulePollingRecovery(generation, error)
                            rethrowControlFlowOrError(error)
                            // A throwable that escapes this body cancels the repeating schedule
                            // permanently, which would freeze every alarm and timer in silence.
                            LOG.warn("Live alarm and timer poll failed; polling continues.", error)
                        }
                    },
                    desiredIntervalSeconds,
                    desiredIntervalSeconds,
                    TimeUnit.SECONDS,
                )
                armedPollingIntervalSeconds = desiredIntervalSeconds
            } catch (error: Throwable) {
                periodicCheck = null
                armedPollingIntervalSeconds = null
                if (cancellationFailure != null) {
                    if (error !== cancellationFailure) cancellationFailure.addSuppressed(error)
                } else {
                    rethrowControlFlowOrError(error)
                }
                if (!disposed.get() && !isControlFlowOrError(error)) {
                    logSchedulingFailure(
                        "Could not start live alarm and timer polling.",
                        "Unexpected failure while starting live alarm and timer polling.",
                        error,
                    )
                }
            }
            cancellationFailure?.let { throw it }
        }
    }

    private fun schedulePollingRecovery(generation: Long, originalError: Throwable) {
        try {
            scheduler.schedule(
                {
                    val shouldReconcile = synchronized(lifecycleLock) {
                        if (disposed.get() || generation != pollingGeneration) {
                            false
                        } else {
                            // The repeating future has completed by the time this one-shot runs.
                            // Release its stale handle before asking reconcilePolling to re-arm it.
                            periodicCheck = null
                            armedPollingIntervalSeconds = null
                            true
                        }
                    }
                    if (shouldReconcile) reconcilePolling()
                },
                0,
                TimeUnit.MILLISECONDS,
            )
        } catch (recoveryError: Throwable) {
            synchronized(lifecycleLock) {
                if (generation == pollingGeneration) {
                    periodicCheck = null
                    armedPollingIntervalSeconds = null
                }
            }
            if (recoveryError !== originalError) originalError.addSuppressed(recoveryError)
        }
    }

    private fun cancelPeriodicCheckSafely(periodicCheck: ScheduledTask, reason: String) {
        cleanupSafely("Failed to cancel live alarm and timer polling after $reason.") {
            periodicCheck.cancel()
        }
    }

    private fun <T> changed(action: () -> T): T = action().also {
        resumePersistingCurrentState()
        confirmUiReady()
        reconcilePolling()
        fireStructureChanged()
    }

    private fun changedIf(action: () -> Boolean): Boolean = action().also {
        if (it) {
            resumePersistingCurrentState()
            confirmUiReady()
            reconcilePolling()
            fireStructureChanged()
        }
    }

    // A rejected execution is the ordinary shutdown race and keeps its own wording; anything else
    // is unexpected and is named as such. Both are warnings, because an error-level log throws
    // outside a running IDE and would defeat the guards that call this.
    private fun logSchedulingFailure(rejectedMessage: String, unexpectedMessage: String, error: Throwable) {
        rethrowControlFlowOrError(error)
        LOG.warn(if (error is RejectedExecutionException) rejectedMessage else unexpectedMessage, error)
    }

    // The user changed the in-memory state on purpose. That state is now the truth worth saving,
    // even if the file this build loaded came from a schema it cannot read.
    private fun resumePersistingCurrentState() {
        readOnlyPersistedState = null
    }

    private fun coalesceImmediateCheck(
        current: DueCheckContext?,
        requested: DueCheckContext,
    ): DueCheckContext = when {
        current == DueCheckContext.STARTUP_RECOVERY || requested == DueCheckContext.STARTUP_RECOVERY ->
            DueCheckContext.STARTUP_RECOVERY
        else -> DueCheckContext.ACTIVATION_CHECK
    }

    private data class ListenerRegistration(
        val wantsClockPulses: () -> Boolean,
        val listener: (AlarmTimerEvent) -> Unit,
        val active: AtomicBoolean = AtomicBoolean(true),
    )

    private data class AlertRecord(
        val handle: AlertHandle,
        val token: Any,
    )

    private data class DueCheckBatch(
        val generation: Long,
        val due: List<DueItem>,
        val recovered: List<DueItem>,
    )

    private data class DisposalCleanup(
        val notifications: List<AlertHandle>,
        val periodicCheck: ScheduledTask?,
    )

    private class AlertRegistration {
        var completed = false
        var expired = false
    }

    companion object {
        // Near-deadline checks can be one full one-second poll late, plus ordinary scheduler jitter.
        private const val LIVE_CHECK_TOLERANCE_MS = 1_250L

        private val LOG = Logger.getInstance(AlarmTimerService::class.java)

        fun getInstance(): AlarmTimerService = ApplicationManager.getApplication().getService(AlarmTimerService::class.java)
    }
}
