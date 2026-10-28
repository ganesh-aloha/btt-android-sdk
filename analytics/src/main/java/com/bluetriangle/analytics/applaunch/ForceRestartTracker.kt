package com.bluetriangle.analytics.applaunch

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
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
        if (lastForegroundTime == 0L) {
            logger?.debug("ForceRestartTracker::checkAppPreviousExit lastForegroundTime=0, so it's first launch after install")
            return
        }

        // If the app was restarted within 10 seconds, we assume it was died unexpectedly
        val reLaunchTime = currentTime - lastForegroundTime
        val diedUnexpectedly = reLaunchTime < _forceRestartDuration

        logger?.debug("ForceRestartTracker::checkAppPreviousExit - Relaunch Time Interval = $reLaunchTime ms")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            // Retrieve the most recent exit reason for this app
            val exitList =
                activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 1)
            if (exitList.isNotEmpty()) {
                val lastExit = exitList[0]
                val isUnexpectedReason = when (lastExit.reason) {
                    ApplicationExitInfo.REASON_USER_REQUESTED -> true

                    else -> false
                }

                if (diedUnexpectedly && isUnexpectedReason) {
                    logger?.debug("ForceRestartTracker::checkAppPreviousExit App Unexpectedly Exit at ${lastExit.timestamp}, Status: ${lastExit.status}, Description: ${lastExit.description}")
                    reportForceKill(lastExit.timestamp)
                } else {
                    logger?.debug("ForceRestartTracker::checkAppPreviousExit - Process Exist Reason:${lastExit.reason} Status: ${lastExit.status}, Description: ${lastExit.description}")
                }
            } else {
                logger?.debug("ForceRestartTracker::checkAppPreviousExit - Last process exit reasons is empty")
            }
        } else if (diedUnexpectedly) {
            //reportForceKill()
            logger?.debug("ForceRestartTracker::checkAppPreviousExit App Unexpectedly Exit - lastForegroundTime=$lastForegroundTime")
        }
    }

    private fun reportForceKill(exitTime: Long) {
        logger?.debug("ForceRestartTracker::reportForceKill")

        val prefs =
            context.getSharedPreferences(Tracker.SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)

        // Page the user was on when the app was force restarted, saved by Tracker.onScreenPause
        val forceRestartScreen = ForceRestartScreen(
            nst = prefs.getString(Constants.LAST_SCREEN_NST, null)?.toLongOrNull(),
            sessionId = prefs.getString(Constants.LAST_SCREEN_SESSION_ID, null).nonBlank(),
            pageName = prefs.getString(FIELD_PAGE_NAME, null).nonBlank(),
            pageType = prefs.getString(FIELD_CONTENT_GROUP_NAME, null).nonBlank(),
            txnName = prefs.getString(FIELD_TRAFFIC_SEGMENT_NAME, null).nonBlank()
        )

        appForceKillReportJob?.cancel()

        appForceKillReportJob = scope.launch {
            while (Tracker.instance == null) delay(5)

            reporter.reportForceKill(forceRestartScreen, exitTime)
            appForceKillReportJob?.cancel()
        }
    }

    private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() && it != "null" }

    internal data class ForceRestartScreen(
        val nst: Long?,
        val sessionId: String?,
        val pageName: String?,
        val pageType: String?,
        val txnName: String?
    )
}