package com.onlinealarmkur.jetbrains.domain

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class MutableClock(private var instant: Instant, private val zoneId: ZoneId = ZoneId.of("UTC")) : Clock() {
    override fun getZone(): ZoneId = zoneId
    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)
    override fun instant(): Instant = instant
    fun advanceMillis(millis: Long) { instant = instant.plusMillis(millis) }
}

class MutableElapsedTimeSource(initialMillis: Long = 0) : ElapsedTimeSource {
    private var nowMillis = initialMillis

    override fun nowMillis(): Long = nowMillis

    fun advanceMillis(millis: Long) {
        nowMillis += millis
    }
}
