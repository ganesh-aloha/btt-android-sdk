/*
 * Copyright (c) 2024, Blue Triangle
 * All rights reserved.
 *
 */
package com.bluetriangle.analytics.sessionmanager

import com.bluetriangle.analytics.Constants
import com.bluetriangle.analytics.Constants.CONFIG_KEY
import com.bluetriangle.analytics.Constants.DEFAULT_CONFIG_KEY
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_ANR_TRACKING
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_CRASH_TRACKING
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_GROUPING
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_GROUPING_TAP_DETECTION
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_RESPONSIVENESS
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_LAUNCH_TIME
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_MEMORY_WARNING
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_NETWORK_STATE_TRACKING
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_WEB_VIEW_STITCHING
import com.bluetriangle.analytics.Constants.DEFAULT_NETWORK_SAMPLE_RATE
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.breadcrumbs.config.BreadcrumbsConfig
import com.bluetriangle.analytics.breadcrumbs.config.BreadcrumbsConfigMapper
import com.bluetriangle.analytics.checkout.config.CheckoutConfig
import com.bluetriangle.analytics.checkout.config.CheckoutConfigMapper
import com.bluetriangle.analytics.utility.getBooleanOrNull
import com.bluetriangle.analytics.utility.getDoubleOrNull
import com.bluetriangle.analytics.utility.getIntOrNull
import com.bluetriangle.analytics.utility.getJsonArrayOrNull
import com.bluetriangle.analytics.utility.getStringOrNull
import org.json.JSONArray
import org.json.JSONObject

