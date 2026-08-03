package com.bluetriangle.analytics.sessionmanager

import android.app.Activity
import android.app.Application
import com.bluetriangle.analytics.Constants
import com.bluetriangle.analytics.Constants.DEFAULT_CONFIG_KEY
import com.bluetriangle.analytics.breadcrumbs.config.BreadcrumbsConfig
import com.bluetriangle.analytics.checkout.config.CheckoutConfig
import com.bluetriangle.analytics.dynamicconfig.updater.IBTTConfigurationUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class DisabledModeSessionManager(
    private val updater: IBTTConfigurationUpdater
) : ISessionManager {

    private var dummySession = dummySessionData(false)

    private fun dummySessionData(isConfigApplied: Boolean) = SessionData(
        "",
        shouldSampleNetwork = false,
        isConfigApplied = isConfigApplied,
        networkSampleRate = 0.0,
        ignoreScreens = listOf(),
        enableScreenTracking = true,
        enableGrouping = false,
        groupingIdleTime = Constants.DEFAULT_GROUPING_IDLE_TIME,
        enableGroupingTapDetection = false,
        enableNetworkStateTracking = false,
        enableCrashTracking = false,
        enableANRTracking = false,
        enableMemoryWarning = false,
        enableLaunchTime = false,
        enableWebViewStitching = false,
        checkoutConfig = CheckoutConfig.DEFAULT,
        breadcrumbsConfig = BreadcrumbsConfig.DEFAULT,
        configKey = DEFAULT_CONFIG_KEY,
        expiration = 0L,
        enableAppInstall = false,
        enableForceRestart = false,
        forceRestartDuration = 10.0,
        enableScreenResponsiveness = false
    )

    override val sessionData: SessionData
        get() = dummySession

    private var scope: CoroutineScope? = null

    init {
        initScope()
        updateConfig(true)
    }

    private fun updateConfig(force: Boolean) {
        scope?.launch {
            if(force) {
                updater.forceUpdate()
            } else {
                updater.update()
            }
        }
    }

    override fun endSession() {

    }

    override fun onActivityResumed(activity: Activity) {
        initScope()
        onLaunch()
    }

    override fun onAppMovedToBackground(application: Application) {
        onOffScreen()
        destroyScope()
    }

    private fun onLaunch() {
        updateConfig(false)
    }

    private fun onOffScreen() {
        dummySession = dummySessionData(false)
    }

    private fun initScope() {
        if(scope?.isActive == true) return

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    private fun destroyScope() {
        scope?.cancel()
        scope = null
    }

}
