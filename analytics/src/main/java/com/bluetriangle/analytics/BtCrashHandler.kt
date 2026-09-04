package com.bluetriangle.analytics

import android.os.Build
import androidx.annotation.RequiresApi
import com.bluetriangle.analytics.Utils.exceptionToStacktrace
import com.bluetriangle.analytics.deviceinfo.IDeviceInfoProvider

internal class BtCrashHandler(
    private val configuration: BlueTriangleConfiguration,
    private val deviceInfoProvider: IDeviceInfoProvider
) : Thread.UncaughtExceptionHandler {
    internal val defaultUEH: Thread.UncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()

    @RequiresApi(api = Build.VERSION_CODES.N)
    override fun uncaughtException(t: Thread, e: Throwable) {
        val timeStamp = System.currentTimeMillis().toString()
        val mostRecentTimer = Tracker.instance?.getMostRecentTimer()
        configuration.logger?.debug("Most Recent Timer: $mostRecentTimer")

        val exceptionInfo = exceptionToStacktrace(null, e)
        try {
            sendToServer(mostRecentTimer, exceptionInfo.title, exceptionInfo.stackTrace, timeStamp)
        } catch (interruptedException: InterruptedException) {
            interruptedException.printStackTrace()
        }
        defaultUEH.uncaughtException(t, e)
    }

    @Throws(InterruptedException::class)
    private fun sendToServer(mostRecentTimer:Timer?, title:String?, stacktrace: String, timeStamp: String) {
        val thread = Thread(CrashRunnable(
            configuration, stacktrace, timeStamp,
            mostRecentTimer = mostRecentTimer,
            title = title,
            deviceInfoProvider = deviceInfoProvider,
            breadcrumbs = Tracker.instance?.breadcrumbsManager?.snapshot()
        ))
        thread.start()
        thread.join()
    }
}