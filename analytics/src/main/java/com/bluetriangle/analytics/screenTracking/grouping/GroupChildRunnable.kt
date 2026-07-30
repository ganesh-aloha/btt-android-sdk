package com.bluetriangle.analytics.screenTracking.grouping

import androidx.core.net.toUri
import com.bluetriangle.analytics.BlueTriangleConfiguration
import com.bluetriangle.analytics.Constants
import com.bluetriangle.analytics.Payload
import com.bluetriangle.analytics.Timer
import com.bluetriangle.analytics.Utils
import com.bluetriangle.analytics.caching.classifier.CacheType
import com.bluetriangle.analytics.networkcapture.CapturedRequest
import com.bluetriangle.analytics.networkcapture.CapturedRequest.Companion.FIELD_DURATION
import com.bluetriangle.analytics.networkcapture.CapturedRequest.Companion.FIELD_FILE
import com.bluetriangle.analytics.networkcapture.CapturedRequest.Companion.FIELD_START_TIME
import com.bluetriangle.analytics.screenTracking.grouping.BTTChildView.Companion.ENTRY_TYPE
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class GroupChildRunnable(
    private val configuration: BlueTriangleConfiguration,
    private val groupTimer: Timer,
    private val childViews: List<BTTChildView>
) : Runnable {
    override fun run() {
        try {
            submitChildViews(childViews)
        } catch (e: Exception) {
            configuration.logger?.error("Error while submitting captured requests: ${e.message}")
        }
    }

    private fun submitChildViews(childViews: List<BTTChildView>) {
        var connection: HttpsURLConnection? = null
        val payloadData = buildChildViewsData(childViews, if (configuration.isDebug) 2 else 0)
        var url = configuration.networkCaptureUrl
        try {
            url = groupTimer.buildUrl(configuration.networkCaptureUrl)
            configuration.logger?.debug("Submitting $groupTimer to $url")
            configuration.logger?.debug("$groupTimer payload: $payloadData")
            val requestUrl = URL(url)
            connection = requestUrl.openConnection() as HttpsURLConnection
            connection.requestMethod = Constants.METHOD_POST
            connection.setRequestProperty(
                Constants.HEADER_CONTENT_TYPE,
                Constants.CONTENT_TYPE_JSON
            )
            connection.setRequestProperty(Constants.HEADER_USER_AGENT, configuration.userAgent)
            connection.doOutput = true
            DataOutputStream(connection.outputStream).use { it.write(Utils.b64encode(payloadData)) }
            val statusCode = connection.responseCode
            if (statusCode >= 300) {
                val responseBody =
                    BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                configuration.logger?.error("Server Error submitting $groupTimer: $statusCode - $responseBody")

                // If server error, cache the payload and try again later
                if (statusCode >= 500) {
                    cachePayload(url, payloadData)
                }
            } else {
                configuration.logger?.debug("$groupTimer submitted successfully")
            }
            connection.getHeaderField(0)
        } catch (e: Exception) {
            configuration.logger?.error(
                e,
                "Android Error submitting $groupTimer: ${e.message}"
            )
            cachePayload(url, payloadData)
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildChildViewsData(childViews: List<BTTChildView>, indentSpaces: Int): String {
        return JSONArray(childViews.map {
            JSONObject().apply {
                put(FIELD_FILE, it.className)
                put(FIELD_DURATION, it.pageTime)
                put(FIELD_START_TIME, it.startTime)
                put(CapturedRequest.FIELD_ENTRY_TYPE, ENTRY_TYPE)
                put(CapturedRequest.FIELD_END_TIME, it.endTime)
                put(CapturedRequest.FIELD_URL, it.pageName)
                put(Timer.FIELD_NATIVE_APP, it.nativeAppProperties.toJSONObject(false))
                it.performanceMetrics?.forEach { perf ->
                    put(perf.key, perf.value)
                }
            }
        }).toString(indentSpaces)
    }

    private fun Timer.buildUrl(baseUrl: String): String {
        return baseUrl.toUri().buildUpon()
            .appendQueryParameter(Timer.FIELD_SITE_ID, getField(Timer.FIELD_SITE_ID))
            .appendQueryParameter(Timer.FIELD_NAVIGATION_START, getField(Timer.FIELD_NST))
            .appendQueryParameter(Timer.FIELD_TRAFFIC_SEGMENT_NAME, getField(Timer.FIELD_TRAFFIC_SEGMENT_NAME))
            .appendQueryParameter(Timer.FIELD_CONTENT_GROUP_NAME, getField(Timer.FIELD_TRAFFIC_SEGMENT_NAME))
            .appendQueryParameter(Timer.FIELD_LONG_SESSION_ID, getField(Timer.FIELD_SESSION_ID))
            .appendQueryParameter(Timer.FIELD_PAGE_NAME, getField(Timer.FIELD_PAGE_NAME))
            .appendQueryParameter(Timer.FIELD_CONTENT_GROUP_NAME, getField(Timer.FIELD_CONTENT_GROUP_NAME))
            .appendQueryParameter(Timer.FIELD_WCDTT, "c")
            .appendQueryParameter(Timer.FIELD_NATIVE_OS, Constants.OS)
            .appendQueryParameter(Timer.FIELD_BROWSER, Constants.BROWSER)
            .appendQueryParameter(Timer.FIELD_BROWSER_VERSION, getField(Timer.FIELD_BROWSER_VERSION))
            .appendQueryParameter(Timer.FIELD_DEVICE, getField(Timer.FIELD_DEVICE))
            .build().toString()
    }

    /**
     * Cache the crash report to try and send again in the future
     * @param url URL to send to
     * @param payloadData payload data to send
     */
    private fun cachePayload(url: String, payloadData: String) {
        configuration.logger?.info("Caching network capture report")
        configuration.payloadCache?.save(
            Payload(
                url = url,
                data = payloadData,
                type = CacheType.Wcd,
                createdAt = System.currentTimeMillis()
            )
        )
    }
}
