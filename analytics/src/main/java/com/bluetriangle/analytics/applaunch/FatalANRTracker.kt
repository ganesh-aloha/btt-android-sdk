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
import com.bluetriangle.analytics.Timer.Companion.FIELD_PAGE_NAME
import com.bluetriangle.analytics.Timer.Companion.FIELD_SESSION_ID
import com.bluetriangle.analytics.Timer.Companion.FIELD_TRAFFIC_SEGMENT_NAME
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.Tracker.Companion.SHARED_PREFERENCES_NAME
import com.bluetriangle.analytics.anrwatchdog.ANRWarningException
import com.bluetriangle.analytics.anrwatchdog.AnrListener
import com.bluetriangle.analytics.deviceinfo.IDeviceInfoProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reports the exit of the previous app process as an error, when the process was killed by a fatal
 * ANR or by the low memory killer.
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
            return
        }

        reportAppExit(lastExit, errorType)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun errorTypeOf(exitInfo: ApplicationExitInfo): Tracker.BTErrorType? {
        return when (exitInfo.reason) {
            ApplicationExitInfo.REASON_ANR -> Tracker.BTErrorType.FatalANR
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

        // A trace is only attached to ANR (and native crash) exits, describe the exit otherwise
        val stackTrace = readExitStackTrace(exitInfo) ?: describeAppExit(exitInfo)

        tracker.trackerExecutor.submit(
            CrashRunnable(
                tracker.configuration,
                stackTrace,
                exitInfo.timestamp.toString(),
                errorType,
                mostRecentTimer = buildExitTimer(errorType),
                deviceInfoProvider = deviceInfoProvider,
                breadcrumbs = tracker.breadcrumbsManager?.getCachedSnapshot()
            )
        )
    }

    private fun buildExitTimer(errorType: Tracker.BTErrorType): Timer {
        val prefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)

        val sessionId = prefs.getString("ANR_$FIELD_SESSION_ID", "")
        val pageName = prefs.getString("ANR_$FIELD_PAGE_NAME", "")
        val pageType = prefs.getString("ANR_$FIELD_CONTENT_GROUP_NAME", "")
        val txnName = prefs.getString("ANR_$FIELD_TRAFFIC_SEGMENT_NAME", "")

        return Timer().apply {
            startWithoutPerformanceMonitor()
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

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readExitStackTrace(exitInfo: ApplicationExitInfo): String? {
        return try {
            exitInfo.traceInputStream?.use { stream ->
                // ANR traces hold every thread of the process and can be a few MB, cap what gets uploaded
                val trace = stream.bufferedReader().readText().take(MAX_EXIT_TRACE_CHAR_LENGTH).trim().removePrefix(ANR_TRACE_SUBJECT_PREFIX)
                formatAnrTrace(trace)
            }
        } catch (e: Throwable) {
            logger?.error("Error while reading last exit trace: ${e.message}")
            null
        }
    }

    private fun formatAnrTrace(rawTrace: String): String {
        val lines = rawTrace.lines()
        val subLines = arrayListOf("$FATAL_ANR_PREFIX${lines.first()}")

        val threadDump = lines.drop(1)
        val appFrame = appFrameOf(threadDump)
        appFrame?.let{
            subLines.add(it)
            subLines.add("")
            subLines.add("")
        }

        val stackTrace = subLines.joinToString("\n") + rawTrace
        return stackTrace.replace(Regex("\\r?\\n"), "~~")

//        return buildString {
//            for (line in subLines) {
//                append(line)
//                append("~~")
//            }
//
//            for (line in threadDump) {
//                //if (line.isNotBlank()) {
//                    append(line)
//                    append("~~")
//                ///}
//            }
//        }
    }

    /**
     * The app's topmost stack frame of the thread dump, ie the first "at <package>..." line
     */
    private fun appFrameOf(threadDump: List<String>): String? {
        val threadsIndex = threadDump.indexOfFirst { it.contains(DALVIK_THREADS_MARKER) }
        if (threadsIndex < 0) return null

        return threadDump.asSequence()
            .drop(threadsIndex + 1)
            .map(String::trim)
            .firstOrNull { it.startsWith(STACK_FRAME_PREFIX) && it.contains(context.packageName) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describeAppExit(exitInfo: ApplicationExitInfo): String {
        val reason = when (exitInfo.reason) {
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Low Memory"
            else -> exitInfo.reason.toString()
        }

        return "App process was killed by the system. Reason: $reason, " +
                "Description: ${exitInfo.description}, Status: ${exitInfo.status}, " +
                "Importance: ${exitInfo.importance}, PSS: ${exitInfo.pss} kB, RSS: ${exitInfo.rss} kB"
    }

    override fun onAppNotResponding(error: ANRWarningException) {
        Tracker.instance?.breadcrumbsManager?.dump()
        val timer = Tracker.instance?.getMostRecentTimer()
        timer?.let { timer ->
            val prefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
            prefs?.edit {
                putString("ANR_$FIELD_SESSION_ID", timer.getField(FIELD_SESSION_ID))
                putString("ANR_$FIELD_PAGE_NAME", timer.getField(FIELD_PAGE_NAME))
                putString("ANR_$FIELD_CONTENT_GROUP_NAME", timer.getField(FIELD_CONTENT_GROUP_NAME))
                putString(
                    "ANR_$FIELD_TRAFFIC_SEGMENT_NAME",
                    timer.getField(FIELD_TRAFFIC_SEGMENT_NAME)
                )
            }
        }
    }

    companion object {
        /**
         * Maximum number of characters of an exit trace that gets reported
         */
        private const val MAX_EXIT_TRACE_CHAR_LENGTH = 100_000

        /**
         * Header the platform puts in front of the ANR reason in the trace
         */
        private const val ANR_TRACE_SUBJECT_PREFIX = "Subject: "

        /**
         * Prefix the ANR reason is reported with
         */
        private const val FATAL_ANR_PREFIX = "Fatal ANR: "

        /**
         * Start of the thread dump section of an ANR trace
         */
        private const val DALVIK_THREADS_MARKER = "DALVIK THREADS"

        private const val STACK_FRAME_PREFIX = "at "

        /**
         * Line break of the reported trace
         */
        private const val LINE_SEPARATOR = "~~"
    }
}
