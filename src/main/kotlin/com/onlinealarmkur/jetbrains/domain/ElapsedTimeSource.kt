package com.onlinealarmkur.jetbrains.domain

/**
 * Process-local monotonic time for measuring live durations. Readings are meaningful only as
 * differences within this process and must never be persisted. The production source includes
 * system suspend time; neither wall-clock corrections nor IDE focus changes reset its epoch.
 */
fun interface ElapsedTimeSource {
    fun nowMillis(): Long
}
