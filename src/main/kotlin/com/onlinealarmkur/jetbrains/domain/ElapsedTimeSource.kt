package com.onlinealarmkur.jetbrains.domain

import java.util.concurrent.TimeUnit

/**
 * Process-local monotonic time for measuring live durations. Readings are meaningful only as
 * differences within this process and must never be persisted.
 */
fun interface ElapsedTimeSource {
    fun nowMillis(): Long
}

object SystemElapsedTimeSource : ElapsedTimeSource {
    override fun nowMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
}
