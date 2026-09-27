package com.onlinealarmkur.jetbrains.service

import com.intellij.openapi.diagnostic.Logger
import com.onlinealarmkur.jetbrains.domain.ElapsedTimeSource
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import java.util.concurrent.TimeUnit

/** Uses the IDE's bundled JNA; no native binaries are packaged or downloaded by the plugin. */
internal class SuspendAwareElapsedTimeSource(
    private val nativeClockFactory: () -> ElapsedTimeSource = { nativeElapsedTimeSource() },
    private val fallback: ElapsedTimeSource = ElapsedTimeSource { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
    private val reportFailure: (Throwable) -> Unit = {
        Logger.getInstance(SuspendAwareElapsedTimeSource::class.java).warn(
            "Suspend-aware clock unavailable; live timers may pause during system sleep until the IDE restarts.", it,
        )
    },
) : ElapsedTimeSource {
    private var initialized = false
    private var nativeClock: ElapsedTimeSource? = null
    private var fallbackOffset = 0L

    @Synchronized
    override fun nowMillis(): Long {
        val fallbackNow = fallback.nowMillis()
        try {
            if (!initialized) {
                nativeClock = nativeClockFactory()
                initialized = true
            }
            val result = nativeClock?.nowMillis() ?: (fallbackNow + fallbackOffset)
            fallbackOffset = result - fallbackNow
            return result
        } catch (error: Throwable) {
            // Only native availability/read failures are recoverable. Never swallow cancellation,
            // VM errors, or unrelated programming errors. Never change epochs for live deadlines.
            if (error !is LinkageError && error !is SecurityException && error !is NativeClockException) throw error
            nativeClock = null
            initialized = true
            reportFailure(error)
            return fallbackNow + fallbackOffset
        }
    }
}

internal class NativeClockException(message: String) : IllegalStateException(message)

internal fun nativeElapsedTimeSource(osName: String = System.getProperty("os.name")): ElapsedTimeSource = when {
    osName.startsWith("Linux") -> posixElapsedTimeSource(7) // CLOCK_BOOTTIME includes suspend.
    osName.startsWith("Mac") -> posixElapsedTimeSource(4) // Darwin CLOCK_MONOTONIC_RAW uses mach_continuous_time.
    osName.startsWith("Windows") -> {
        val ticks = NativeLibrary.getInstance("kernel32").getFunction("GetTickCount64", Function.ALT_CONVENTION)
        ElapsedTimeSource { ticks.invokeLong(emptyArray()) }
    }
    else -> throw NativeClockException("Unsupported operating system for suspend-aware timing: $osName")
}

private fun posixElapsedTimeSource(clockId: Int): ElapsedTimeSource {
    val clockGettime = NativeLibrary.getInstance("c").getFunction("clock_gettime")
    return ElapsedTimeSource {
        Memory(2L * Native.LONG_SIZE).use { timespec ->
            if (clockGettime.invokeInt(arrayOf(clockId, timespec)) != 0) {
                throw NativeClockException("clock_gettime($clockId) failed with errno ${Native.getLastError()}")
            }
            val seconds = timespec.getNativeLong(0).toLong()
            val nanos = timespec.getNativeLong(Native.LONG_SIZE.toLong()).toLong()
            if (seconds < 0 || nanos !in 0 until 1_000_000_000L) {
                throw NativeClockException("Invalid monotonic clock reading")
            }
            seconds * 1_000 + nanos / 1_000_000
        }
    }
}
