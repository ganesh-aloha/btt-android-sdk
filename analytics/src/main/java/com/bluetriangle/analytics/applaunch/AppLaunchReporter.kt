package com.bluetriangle.analytics.applaunch

import android.content.Context
import android.util.Log
import com.bluetriangle.analytics.Constants
import com.bluetriangle.analytics.CrashRunnable
import com.bluetriangle.analytics.Logger
import com.bluetriangle.analytics.Timer
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.deviceinfo.IDeviceInfoProvider
import com.bluetriangle.analytics.event.BTTEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class AppLaunchReporter(
    logger: Logger?, val context: Context, val deviceInfoProvider: IDeviceInfoProvider, forceRestartDuration: Double
) {
    private var forceRestartTracker: ForceRestartTracker? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appInstallReportJob: Job? = null

    init {
        forceRestartTracker = ForceRestartTracker(logger, context, this, forceRestartDuration)
    }

    fun start() {
        forceRestartTracker?.start()
    }

    fun stop() {
        forceRestartTracker?.stop()
        scope.cancel()
    }

    fun setForceRestartDuration(forceRestartDuration: Double) {
        forceRestartTracker?.setForceRestartDuration(forceRestartDuration)
    }

    fun reportAppInstall(installTime: Long) {
        appInstallReportJob?.cancel()

        appInstallReportJob = scope.launch {
            while (Tracker.instance == null) delay(5)

            Timer().apply {
                startWithoutPerformanceMonitor()
                setPageName(BTTEvent.AppInstall.defaultPageName)
                setContentGroupName(BTTEvent.AppInstall.defaultPageName)
                setTrafficSegmentName(BTTEvent.AppInstall.defaultPageName)
                setTimeOnPage(Constants.TIMER_MIN_PGTM)
                pageTimeCalculator = {
                    Constants.TIMER_MIN_PGTM
                }
                generateNativeAppProperties()
                nativeAppProperties.loadTime = Constants.TIMER_MIN_PGTM
                nativeAppProperties.event = BTTEvent.AppInstall
                nativeAppProperties.installTime = installTime

                submit()

                appInstallReportJob = null
            }
        }
    }

    fun reportForceKill(pageName: String?, pageType: String?, txnName: String?) {
        val configuration = Tracker.instance?.configuration!!

        val timeStamp = System.currentTimeMillis().toString()
        val mostRecentTimer = Tracker.instance?.getMostRecentTimer()

        val timer = Timer().apply {
            startWithoutPerformanceMonitor()
            setPageName(pageName ?: mostRecentTimer?.getField(Timer.FIELD_PAGE_NAME) ?: "Unknown")
            //setContentGroupName(BTTEvent.ForceRestart.defaultPageName)
            setContentGroupName(
                pageType ?: mostRecentTimer?.getField(Timer.FIELD_CONTENT_GROUP_NAME) ?: ""
            )
            setTrafficSegmentName(
                txnName ?: mostRecentTimer?.getField(Timer.FIELD_TRAFFIC_SEGMENT_NAME) ?: ""
            )
            setTimeOnPage(Constants.TIMER_MIN_PGTM)
            pageTimeCalculator = {
                Constants.TIMER_MIN_PGTM
            }
            generateNativeAppProperties()
            nativeAppProperties.loadTime = Constants.TIMER_MIN_PGTM
            nativeAppProperties.event = BTTEvent.ForceRestart
        }

        try {
            val thread = Thread(
                CrashRunnable(
                    configuration,
                    "User force restarted app.",
                    timeStamp,
                    Tracker.BTErrorType.ForceRestart,
                    timer,
                    deviceInfoProvider = deviceInfoProvider,
                    breadcrumbs = Tracker.instance?.breadcrumbsManager?.getCachedSnapshot()
                )
            )
            thread.start()
            thread.join()
        } catch (interruptedException: InterruptedException) {
            interruptedException.printStackTrace()
        }
    }
}
