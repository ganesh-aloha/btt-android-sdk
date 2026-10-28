package com.bluetriangle.analytics.applaunch

import android.content.Context
import com.bluetriangle.analytics.Constants
import com.bluetriangle.analytics.CrashRunnable
import com.bluetriangle.analytics.Logger
import com.bluetriangle.analytics.Timer
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.anrwatchdog.AnrDetector
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
    private var fatalANRTracker: FatalANRTracker? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appInstallReportJob: Job? = null

    init {
        forceRestartTracker = ForceRestartTracker(logger, context, this, forceRestartDuration)
        fatalANRTracker = FatalANRTracker(logger, context, deviceInfoProvider)
    }

    fun startForceRestartTracker() {
        forceRestartTracker?.start()
    }

    fun stopForceRestartTracker() {
        forceRestartTracker?.stop()
        scope.cancel()
    }

    fun startFatalANRTracker() {
        fatalANRTracker?.start()
    }

    fun stopFatalANRTracker() {
        fatalANRTracker?.stop()
    }

    fun attachFatalAnrListener(detector: AnrDetector) {
        fatalANRTracker?.attachAnrListener(detector)
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

    internal fun reportForceKill(forceRestartScreen: ForceRestartTracker.ForceRestartScreen, exitTime: Long) {
        val configuration = Tracker.instance?.configuration!!

        val timeStamp = exitTime.toString()

        // Built from the page the user was on in the killed process, so the error carries the nst of
        // that page view. It isn't started with start(), which would stamp the relaunch time as nst
        // and register it as the most recent timer of the new process.
        val timer = Timer().apply {
            startAt(forceRestartScreen.nst ?: exitTime)
            setPageName(forceRestartScreen.pageName ?: BTTEvent.ForceRestart.defaultPageName)
            setContentGroupName(forceRestartScreen.pageType ?: "")
            setTrafficSegmentName(forceRestartScreen.txnName ?: "")
            forceRestartScreen.sessionId?.let { setField(Timer.FIELD_SESSION_ID, it) }
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
