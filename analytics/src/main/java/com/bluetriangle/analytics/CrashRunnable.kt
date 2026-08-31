package com.bluetriangle.analytics

import androidx.core.net.toUri
import com.bluetriangle.analytics.Constants.TIMER_MIN_PGTM
import com.bluetriangle.analytics.Timer.Companion.FIELD_NATIVE_APP
import com.bluetriangle.analytics.caching.classifier.CacheType
import com.bluetriangle.analytics.deviceinfo.IDeviceInfoProvider
import com.bluetriangle.analytics.networkstate.BTTNetworkState
import com.bluetriangle.analytics.utility.value
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.collections.toMap

/**
 * Create a timer runnable to submit the given timer to the given URL
 *
 * @param configuration  the tracker configuration
 * @param stackTrace     the error stack trace
 * @param timeStamp      time stamp when error occurred
 * @param crashHitsTimer crash hit timer
 */
internal class CrashRunnable(
    /**
     * Tracker configuration
     */
    private val configuration: BlueTriangleConfiguration,
    /**
     * This is the crash report
     */
    private val stackTrace: String,
    /**
     * This is the epoch when the error happened
     */
    private val timeStamp: String,
    private val errorType: Tracker.BTErrorType = Tracker.BTErrorType.NativeAppCrash,
    private val mostRecentTimer: Timer? = null,
    private val errorCount: Int = 1,
    private val deviceInfoProvider: IDeviceInfoProvider,
    private val breadcrumbs: JSONArray? = null
) : Runnable {

    private var timerFields = mutableMapOf<String, String?>()

    override fun run() {
        try {
            mostRecentTimer?.let {
                Tracker.instance?.applyGlobalFields(it)
                loadTimerFields(it)
                if(errorType == Tracker.BTErrorType.NativeAppCrash || errorType == Tracker.BTErrorType.FatalANR) {
                    submitTimer(it, true)
                }
            }
            if(mostRecentTimer == null) {
                val timer = buildCrashHitsTimer()
                submitTimer(timer, false)
            }
            submitCrashReport()
        } catch (e: Exception) {
            configuration.logger?.error("Error while submitting crash report: ${e.message}")
        }
    }

    private fun buildCrashHitsTimer() = Timer().apply {
        startWithoutPerformanceMonitor()
        nativeAppProperties.add(deviceInfoProvider.getDeviceInfo())
        nativeAppProperties.event = errorType.event
        setError(true)
        pageTimeCalculator = {
            TIMER_MIN_PGTM
        }
        setField(Timer.FIELD_EXCLUDED, "20")
        setPageName(errorType.errorName)
    }

    private fun submitTimer(timer: Timer, shouldSendCapturedRequests: Boolean) {
        val tracker = Tracker.instance ?: return

        val timerRunnable = tracker.prepareTimerRunnable(timer, shouldSendCapturedRequests)
        loadTimerFields(timer)
        timerRunnable.run()
    }

    private fun buildCrashReportUrl(): String {
        return configuration.errorReportingUrl.toUri()
            .buildUpon()
            .appendQueryParameter(Timer.FIELD_SITE_ID, configuration.siteId)
            .appendQueryParameter(
                Timer.FIELD_NAVIGATION_START,
                timerFields[Timer.FIELD_NST]
            )
            .appendQueryParameter(
                Timer.FIELD_PAGE_NAME,
                timerFields[Timer.FIELD_PAGE_NAME]
            )
            .appendQueryParameter(
                Timer.FIELD_TRAFFIC_SEGMENT_NAME,
                timerFields[Timer.FIELD_TRAFFIC_SEGMENT_NAME]
            )
            .appendQueryParameter(
                Timer.FIELD_NATIVE_OS,
                timerFields[Timer.FIELD_NATIVE_OS]
            )
            .appendQueryParameter(
                Timer.FIELD_DEVICE,
                timerFields[Timer.FIELD_DEVICE]
            )
            .appendQueryParameter(Timer.FIELD_BROWSER, Constants.BROWSER)
            .appendQueryParameter(
                Timer.FIELD_BROWSER_VERSION,
                timerFields[Timer.FIELD_BROWSER_VERSION]
            )
            .appendQueryParameter(Timer.FIELD_LONG_SESSION_ID, configuration.sessionId)
            .appendQueryParameter(Timer.FIELD_PAGE_TIME, timerFields[Timer.FIELD_PAGE_TIME])
            .appendQueryParameter(
                Timer.FIELD_CONTENT_GROUP_NAME,
                timerFields[Timer.FIELD_CONTENT_GROUP_NAME]
            )
            .appendQueryParameter(
                Timer.FIELD_AB_TEST_ID,
                timerFields[Timer.FIELD_AB_TEST_ID]
            )
            .appendQueryParameter(
                Timer.FIELD_DATACENTER,
                timerFields[Timer.FIELD_DATACENTER]
            )
            .appendQueryParameter(
                Timer.FIELD_CAMPAIGN_NAME,
                timerFields[Timer.FIELD_CAMPAIGN_NAME]
            )
            .appendQueryParameter(
                Timer.FIELD_CAMPAIGN_MEDIUM,
                timerFields[Timer.FIELD_CAMPAIGN_MEDIUM]
            )
            .appendQueryParameter(
                Timer.FIELD_CAMPAIGN_SOURCE,
                timerFields[Timer.FIELD_CAMPAIGN_SOURCE]
            )
            .build().toString()
    }

    private fun submitCrashReport() {
        var connection: HttpsURLConnection? = null
        val crashReportUrl = buildCrashReportUrl()
        configuration.logger?.debug("Crash Report URL: $crashReportUrl")
        val payloadData = buildCrashReportData()
        try {
            val url = URL(crashReportUrl)
            connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = Constants.METHOD_POST
            connection.setRequestProperty(
                Constants.HEADER_CONTENT_TYPE,
                Constants.CONTENT_TYPE_JSON
            )
            connection.setRequestProperty(Constants.HEADER_USER_AGENT, configuration.userAgent)
            connection.doOutput = true
            connection.doInput = false
            DataOutputStream(connection.outputStream).use { it.write(Utils.b64encode(payloadData)) }
            val statusCode = connection.responseCode
            if (statusCode >= 300) {
                val responseBody =
                    BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                configuration.logger?.error("Error submitting crash report: $statusCode - $responseBody")
            }
            // If server error, cache the payload and try again later
            if (statusCode >= 500) {
                cachePayload(crashReportUrl, payloadData)
            }
            connection.getHeaderField(0)
        } catch (e: Exception) {
            configuration.logger?.error(e, "Error submitting crash report: ${e.message}")
            cachePayload(crashReportUrl, payloadData)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Cache the crash report to try and send again in the future
     * @param url URL to send to
     * @param payloadData payload data to send
     */
    private fun cachePayload(url: String, payloadData: String) {
        configuration.logger?.info("Caching crash report")
        configuration.payloadCache?.save(
            Payload(
                url = url,
                data = payloadData,
                type = CacheType.Error,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * build the JSON payload of crash data
     *
     * @return base 64 encoded JSON payload
     */
    private fun buildCrashReportData(): String {
        val crashReport = mutableMapOf<String, Any?>(
            "msg" to stackTrace,
            "eTp" to errorType.errorName,
            "eCnt" to errorCount.toString(),
            "url" to configuration.applicationName,
            "line" to "1",
            "col" to "1",
            "time" to timeStamp,
        )

        val netStateMonitor = Tracker.instance?.networkStateMonitor

        val nativeAppProperties = ErrorNativeAppProperties()

        netStateMonitor?.state?.value?.let {
            nativeAppProperties.netState = it.value
            if(it is BTTNetworkState.Cellular) {
                nativeAppProperties.netStateSource = it.source
            }
        }
        nativeAppProperties.add(deviceInfoProvider.getDeviceInfo())
        nativeAppProperties.breadcrumbs = breadcrumbs?.toString()
        crashReport[FIELD_NATIVE_APP] = nativeAppProperties.toMap()

        val crashDataArray = JSONArray(listOf(JSONObject(crashReport.toMap())))
        val jsonData = crashDataArray.toString(if (configuration.isDebug) 2 else 0)
        configuration.logger?.debug("Crash Report Data: $jsonData")
        return jsonData
    }

    private fun loadTimerFields(from: Timer) {
        val keysAndDefaults = mutableMapOf(
            Timer.FIELD_NST to null,
            Timer.FIELD_PAGE_NAME to errorType.errorName,
            Timer.FIELD_TRAFFIC_SEGMENT_NAME to null,
            Timer.FIELD_NATIVE_OS to Constants.OS,
            Timer.FIELD_DEVICE to Constants.DEVICE_MOBILE,
            Timer.FIELD_BROWSER_VERSION to null,
            Timer.FIELD_PAGE_TIME to null,
            Timer.FIELD_CONTENT_GROUP_NAME to null,
            Timer.FIELD_AB_TEST_ID to "Default",
            Timer.FIELD_DATACENTER to "Default",
            Timer.FIELD_CAMPAIGN_NAME to "",
            Timer.FIELD_CAMPAIGN_MEDIUM to Constants.OS,
            Timer.FIELD_CAMPAIGN_SOURCE to errorType.errorName,
        )

        keysAndDefaults.forEach {
            val value = it.value
            timerFields[it.key] = if(value == null) {
                from.getField(it.key)
            } else {
                from.getField(it.key, value)
            }
        }
    }

}
