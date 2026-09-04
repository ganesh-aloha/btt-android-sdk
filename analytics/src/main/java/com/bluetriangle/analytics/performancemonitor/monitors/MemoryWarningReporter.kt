package com.bluetriangle.analytics.performancemonitor.monitors

import com.bluetriangle.analytics.CrashRunnable
import com.bluetriangle.analytics.Timer
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.deviceinfo.IDeviceInfoProvider

internal class MemoryWarningReporter(val deviceInfoProvider: IDeviceInfoProvider) {

    fun reportMemoryWarning(timer: Timer?, exception: MemoryMonitor.MemoryWarningException) {
        val tracker = Tracker.instance ?: return
        val configuration = tracker.configuration

        val timeStamp = exception.timestamp.toString()

        try {
            val thread = Thread(
                CrashRunnable(
                    configuration,
                    exception.message ?: "",
                    timeStamp,
                    Tracker.BTErrorType.MemoryWarning,
                    mostRecentTimer = timer,
                    title = exception.message ?: "",
                    errorCount = exception.count,
                    deviceInfoProvider = deviceInfoProvider,
                    breadcrumbs = exception.breadcrumbs
                )
            )
            thread.start()
            thread.join()
        } catch (interruptedException: InterruptedException) {
            interruptedException.printStackTrace()
        }
    }
}
