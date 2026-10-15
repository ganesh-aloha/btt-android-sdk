package com.bluetriangle.analytics

import com.bluetriangle.analytics.Timer.Companion.FIELD_NATIVE_APP
import com.bluetriangle.analytics.caching.classifier.CacheType
import com.bluetriangle.analytics.eventhub.sdkeventhub.SDKEventHub
import com.bluetriangle.analytics.networkcapture.CapturedRequestRunnable
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Create a timer runnable to submit the given timer to the given URL
 *
 * @param configuration tracker configuration
 * @param timer      the timer to submit
 */
internal class TimerRunnable(
    /**
     * The tracker configuration
     */
    private val configuration: BlueTriangleConfiguration,
    /**
     * The timer to submit
     */
    val timer: Timer,
    val shouldSendCapturedRequests: Boolean = true
) : Runnable {
    override fun run() {
        try {
            var connection: HttpsURLConnection? = null
            val payloadData = buildTimerData()
            var capturedRequestCollections = if(shouldSendCapturedRequests) {
                Tracker.instance?.getCapturedRequestCollectionsForTimer(timer)
            } else {
                configuration.logger?.info("shouldSendCapturedRequests is false, ignoring captured requests collections for timer: ${timer}")
                listOf()
            }

            val requestStartTime = System.currentTimeMillis()
            try {
                val url = URL(configuration.trackerUrl)
                connection = url.openConnection() as HttpsURLConnection
                connection.requestMethod = Constants.METHOD_POST
                connection.setRequestProperty(
                    Constants.HEADER_CONTENT_TYPE,
                    Constants.CONTENT_TYPE_JSON
                )
                connection.setRequestProperty(Constants.HEADER_USER_AGENT, configuration.userAgent)
                connection.doOutput = true
                DataOutputStream(connection.outputStream).use { it.write(Utils.b64encode(payloadData)) }
                val statusCode = connection.responseCode
                val responseBody = BttBeaconLogger.readResponseBody(connection, statusCode)
                BttBeaconLogger.trace(configuration.trackerUrl, payloadData, requestStartTime, responseBody)
                if (!capturedRequestCollections.isNullOrEmpty()) {
                    CapturedRequestRunnable(configuration, capturedRequestCollections).run()
                    capturedRequestCollections = null
                }
                SDKEventHub.instance.onTimerSubmitted(timer)
                if (statusCode >= 300) {
                    configuration.logger?.error("Server Error submitting $timer: $statusCode - $responseBody")

                    // If server error, cache the payload and try again later
                    if (statusCode >= 500) {
                        cachePayload(configuration.trackerUrl, payloadData)
                    }
                } else {
                    configuration.logger?.debug("$url\n$timer submitted successfully")

                    // successfully submitted a timer, lets check if there are any cached timers that we can try and submit too
                    val nextCachedPayload = configuration.payloadCache?.pickNext()
                    if (nextCachedPayload != null) {
                        Tracker.instance?.submitPayload(nextCachedPayload)
                    }
                }
                connection.getHeaderField(0)
            } catch (e: Exception) {
                BttBeaconLogger.trace(configuration.trackerUrl, payloadData, requestStartTime, error = e)
                if (!capturedRequestCollections.isNullOrEmpty()) {
                    CapturedRequestRunnable(configuration, capturedRequestCollections).run()
                }
                configuration.logger?.error(e, "Android Error submitting $timer: ${e.message}")
                cachePayload(configuration.trackerUrl, payloadData)
            } finally {
                connection?.disconnect()
            }
        } catch(e: Exception) {
            configuration.logger?.error("Error while submitting timer: ${e.message}")
        }
    }

    /**
     * Build the JSON data to send to the API
     *
     * @return JSON string
     */
    private fun buildTimerData(): String {
        val data = JSONObject(timer.getFields())
        val nativeProps = timer.nativeAppProperties
        data.put(FIELD_NATIVE_APP, nativeProps.toJSONObject())
        val jsonData = data.toString(if (configuration.isDebug) 2 else 0)
        configuration.logger?.debug("Timer Data: $jsonData")
        return jsonData
    }

    /**
     * Cache the current timer to try and send again in the future
     * @param url URL to send to
     * @param payloadData payload data to send
     */
    private fun cachePayload(url: String, payloadData: String) {
        configuration.logger?.info("Caching timer $timer")
        configuration.payloadCache?.save(
            Payload(
                url = url,
                data = payloadData,
                type = CacheType.Analytics,
                createdAt = System.currentTimeMillis()
            )
        )
    }
}