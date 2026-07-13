package com.bluetriangle.analytics

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_GROUPING
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_WEB_VIEW_STITCHING
import java.util.concurrent.TimeUnit

class BlueTriangleConfiguration {
    /**
     * The Blue Triangle site ID
     */
    var siteId: String? = null

    /**
     * Global User ID
     */
    var globalUserId: String? = null

    /**
     * Session ID
     */
    var sessionId: String? = null
        @Synchronized get
        @Synchronized set

    /**
     * the application's name
     */
    var applicationName: String? = null

    /**
     * SDK's user agent
     */
    var userAgent: String? = null

    /**
     * Percentage of sessions for which network calls will be captured. A value of `0.05` means that 5% of session's network requests will be tracked.
     * A value of `0.0` means that no network requests will be captured for the session, a value of `1.0` will track all network requests for a session.
     */
    var networkSampleRate = Constants.DEFAULT_NETWORK_SAMPLE_RATE
        set(networkSampleRate) {
            field = networkSampleRate.coerceAtLeast(0.0).coerceAtMost(1.0)
        }

    /**
     * if network requests should be captured
     */
    var shouldSampleNetwork = false

    var isDebug = false
        set(value) {
            field = value
            if (!field)
                logger = null
        }

    var debugLevel = Log.DEBUG
    var logger: Logger? = null
        get() {
            if (field == null && isDebug) {
                field = AndroidLogger(debugLevel)
            }
            return field
        }

    var trackerUrl = DEFAULT_TRACKER_URL

    var errorReportingUrl = DEFAULT_ERROR_REPORTING_URL

    var networkCaptureUrl = DEFAULT_NETWORK_CAPTURE_URL

    /**
     * Cache directory path
     */
    var cacheDirectory: String? = null

    /**
     * Max items in the cache
     */
    @Deprecated(message = "Max cache items has been replaced in favor of cacheMemoryLimit", replaceWith = ReplaceWith("cacheMemoryLimit"))
    var maxCacheItems = 100

    /**
     * Max attempts to resend a payload
     */
    var maxAttempts = 3

    /**
     * the instance to cache payloads
     */
    var payloadCache: PayloadCache? = null
        get() {
            if (field == null) {
                field = PayloadCache(this)
            }
            return field
        }

    /**
     * Enable or Disable automatic crash detection and reporting
     */
    var isTrackCrashesEnabled = true

    /**
     * Enable or disable monitoring and reporting performance metrics
     */
    var isPerformanceMonitorEnabled = true

    /**
     * Enable or disable memory warning detection and reporting
     */
    var isMemoryWarningEnabled = true

    /**
     * Set the sampling interval for performance monitoring in milliseconds
     */
    var performanceMonitorIntervalMs = TimeUnit.SECONDS.toMillis(1)
        set(value) {
            field = value.coerceAtLeast(500L)
        }

    /**
     * Enable or disable ANR detection and sending reports to the server.
     */
    var isTrackAnrEnabled: Boolean = true

    /**
     * time interval for ANR warning based on track ANR is enabled or disabled, default to 5 seconds, minimum is 3 second, if set less then minimum allowed set value is ignored
     */
    var trackAnrIntervalSec = Constants.ANR_DEFAULT_INTERVAL

    /**
     * Enable or disable automatic tracking and reporting of page views for Activity and Fragments.
     * For Jetpack compose use BttTimerEffect
     */
    var isScreenTrackingEnabled: Boolean = true

    /**
     * Enable or disable tracking and reporting launch time.
     * Supported on API Level 29 and above
     */
    var isLaunchTimeEnabled: Boolean = true

    /**
     * sets the duration after which a payload item in cache gets expired.
     */
    var cacheExpiryDuration = EXPIRATION_IN_MILLIS
        set(value) {
            field = value.coerceAtLeast(MIN_EXPIRY_DURATION).coerceAtMost(MAX_EXPIRY_DURATION)
        }

    /**
     * sets the amounts of bytes the SDK can utilize for caching.
     */
    var cacheMemoryLimit = MEMORY_LIMIT
        set(value) {
            field = value.coerceAtLeast(MIN_MEMORY_LIMIT).coerceAtMost(MAX_MEMORY_LIMIT)
        }

