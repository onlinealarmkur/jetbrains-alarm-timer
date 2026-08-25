package com.onlinealarmkur.jetbrains.service

import com.intellij.openapi.wm.IdeFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class AlarmTimerLifecycleListenerTest {
    @Test
    fun `frame creation does not request recovery before UI is ready`() {
        var requests = 0
        val listener = AlarmTimerLifecycleListener(
            requestUiReadyCheck = { requests++ },
        )

        listener.appFrameCreated(emptyList())

        assertEquals(0, requests)
    }

    @Test
    fun `welcome screen requests recovery after UI is ready`() {
        var requests = 0
        val listener = AlarmTimerLifecycleListener(
            requestUiReadyCheck = { requests++ },
        )

        listener.welcomeScreenDisplayed()

        assertEquals(1, requests)
    }

    @Test
    fun `application activation requests UI-ready recovery`() {
        var requests = 0
        val listener = AlarmTimerLifecycleListener(
            requestUiReadyCheck = { requests++ },
        )

        listener.applicationActivated(fakeIdeFrame())

        assertEquals(1, requests)
    }

    private fun fakeIdeFrame(): IdeFrame = Proxy.newProxyInstance(
        IdeFrame::class.java.classLoader,
        arrayOf(IdeFrame::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            else -> null
        }
    } as IdeFrame
}
