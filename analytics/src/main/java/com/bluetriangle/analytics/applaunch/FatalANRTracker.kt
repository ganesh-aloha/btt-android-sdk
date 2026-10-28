package com.bluetriangle.analytics.applaunch

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.bluetriangle.analytics.Constants
import com.bluetriangle.analytics.CrashRunnable
import com.bluetriangle.analytics.Logger
import com.bluetriangle.analytics.Timer
import com.bluetriangle.analytics.Timer.Companion.FIELD_CONTENT_GROUP_NAME
import com.bluetriangle.analytics.Timer.Companion.FIELD_NST
import com.bluetriangle.analytics.Timer.Companion.FIELD_PAGE_NAME
import com.bluetriangle.analytics.Timer.Companion.FIELD_SESSION_ID
import com.bluetriangle.analytics.Timer.Companion.FIELD_TRAFFIC_SEGMENT_NAME
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.Tracker.Companion.SHARED_PREFERENCES_NAME
import com.bluetriangle.analytics.anrwatchdog.ANRWarningException
import com.bluetriangle.analytics.anrwatchdog.AnrListener
import com.bluetriangle.analytics.deviceinfo.IDeviceInfoProvider
import com.bluetriangle.analytics.event.CrashSource
import com.bluetriangle.analytics.utility.CrashTraceParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reports the exit of the previous app process as an error, when the process was killed by a fatal
 * ANR, excessive resource usage or the low memory killer.
 *
 * Android only tells us why a process died on the next launch, so the check runs once per launch.
 * Everything it does blocks - a binder call for the exit record, reading the ANR trace file and a
 * network round trip for the report - so it all runs off the main thread.
 */