    var sessionExpiryDuration:Long = SESSION_EXPIRATION_IN_MILLIS
        private set

    /**
     * Track the network state during Timer, Network request and errors. States include wifi, cellular, ethernet and offline.
     * Default value is false.
     * Requires host app to have ACCESS_NETWORK_STATE permission.
     */
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    var isTrackNetworkStateEnabled: Boolean = true

    internal var isGroupingEnabled: Boolean = DEFAULT_ENABLE_GROUPING

    internal var groupingIdleTime: Int = Constants.DEFAULT_GROUPING_IDLE_TIME
        set(value) {
            field = value.coerceAtLeast(1)
        }

    internal var isGroupingTapDetectionEnabled: Boolean = Constants.DEFAULT_ENABLE_GROUPING_TAP_DETECTION

    internal var groupedViewSampleRate = Constants.DEFAULT_GROUPED_VIEW_SAMPLE_RATE
        set(value) {
            field = value.coerceAtLeast(0.0).coerceAtMost(1.0)
        }

    internal var isWebViewStitchingEnabled: Boolean = DEFAULT_ENABLE_WEB_VIEW_STITCHING

    internal val shouldDetectTap: Boolean
        get() = isGroupingEnabled && isGroupingTapDetectionEnabled

    internal var bttPluginVersion: String? = null

    /**
     * Enable or disable App Install sending reports to the server.
     */
    var isAppInstallEnabled: Boolean = true

    /**
     * Enable or disable App Force Restart sending reports to the server.
     */
    var isForceRestartEnable: Boolean = true

    /**
     * Enable or disable App Force Restart sending reports to the server.
     */
    var forceRestartDuration: Double = 10.0

    companion object {
        const val DEFAULT_TRACKER_URL = "https://d.btttag.com/analytics.rcv"
        const val DEFAULT_ERROR_REPORTING_URL = "https://d.btttag.com/err.rcv"
        const val DEFAULT_NETWORK_CAPTURE_URL = "https://d.btttag.com/wcdv02.rcv"

        private const val KB = 1024L
        private const val MB = 1024 * 1024L
        private const val MIN = 60 * 1000L
        private const val HOUR = 60 * MIN
        private const val MIN_MEMORY_LIMIT = 10 * MB
        private const val MAX_MEMORY_LIMIT = 300 * MB
        private const val MEMORY_LIMIT = 30 * MB
        private val MIN_EXPIRY_DURATION = 1 * MIN
        private val EXPIRATION_IN_MILLIS = 48 * HOUR
        private val MAX_EXPIRY_DURATION = 240 * HOUR

        private val SESSION_EXPIRATION_IN_MILLIS = 30 * MIN
    }

    override fun toString(): String {
        return """configurations = {
            siteId : "$siteId"
            sessionId : "$sessionId"
            applicationName : "$applicationName"
            networkSampleRate : $networkSampleRate
            shouldSampleNetwork : $shouldSampleNetwork
            isDebug : $isDebug
            debugLevel : $debugLevel
            isTrackCrashesEnabled : $isTrackCrashesEnabled
            isPerformanceMonitorEnabled : $isPerformanceMonitorEnabled
            isMemoryWarningEnabled : $isMemoryWarningEnabled
            performanceMonitorIntervalMs : $performanceMonitorIntervalMs
            isTrackAnrEnabled : $isTrackAnrEnabled
            trackAnrIntervalSec : $trackAnrIntervalSec
            isScreenTrackingEnabled : $isScreenTrackingEnabled
            isLaunchTimeEnabled : $isLaunchTimeEnabled
            cacheExpiryDuration : $cacheExpiryDuration
            cacheMemoryLimit : $cacheMemoryLimit
            isTrackNetworkStateEnabled : $isTrackNetworkStateEnabled,
            isGroupingEnabled : $isGroupingEnabled,
            groupingIdleTime : $groupingIdleTime,
            isAppInstallEnabled : $isAppInstallEnabled,
            isForceRestartEnable : $isForceRestartEnable,
            forceRestartDuration : $forceRestartDuration
        }
        """.trimIndent()
    }
}
