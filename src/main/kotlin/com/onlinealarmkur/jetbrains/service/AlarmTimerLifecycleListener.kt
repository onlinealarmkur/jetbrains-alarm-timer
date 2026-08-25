package com.onlinealarmkur.jetbrains.service

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.wm.IdeFrame

class AlarmTimerLifecycleListener internal constructor(
    private val requestUiReadyCheck: () -> Unit,
) : AppLifecycleListener, ApplicationActivationListener {
    constructor() : this(
        { AlarmTimerService.getInstance().requestUiReadyCheck() },
    )

    override fun welcomeScreenDisplayed() {
        requestUiReadyCheck()
    }

    override fun applicationActivated(ideFrame: IdeFrame) {
        requestUiReadyCheck()
    }
}
