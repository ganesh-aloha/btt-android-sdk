package com.bluetriangle.analytics.applaunch

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.bluetriangle.analytics.Constants
import com.bluetriangle.analytics.Logger
import com.bluetriangle.analytics.Timer.Companion.FIELD_CONTENT_GROUP_NAME
import com.bluetriangle.analytics.Timer.Companion.FIELD_PAGE_NAME
import com.bluetriangle.analytics.Timer.Companion.FIELD_TRAFFIC_SEGMENT_NAME
import com.bluetriangle.analytics.Tracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ForceRestartTracker(
    private val logger: Logger?, private val context: Context, private val reporter:AppLaunchReporter, forceRestartDuration: Double
) {
    private var _forceRestartDuration: Long = 10_000
    private var appForceKillReportJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        _forceRestartDuration = (forceRestartDuration * 1000).toLong()
    }

    fun setForceRestartDuration(forceRestartDuration: Double) {
        _forceRestartDuration = (forceRestartDuration * 1000).toLong()
    }

    fun start() {
        val prefs =
            context.getSharedPreferences(Tracker.SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)

        val lastForegroundTime = prefs.getLong(Constants.APP_LAST_FOREGROUND_TIME, 0L)

        checkAppPreviousExit(System.currentTimeMillis(), lastForegroundTime)
    }

    fun stop() {
        scope.cancel()
    }

    private fun checkAppPreviousExit(currentTime: Long, lastForegroundTime: Long) {
        Log.e("GANESH", "checkAppPreviousExit - lastForegroundTime=$lastForegroundTime")
        if (lastForegroundTime == 0L) return

        // If the app was restarted within 10 seconds, we assume it was died unexpectedly
        val diedUnexpectedly = (currentTime - lastForegroundTime) < _forceRestartDuration

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.e("GANESH", "checkAppPreviousExit - Support ApplicationExitInfo")
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            // Retrieve the most recent exit reason for this app
            val exitList =
                activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 1)
            if (exitList.isNotEmpty()) {
                Log.e("GANESH", "checkAppPreviousExit - Process Exist Info is Not Empty $exitList")
                val lastExit = exitList[0]
                Log.e("GANESH", "checkAppPreviousExit - Process Exist Reason:${lastExit.reason} Status: ${lastExit.status}, Description: ${lastExit.description}")

                val isUnexpectedReason = when (lastExit.reason) {
                    ApplicationExitInfo.REASON_USER_REQUESTED -> true

                    else -> false
                }
                Log.e("GANESH", "checkAppPreviousExit - diedUnexpectedly $diedUnexpectedly")

                if (diedUnexpectedly && isUnexpectedReason) {
                    reportForceKill()

                    logger?.debug(
                        "ForceRestartTracker → App Unexpectedly Exit at ${lastExit.timestamp}, Status: ${lastExit.status}, Description: ${lastExit.description}"
                    )
                }
            } else {
                Log.e("GANESH", "checkAppPreviousExit - Process Exist Info is Empty")
            }
        } else if (diedUnexpectedly) {
            //reportForceKill()
            logger?.debug(
                "ForceRestartTracker → App Unexpectedly Exit - lastAliveTime=$lastForegroundTime"
            )
        }
    }

    private fun reportForceKill() {
        val prefs =
            context.getSharedPreferences(Tracker.SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)

        val pageName = prefs.getString(FIELD_PAGE_NAME, "")
        val pageType = prefs.getString(FIELD_CONTENT_GROUP_NAME, "")
        val txnName = prefs.getString(FIELD_TRAFFIC_SEGMENT_NAME, "")

        appForceKillReportJob?.cancel()

        appForceKillReportJob = scope.launch {
            while (Tracker.instance == null) delay(5)

            reporter.reportForceKill(pageName, pageType, txnName)
            appForceKillReportJob?.cancel()
        }
    }
}