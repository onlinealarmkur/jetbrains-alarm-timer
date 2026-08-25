package com.onlinealarmkur.jetbrains.service

import com.onlinealarmkur.jetbrains.domain.ItemKind
import com.onlinealarmkur.jetbrains.domain.ScheduledItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NotificationContentTest {
    @Test
    fun `mixed markup is escaped exactly`() {
        val item = item(ItemKind.TIMER, "<img src='https://example.invalid/x'>&\"")

        assertEquals(
            "&lt;img src=&#39;https://example.invalid/x&#39;&gt;&amp;&quot;",
            notificationContent(item),
        )
    }

    @Test
    fun `unicode text is preserved while html significant characters are escaped`() {
        val item = item(ItemKind.ALARM, "Café ⏰ & <b>\"Tea\" 'now'</b>")

        assertEquals(
            "Café ⏰ &amp; &lt;b&gt;&quot;Tea&quot; &#39;now&#39;&lt;/b&gt;",
            notificationContent(item),
        )
    }

    @Test
    fun `blank labels use body copy while titles identify kind exactly once`() {
        assertEquals(NotificationText("Alarm", "It’s time.", false), notificationText(item(ItemKind.ALARM, "")))
        assertEquals(NotificationText("Timer", "Time is up.", false), notificationText(item(ItemKind.TIMER, " \t\n")))
        assertEquals("It’s time.", notificationContent(item(ItemKind.ALARM, "")))
        assertEquals("Time is up.", notificationContent(item(ItemKind.TIMER, " \t\n")))
    }

    @Test
    fun `labeled notifications retain the raw label until escaping at the sink`() {
        val item = item(ItemKind.ALARM, "<b>Focus</b>")
        assertEquals(NotificationText("Alarm", "<b>Focus</b>", true), notificationText(item))
        assertEquals("&lt;b&gt;Focus&lt;/b&gt;", notificationContent(item))
        val timer = notificationText(item(ItemKind.TIMER, "Tea & biscuits"))
        assertEquals(NotificationText("Timer", "Tea & biscuits", true), timer)
        assertEquals("Tea &amp; biscuits", notificationSinkContent(timer))
    }

    @Test
    fun `escaping notification content does not change the stored label`() {
        val label = "Tea & <b>biscuits</b>"
        val item = item(ItemKind.TIMER, label)

        assertEquals("Tea &amp; &lt;b&gt;biscuits&lt;/b&gt;", notificationContent(item))
        assertEquals(label, item.label)
    }

    private fun item(kind: ItemKind, label: String) = ScheduledItem(
        id = "test-item",
        kind = kind,
        label = label,
        createdAtEpochMs = 1_000,
        targetEpochMs = 2_000,
    )
}