internal class FatalANRTracker(
    private val logger: Logger?,
    private val context: Context,
    private val deviceInfoProvider: IDeviceInfoProvider
) : AnrListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reportJob: Job? = null

    private fun addAnrListener() {
        Tracker.instance?.anrManager?.detector?.addAnrListener("Fatal ANR", this)
    }

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        reportJob?.cancel()
        reportJob = scope.launch {
            // CrashRunnable needs the tracker singleton, which is assigned only after Tracker.init() returns
            while (Tracker.instance == null) delay(5)

            addAnrListener()

            try {
                checkAppExitInfoAndReport()
            } catch (e: Exception) {
                logger?.error(e, "Error while reporting last app exit: ${e.message}")
            }

            reportJob = null
        }
    }

    fun stop() {
        reportJob?.cancel()
        reportJob = null

        Tracker.instance?.anrManager?.detector?.removeAnrListener("Fatal ANR")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun checkAppExitInfoAndReport() {
        val prefs = context.getSharedPreferences(
            Tracker.SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE
        )
        val lastReportedExitTime = prefs.getLong(Constants.APP_LAST_EXIT_INFO_TIME, 0L)

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        // Retrieve the most recent exit reason for this app
        val exitList = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 1)
        if (exitList.isEmpty()) {
            logger?.debug("AppExitInfoReporter::checkAppExitInfoAndReport - Last process exit reasons is empty")
            return
        }

        val lastExit = exitList.first()

        // The system keeps the record across launches, so report each exit only once
        if (lastExit.timestamp <= lastReportedExitTime) {
            logger?.debug("AppExitInfoReporter::checkAppExitInfoAndReport - Last exit at ${lastExit.timestamp} is already reported")
            return
        }

        prefs.edit { putLong(Constants.APP_LAST_EXIT_INFO_TIME, lastExit.timestamp) }

        val errorType = errorTypeOf(lastExit)
        if (errorType == null) {
            logger?.debug("AppExitInfoReporter::checkAppExitInfoAndReport - Skipping exit reason: ${lastExit.reason}")
        } else {
            reportAppExit(lastExit, errorType)
        }

        // The saved page belongs to the process of this exit, clear it so a later exit doesn't reuse it
        clearSavedAnrFields()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun errorTypeOf(exitInfo: ApplicationExitInfo): Tracker.BTErrorType? {
        return when (exitInfo.reason) {
            ApplicationExitInfo.REASON_ANR -> Tracker.BTErrorType.FatalANR
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> Tracker.BTErrorType.AccessResourceUsage
            else -> null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reportAppExit(exitInfo: ApplicationExitInfo, errorType: Tracker.BTErrorType) {
        val tracker = Tracker.instance ?: return

        logger?.debug(
            "AppExitInfoReporter::reportAppExit - Reported as ${errorType.errorName}, Reason: ${exitInfo.reason}, " +
                    "Status: ${exitInfo.status}, Description: ${exitInfo.description}, at ${exitInfo.timestamp}"
        )

        val exitInfoDetail = CrashTraceParser.parseApplicationExitInfo(exitInfo, errorType, errorType.getPrefix())

        tracker.trackerExecutor.submit(
            CrashRunnable(
                tracker.configuration,
                exitInfoDetail.stackTrace?.toString().orEmpty(),
                exitInfo.timestamp.toString(),
                errorType,
                title = exitInfoDetail.title,
                mostRecentTimer = buildExitTimer(errorType, exitInfo.timestamp),
                deviceInfoProvider = deviceInfoProvider,
                breadcrumbs = tracker.breadcrumbsManager?.getCachedSnapshot(),
                source = CrashSource.AppExitInformation
            )
        )
    }

    private fun clearSavedAnrFields() {
        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit {
            remove("ANR_$FIELD_NST")
            remove("ANR_$FIELD_SESSION_ID")
            remove("ANR_$FIELD_PAGE_NAME")
            remove("ANR_$FIELD_CONTENT_GROUP_NAME")
            remove("ANR_$FIELD_TRAFFIC_SEGMENT_NAME")
        }
    }

    private fun buildExitTimer(errorType: Tracker.BTErrorType, exitTime: Long): Timer {
        val prefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)

        fun savedField(field: String): String? =
            prefs.getString("ANR_$field", null)?.takeIf { it.isNotBlank() && it != "null" }

        val nst = savedField(FIELD_NST)?.toLongOrNull()
        val sessionId = savedField(FIELD_SESSION_ID)
        val pageName = savedField(FIELD_PAGE_NAME)
        val pageType = savedField(FIELD_CONTENT_GROUP_NAME)
        val txnName = savedField(FIELD_TRAFFIC_SEGMENT_NAME)

        // Started at the nst of the page the ANR happened on, so the error links to that page view
        return Timer().apply {
            startAt(nst ?: exitTime)
            setPageName(pageName ?: Constants.CRASH_PAGE_NAME)
            pageType?.let { setContentGroupName(pageType) }
            txnName?.let { setTrafficSegmentName(txnName) }
            sessionId?.let { setField(FIELD_SESSION_ID, sessionId) }
            setTimeOnPage(Constants.TIMER_MIN_PGTM)
            pageTimeCalculator = {
                Constants.TIMER_MIN_PGTM
            }
            generateNativeAppProperties()
            nativeAppProperties.loadTime = Constants.TIMER_MIN_PGTM
            nativeAppProperties.event = errorType.event
        }
    }

    override fun onAppNotResponding(error: ANRWarningException) {
        Tracker.instance?.breadcrumbsManager?.dump()
        val timer = Tracker.instance?.getMostRecentTimer()
        timer?.let { timer ->
            val prefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
            prefs?.edit {
                putString("ANR_$FIELD_NST", timer.getField(FIELD_NST))
                // A running timer gets its session ID only when submitted, save the current session
                putString("ANR_$FIELD_SESSION_ID", Tracker.instance?.configuration?.sessionId ?: timer.getField(FIELD_SESSION_ID))
                putString("ANR_$FIELD_PAGE_NAME", timer.getField(FIELD_PAGE_NAME))
                putString("ANR_$FIELD_CONTENT_GROUP_NAME", timer.getField(FIELD_CONTENT_GROUP_NAME))
                putString(
                    "ANR_$FIELD_TRAFFIC_SEGMENT_NAME",
                    timer.getField(FIELD_TRAFFIC_SEGMENT_NAME)
                )
            }
        }
    }

    fun Tracker.BTErrorType.getPrefix(): String {
        return when (this) {
            Tracker.BTErrorType.FatalANR -> "Fatal ANR:"
            Tracker.BTErrorType.AccessResourceUsage -> "Excess Resource Usage:"
            else -> ""
        }
    }
}
