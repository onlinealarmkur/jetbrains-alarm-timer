package com.onlinealarmkur.jetbrains.persistence

import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import com.onlinealarmkur.jetbrains.domain.AlarmTimerSettings
import com.onlinealarmkur.jetbrains.domain.AlarmTimerSettingsPolicy
import com.onlinealarmkur.jetbrains.domain.ItemKind
import com.onlinealarmkur.jetbrains.domain.ItemStatus
import com.onlinealarmkur.jetbrains.domain.MAX_LABEL_LENGTH
import com.onlinealarmkur.jetbrains.domain.ScheduledItem
import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StateCodecTest {
    @Test
    fun `domain persisted bean and decoded defaults share canonical settings`() {
        val bean = PersistedSettings()
        val beanSettings = AlarmTimerSettings(
            defaultTimerMs = bean.defaultTimerMs,
            use24HourTime = bean.use24HourTime,
            showStatusBarWidget = bean.showStatusBarWidget,
            warnOnExitWithPendingItems = bean.warnOnExitWithPendingItems,
            overdueGracePeriodMs = bean.overdueGracePeriodMs,
            soundEnabled = bean.soundEnabled,
            volumePercent = bean.volumePercent,
        )

        assertEquals(AlarmTimerSettingsPolicy.DEFAULTS, AlarmTimerSettings())
        assertEquals(AlarmTimerSettingsPolicy.DEFAULTS, beanSettings)
        assertEquals(AlarmTimerSettingsPolicy.DEFAULTS, StateCodec.decode(PersistedState()).settings)
    }

    @Test
    fun `malformed and duplicate items are ignored`() {
        val valid = item("same")
        val duplicate = item("same")
        val malformed = item("bad").apply { kind = "UNKNOWN" }
        val decoded = StateCodec.decode(PersistedState().apply { items.addAll(listOf(valid, duplicate, malformed)) })
        assertEquals(listOf("same"), decoded.items.map { it.id })
    }

    @Test
    fun `overlength ids with a shared prefix are rejected instead of truncated`() {
        val sharedPrefix = "a".repeat(100)
        val decoded = StateCodec.decode(PersistedState().apply {
            items.addAll(listOf(item(sharedPrefix + "1"), item(sharedPrefix + "2")))
        })

        assertTrue(decoded.items.isEmpty())
    }

    @Test
    fun `malformed item does not reserve its id`() {
        val malformed = item("same").apply { targetEpochMs = 0 }
        val valid = item("same")
        val decoded = StateCodec.decode(PersistedState().apply { items.addAll(listOf(malformed, valid)) })

        assertEquals(listOf("same"), decoded.items.map { it.id })
    }

    @Test
    fun `blank ids are ignored`() {
        val decoded = StateCodec.decode(PersistedState().apply {
            items.addAll(listOf(item(""), item("   "), item("valid")))
        })

        assertEquals(listOf("valid"), decoded.items.map { it.id })
    }

    @Test
    fun `invalid settings are repaired`() {
        val decoded = StateCodec.decode(PersistedState().apply {
            settings.defaultTimerMs = -1
            settings.volumePercent = 500
            settings.overdueGracePeriodMs = Long.MAX_VALUE
        })
        assertEquals(AlarmTimerSettingsPolicy.DEFAULT_TIMER_MS, decoded.settings.defaultTimerMs)
        assertEquals(AlarmTimerSettingsPolicy.MAX_VOLUME_PERCENT, decoded.settings.volumePercent)
        assertEquals(
            AlarmTimerSettingsPolicy.MAX_OVERDUE_GRACE_PERIOD_MS,
            decoded.settings.overdueGracePeriodMs,
        )
    }

    @Test
    fun `settings lower bounds are repaired by clamping`() {
        val decoded = StateCodec.decode(PersistedState().apply {
            // State the schema: below version 2 a nonpositive volume means the legacy mute, which
            // the migration repairs before this clamp can apply.
            schemaVersion = CURRENT_SCHEMA_VERSION
            settings.volumePercent = -1
            settings.overdueGracePeriodMs = -1
        })

        assertEquals(AlarmTimerSettingsPolicy.MIN_VOLUME_PERCENT, decoded.settings.volumePercent)
        assertEquals(
            AlarmTimerSettingsPolicy.MIN_OVERDUE_GRACE_PERIOD_MS,
            decoded.settings.overdueGracePeriodMs,
        )
    }

    @Test
    fun `settings exact boundaries and booleans round trip`() {
        val settings = AlarmTimerSettings(
            defaultTimerMs = AlarmTimerSettingsPolicy.MIN_DURATION_MS,
            use24HourTime = false,
            showStatusBarWidget = false,
            warnOnExitWithPendingItems = false,
            overdueGracePeriodMs = AlarmTimerSettingsPolicy.MAX_OVERDUE_GRACE_PERIOD_MS,
            soundEnabled = false,
            volumePercent = AlarmTimerSettingsPolicy.MAX_VOLUME_PERCENT,
        )

        assertEquals(settings, StateCodec.decode(StateCodec.encode(settings, emptyList())).settings)
    }

    @Test
    fun `absent exit warning setting defaults to warning and an explicit opt-out survives`() {
        // A file written before this field existed carries no option for it, so the bean default is
        // what the serializer leaves in place. That safe default is why the addition needs no
        // schema bump and no migration.
        assertEquals(2, CURRENT_SCHEMA_VERSION)
        assertTrue(StateCodec.decode(PersistedState()).settings.warnOnExitWithPendingItems)

        val optedOut = PersistedState().apply {
            schemaVersion = CURRENT_SCHEMA_VERSION
            settings.warnOnExitWithPendingItems = false
        }
        val decoded = StateCodec.decode(optedOut).settings

        assertFalse(decoded.warnOnExitWithPendingItems)
        assertFalse(StateCodec.decode(StateCodec.encode(decoded, emptyList())).settings.warnOnExitWithPendingItems)
    }

    @Test
    fun `schema zero decodes through migration without mutating stored representation`() {
        val state = PersistedState().apply { schemaVersion = 0; items += item("old") }
        assertEquals("old", StateCodec.decode(state).items.single().id)
        assertEquals(0, state.schemaVersion)
    }

    @Test
    fun `version one zero volume migrates to an explicit mute with a reusable volume`() {
        val state = PersistedState().apply {
            schemaVersion = 1
            settings.soundEnabled = true
            settings.volumePercent = 0
        }

        val decoded = StateCodec.decode(state)

        assertFalse(decoded.settings.soundEnabled)
        assertEquals(AlarmTimerSettingsPolicy.DEFAULT_VOLUME_PERCENT, decoded.settings.volumePercent)
        assertEquals(1, state.schemaVersion)
        assertTrue(state.settings.soundEnabled)
        assertEquals(0, state.settings.volumePercent)
    }

    @Test
    fun `unknown future schema fails safely to defaults and leaves the stored state untouched`() {
        val state = PersistedState().apply {
            schemaVersion = 999
            items += item("future").apply { deliveryPending = true }
        }

        val decoded = StateCodec.decode(state)

        assertTrue(decoded.items.isEmpty())
        assertTrue(decoded.foreignSchema)
        assertEquals(AlarmTimerSettingsPolicy.DEFAULTS, decoded.settings)
        assertEquals(999, state.schemaVersion)
        assertEquals(listOf("future"), state.items.map { it.id })
    }

    @Test
    fun `absent schema version in stored xml migrates and encoded xml stamps the current version`() {
        val filter = SkipDefaultsSerializationFilter()
        val legacy = PersistedState().apply {
            settings.soundEnabled = true
            settings.volumePercent = 0
            items += item("legacy")
        }
        val legacyElement = Element("component")
        XmlSerializer.serializeInto(legacy, legacyElement, filter)

        // The serializer omits every field still equal to its bean default, so a legacy file and a
        // file written before the field existed are the same document: no schemaVersion option.
        assertNull(optionValue(legacyElement, "schemaVersion"))

        val stored = PersistedState()
        XmlSerializer.deserializeInto(stored, legacyElement)
        assertEquals(0, stored.schemaVersion)

        val decoded = StateCodec.decode(stored)

        assertFalse(decoded.settings.soundEnabled)
        assertEquals(AlarmTimerSettingsPolicy.DEFAULT_VOLUME_PERCENT, decoded.settings.volumePercent)
        assertEquals(listOf("legacy"), decoded.items.map { it.id })

        val encodedElement = Element("component")
        XmlSerializer.serializeInto(StateCodec.encode(decoded.settings, decoded.items), encodedElement, filter)

        assertEquals(CURRENT_SCHEMA_VERSION.toString(), optionValue(encodedElement, "schemaVersion"))
    }

    @Test
    fun `states without a delivery marker default to delivered`() {
        val decoded = StateCodec.decode(PersistedState().apply { items += item("old") })

        assertFalse(decoded.items.single().deliveryPending)
    }

    @Test
    fun `pending delivery round trips without losing item state`() {
        val settings = AlarmTimerSettings()
        val pending = ScheduledItem(
            id = "pending",
            kind = ItemKind.TIMER,
            label = "Tea",
            createdAtEpochMs = 1,
            targetEpochMs = 60_001,
            durationMs = 60_000,
            status = ItemStatus.COMPLETED,
            firedAtEpochMs = 60_001,
            deliveryPending = true,
        )

        assertEquals(
            ValidatedState(settings, listOf(pending)),
            StateCodec.decode(StateCodec.encode(settings, listOf(pending))),
        )
    }

    @Test
    fun `valid paused timer remainders round trip unchanged`() {
        val settings = AlarmTimerSettings()
        val durationMs = 60_000L

        listOf(1L, 999L, 1_000L, durationMs).forEach { remainingMs ->
            val paused = ScheduledItem(
                id = "paused-$remainingMs",
                kind = ItemKind.TIMER,
                label = "Tea",
                createdAtEpochMs = 1,
                targetEpochMs = durationMs + 1,
                durationMs = durationMs,
                remainingMs = remainingMs,
                status = ItemStatus.PAUSED,
            )

            assertEquals(
                paused,
                StateCodec.decode(StateCodec.encode(settings, listOf(paused))).items.single(),
                "remainingMs=$remainingMs",
            )
        }
    }

    @Test
    fun `invalid paused timer remainders are rejected`() {
        val durationMs = 60_000L

        listOf(-1L, 0L).forEach { remainingMs ->
            val paused = item("paused-$remainingMs").apply {
                this.durationMs = durationMs
                this.remainingMs = remainingMs
                status = ItemStatus.PAUSED.name
            }

            val decoded = StateCodec.decode(PersistedState().apply { items += paused })

            assertTrue(decoded.items.isEmpty(), "remainingMs=$remainingMs")
        }
    }

    @Test
    fun `paused timer remainder past its duration is repaired instead of dropped`() {
        val durationMs = 60_000L
        val paused = item("paused-overflow").apply {
            this.durationMs = durationMs
            this.remainingMs = durationMs + 1
            status = ItemStatus.PAUSED.name
        }

        val decoded = StateCodec.decode(PersistedState().apply { items += paused })

        val repaired = decoded.items.single()
        assertEquals(ItemStatus.PAUSED, repaired.status)
        assertEquals(durationMs, repaired.remainingMs)
        assertEquals(
            listOf(repaired),
            StateCodec.decode(StateCodec.encode(decoded.settings, decoded.items)).items,
        )
    }

    @Test
    fun `malformed delivery pending combinations decode as not pending`() {
        val active = item("active").apply {
            firedAtEpochMs = 2
            deliveryPending = true
        }
        val completedWithoutFiredTime = item("no-fired-time").apply {
            status = ItemStatus.COMPLETED.name
            deliveryPending = true
        }
        val missed = item("missed").apply {
            status = ItemStatus.MISSED.name
            firedAtEpochMs = 2
            deliveryPending = true
        }
        val delivered = item("delivered").apply {
            status = ItemStatus.COMPLETED.name
            firedAtEpochMs = 2
        }

        val decoded = StateCodec.decode(PersistedState().apply {
            items.addAll(listOf(active, completedWithoutFiredTime, missed, delivered))
        })

        assertEquals(listOf("active", "no-fired-time", "missed", "delivered"), decoded.items.map { it.id })
        assertTrue(decoded.items.none { it.deliveryPending })
    }

    @Test
    fun `decode applies the shared label normalization contract`() {
        val persisted = item("label").apply { label = "  ${"x".repeat(201)}  " }

        val decoded = StateCodec.decode(PersistedState().apply { items += persisted })

        assertEquals("x".repeat(MAX_LABEL_LENGTH), decoded.items.single().label)
    }

    @Test
    fun `normalized state is exactly stable across encode and decode`() {
        val settings = AlarmTimerSettings()
        val item = ScheduledItem(
            id = "round-trip",
            kind = ItemKind.TIMER,
            label = "x".repeat(MAX_LABEL_LENGTH),
            createdAtEpochMs = 1,
            targetEpochMs = 60_001,
            durationMs = 60_000,
        )

        assertEquals(
            ValidatedState(settings, listOf(item)),
            StateCodec.decode(StateCodec.encode(settings, listOf(item))),
        )
    }

    @Test
    fun `surrogate safe label normalization is stable across encode and decode`() {
        val persisted = item("round-trip-surrogate").apply {
            label = "x".repeat(199) + "\uD83D\uDE00"
        }
        val normalized = StateCodec.decode(PersistedState().apply { items += persisted })

        val roundTripped = StateCodec.decode(StateCodec.encode(normalized.settings, normalized.items))

        assertEquals(normalized, roundTripped)
        assertEquals("x".repeat(199), roundTripped.items.single().label)
        assertTrue(roundTripped.items.single().label.none(Character::isSurrogate))
    }

    private fun optionValue(element: Element, name: String): String? = element.getChildren("option")
        .firstOrNull { it.getAttributeValue("name") == name }
        ?.getAttributeValue("value")

    private fun item(idValue: String) = PersistedItem().apply {
        id = idValue
        kind = ItemKind.TIMER.name
        label = "timer"
        createdAtEpochMs = 1
        targetEpochMs = 2
        durationMs = 60_000
        status = ItemStatus.ACTIVE.name
    }
}