internal data class SessionData(
    val sessionId: String,
    val shouldSampleNetwork: Boolean,
    val isConfigApplied: Boolean,
    val networkSampleRate: Double,
    val ignoreScreens: List<String>,
    val enableScreenTracking: Boolean,
    val enableGrouping: Boolean,
    val groupingIdleTime: Int,
    val enableGroupingTapDetection: Boolean,
    val enableNetworkStateTracking: Boolean,
    val enableCrashTracking: Boolean,
    val enableANRTracking: Boolean,
    val enableMemoryWarning: Boolean,
    val enableLaunchTime: Boolean,
    val enableWebViewStitching: Boolean,
    val checkoutConfig: CheckoutConfig,
    val breadcrumbsConfig: BreadcrumbsConfig,
    val configKey: String,
    val expiration: Long,
    val enableAppInstall: Boolean,
    val enableForceRestart: Boolean,
    val forceRestartDuration: Double,
    val enableResponsiveness: Boolean
) {
    companion object {
        private const val SESSION_ID = "sessionId"
        private const val EXPIRATION = "expiration"
        private const val SHOULD_SAMPLE_NETWORK = "shouldSampleNetwork"
        private const val IS_CONFIG_APPLIED = "isConfigApplied"
        private const val NETWORK_SAMPLE_RATE = "networkSampleRate"
        private const val IGNORE_SCREENS = "ignoreScreens"
        private const val ENABLE_SCREEN_TRACKING = "enableScreenTracking"
        private const val ENABLE_GROUPING = "enableGrouping"
        private const val GROUPING_IDLE_TIME = "groupingIdleTime"
        private const val ENABLE_GROUPING_TAP_DETECTION = "enableGroupingTapDetection"
        private const val ENABLE_NETWORK_STATE_TRACKING = "enableNetworkStateTracking"
        private const val ENABLE_CRASH_TRACKING = "enableCrashTracking"
        private const val ENABLE_ANR_TRACKING = "enableANRTracking"
        private const val ENABLE_MEMORY_WARNING = "enableMemoryWarning"
        private const val ENABLE_LAUNCH_TIME = "enableLaunchTime"
        private const val ENABLE_WEB_VIEW_STITCHING = "enableWebViewStitching"
        private const val ENABLE_APP_INSTALL = "enableAppInstall"
        private const val ENABLE_FORCE_RESTART = "enableForceRestart"
        private const val FORCE_RESTART_DURATION = "forceRestartDuration"
        private const val ENABLE_RESPONSIVENESS = "enableResponsiveness"

        internal fun JSONObject.toSessionData(): SessionData? {
            try {
                return SessionData(
                    sessionId = getStringOrNull(SESSION_ID)?:return null,
                    shouldSampleNetwork = getBooleanOrNull(SHOULD_SAMPLE_NETWORK)?:false,
                    isConfigApplied = getBooleanOrNull(IS_CONFIG_APPLIED)?:false,
                    networkSampleRate = getDoubleOrNull(NETWORK_SAMPLE_RATE)?:DEFAULT_NETWORK_SAMPLE_RATE,
                    ignoreScreens = getJsonArrayOrNull(IGNORE_SCREENS)?.let { array ->
                        buildList {
                            repeat(array.length()) {
                                add(array.getString(it))
                            }
                        }
                    } ?: listOf(),
                    enableScreenTracking = getBooleanOrNull(ENABLE_SCREEN_TRACKING) != false,
                    enableGrouping = getBooleanOrNull(ENABLE_GROUPING)?:DEFAULT_ENABLE_GROUPING,
                    groupingIdleTime = getIntOrNull(GROUPING_IDLE_TIME) ?: Constants.DEFAULT_GROUPING_IDLE_TIME,
                    enableGroupingTapDetection = getBooleanOrNull(ENABLE_GROUPING_TAP_DETECTION) ?: DEFAULT_ENABLE_GROUPING_TAP_DETECTION,
                    enableNetworkStateTracking = getBooleanOrNull(ENABLE_NETWORK_STATE_TRACKING) ?: DEFAULT_ENABLE_NETWORK_STATE_TRACKING,
                    enableCrashTracking = getBooleanOrNull(ENABLE_CRASH_TRACKING) ?: DEFAULT_ENABLE_CRASH_TRACKING,
                    enableANRTracking = getBooleanOrNull(ENABLE_ANR_TRACKING) ?: DEFAULT_ENABLE_ANR_TRACKING,
                    enableMemoryWarning = getBooleanOrNull(ENABLE_MEMORY_WARNING) ?: DEFAULT_ENABLE_MEMORY_WARNING,
                    enableLaunchTime = getBooleanOrNull(ENABLE_LAUNCH_TIME) ?: DEFAULT_ENABLE_LAUNCH_TIME,
                    enableWebViewStitching = getBooleanOrNull(ENABLE_WEB_VIEW_STITCHING) ?: DEFAULT_ENABLE_WEB_VIEW_STITCHING,
                    checkoutConfig = CheckoutConfigMapper.loadFromJsonObject(this),
                    breadcrumbsConfig = BreadcrumbsConfigMapper.loadFromJsonObject(this),
                    configKey = getStringOrNull(CONFIG_KEY) ?: DEFAULT_CONFIG_KEY,
                    expiration = getLong(EXPIRATION),
                    enableAppInstall = getBooleanOrNull(ENABLE_APP_INSTALL) ?: false,
                    enableForceRestart = getBooleanOrNull(ENABLE_FORCE_RESTART) ?: false,
                    forceRestartDuration = getDoubleOrNull(FORCE_RESTART_DURATION) ?: 10.0,
                    enableResponsiveness = getBooleanOrNull(ENABLE_RESPONSIVENESS) ?: DEFAULT_ENABLE_RESPONSIVENESS
                )
            } catch (e: Exception) {
                Tracker.instance?.configuration?.logger?.error("Error while parsing session data: ${e::class.simpleName}(\"${e.message}\")")
                return null
            }
        }

        internal fun SessionData.toJsonObject() = JSONObject().apply {
            put(SESSION_ID, sessionId)
            put(SHOULD_SAMPLE_NETWORK, shouldSampleNetwork)
            put(IS_CONFIG_APPLIED, isConfigApplied)
            put(NETWORK_SAMPLE_RATE, networkSampleRate)
            put(IGNORE_SCREENS, JSONArray(ignoreScreens))
            put(ENABLE_SCREEN_TRACKING, enableScreenTracking)
            put(ENABLE_GROUPING, enableGrouping)
            put(GROUPING_IDLE_TIME, groupingIdleTime)
            put(ENABLE_GROUPING_TAP_DETECTION, enableGroupingTapDetection)
            put(ENABLE_NETWORK_STATE_TRACKING, enableNetworkStateTracking)
            put(ENABLE_CRASH_TRACKING, enableCrashTracking)
            put(ENABLE_ANR_TRACKING, enableANRTracking)
            put(ENABLE_MEMORY_WARNING, enableMemoryWarning)
            put(ENABLE_LAUNCH_TIME, enableLaunchTime)
            put(ENABLE_WEB_VIEW_STITCHING, enableWebViewStitching)
            put(CONFIG_KEY, configKey)
            CheckoutConfigMapper.loadIntoJsonObject(this, checkoutConfig)
            BreadcrumbsConfigMapper.loadIntoJsonObject(this, breadcrumbsConfig)
            put(EXPIRATION, expiration)
            put(ENABLE_APP_INSTALL, enableAppInstall)
            put(ENABLE_FORCE_RESTART, enableForceRestart)
            put(FORCE_RESTART_DURATION, forceRestartDuration)
            put(ENABLE_RESPONSIVENESS, enableResponsiveness)
        }
    }
}
