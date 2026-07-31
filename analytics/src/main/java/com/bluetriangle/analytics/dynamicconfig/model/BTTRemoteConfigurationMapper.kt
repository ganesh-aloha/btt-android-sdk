/*
 * Copyright (c) 2024, Blue Triangle
 * All rights reserved.
 *
 */
package com.bluetriangle.analytics.dynamicconfig.model

import com.bluetriangle.analytics.Constants.DEFAULT_CONFIG_KEY
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_ANR_TRACKING
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_APP_INSTALL
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_CRASH_TRACKING
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_FORCE_RESTART
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_GROUPING
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_RESPONSIVENESS
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_LAUNCH_TIME
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_MEMORY_WARNING
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_NETWORK_STATE_TRACKING
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_WEB_VIEW_STITCHING
import com.bluetriangle.analytics.Constants.DEFAULT_FORCE_RESTART_DURATION
import com.bluetriangle.analytics.Constants.DEFAULT_GROUPING_IDLE_TIME
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_GROUPING_TAP_DETECTION
import com.bluetriangle.analytics.breadcrumbs.config.BreadcrumbsConfigMapper
import com.bluetriangle.analytics.checkout.config.CheckoutConfigMapper
import com.bluetriangle.analytics.utility.getBooleanOrNull
import com.bluetriangle.analytics.utility.getDoubleOrNull
import com.bluetriangle.analytics.utility.getIntOrNull
import com.bluetriangle.analytics.utility.getJsonArrayOrNull
import com.bluetriangle.analytics.utility.getStringOrNull
import org.json.JSONObject

internal object BTTRemoteConfigurationMapper {

    private const val NETWORK_SAMPLE_RATE = "networkSampleRateSDK"
    private const val ENABLE_REMOTE_CONFIG = "enableRemoteConfigAck"
    private const val IGNORE_SCREENS = "ignoreScreens"
    private const val ENABLE_ALL_TRACKING = "enableAllTracking"
    private const val ENABLE_GROUPING = "enableGrouping"
    private const val GROUPING_IDLE_TIME = "groupingIdleTime"
    private const val ENABLE_SCREEN_TRACKING = "enableScreenTracking"
    private const val ENABLE_GROUPING_TAP_DETECTION = "enableGroupingTapDetection"
    private const val ENABLE_NETWORK_STATE_TRACKING = "enableNetworkStateTracking"
    private const val ENABLE_CRASH_TRACKING = "enableCrashTracking"
    private const val ENABLE_ANR_TRACKING = "enableANRTracking"
    private const val ENABLE_MEMORY_WARNING = "enableMemoryWarning"
    private const val ENABLE_LAUNCH_TIME = "enableLaunchTime"
    private const val ENABLE_WEB_VIEW_STITCHING = "enableWebViewStitching"
    private const val CONFIG_KEY = "configKey"
    private const val ENABLE_APP_INSTALL = "enableAppInstall"
    private const val ENABLE_FORCE_RESTART = "enableForceRestart"
    private const val FORCE_RESTART_DURATION = "forceRestartDuration"
    private const val ENABLE_RESPONSIVENESS = "enableResponsiveness"

    fun fromJson(remoteConfigJson: JSONObject): BTTRemoteConfiguration {
        val networkSampleRate = remoteConfigJson.getDoubleOrNull(NETWORK_SAMPLE_RATE)?.div(100.0)
        val enableRemoteConfig = remoteConfigJson.getBooleanOrNull(ENABLE_REMOTE_CONFIG) == true
        val ignoreScreens = remoteConfigJson.getJsonArrayOrNull(IGNORE_SCREENS)?.let { array ->
            buildList {
                for (i in 0 until array.length()) {
                    array.optString(i, null)?.also { screen ->
                        add(screen.trim())
                    }
                }
            }
        } ?: listOf()
        val enableAllTracking = remoteConfigJson.getBooleanOrNull(ENABLE_ALL_TRACKING) != false
        val enableScreenTracking = remoteConfigJson.getBooleanOrNull(ENABLE_SCREEN_TRACKING) != false
        val enableGrouping = remoteConfigJson.getBooleanOrNull(ENABLE_GROUPING) ?: DEFAULT_ENABLE_GROUPING
        val groupingIdleTime = remoteConfigJson.getIntOrNull(GROUPING_IDLE_TIME) ?: DEFAULT_GROUPING_IDLE_TIME
        val enableGroupingTapDetection = remoteConfigJson.getBooleanOrNull(ENABLE_GROUPING_TAP_DETECTION) ?: DEFAULT_ENABLE_GROUPING_TAP_DETECTION
        val enableNetworkStateTracking = remoteConfigJson.getBooleanOrNull(ENABLE_NETWORK_STATE_TRACKING) ?: DEFAULT_ENABLE_NETWORK_STATE_TRACKING
        val enableCrashTracking = remoteConfigJson.getBooleanOrNull(ENABLE_CRASH_TRACKING) ?: DEFAULT_ENABLE_CRASH_TRACKING
        val enableANRTracking = remoteConfigJson.getBooleanOrNull(ENABLE_ANR_TRACKING) ?: DEFAULT_ENABLE_ANR_TRACKING
        val enableMemoryWarning = remoteConfigJson.getBooleanOrNull(ENABLE_MEMORY_WARNING) ?: DEFAULT_ENABLE_MEMORY_WARNING
        val enableLaunchTime = remoteConfigJson.getBooleanOrNull(ENABLE_LAUNCH_TIME) ?: DEFAULT_ENABLE_LAUNCH_TIME
        val enableWebViewStitching = remoteConfigJson.getBooleanOrNull(ENABLE_WEB_VIEW_STITCHING) ?: DEFAULT_ENABLE_WEB_VIEW_STITCHING
        val checkoutConfig = CheckoutConfigMapper.loadFromJsonObject(remoteConfigJson)
        val breadcrumbsConfig = BreadcrumbsConfigMapper.loadFromJsonObject(remoteConfigJson)
        val configKey = remoteConfigJson.getStringOrNull(CONFIG_KEY) ?: DEFAULT_CONFIG_KEY
        val enableAppInstall =
            remoteConfigJson.getBooleanOrNull(ENABLE_APP_INSTALL) ?: DEFAULT_ENABLE_APP_INSTALL
        val enableForceRestart =
            remoteConfigJson.getBooleanOrNull(ENABLE_FORCE_RESTART) ?: DEFAULT_ENABLE_FORCE_RESTART
        val forceRestartDuration =
            remoteConfigJson.getDoubleOrNull(FORCE_RESTART_DURATION) ?: DEFAULT_FORCE_RESTART_DURATION
        val enableResponsiveness =
            remoteConfigJson.getBooleanOrNull(ENABLE_RESPONSIVENESS) ?: DEFAULT_ENABLE_RESPONSIVENESS
        return BTTRemoteConfiguration(
            networkSampleRate,
            ignoreScreens,
            enableAllTracking,
            enableScreenTracking,
            enableGrouping,
            groupingIdleTime,
            enableGroupingTapDetection,
            enableNetworkStateTracking,
            enableCrashTracking,
            enableANRTracking,
            enableMemoryWarning,
            enableLaunchTime,
            enableWebViewStitching,
            checkoutConfig,
            breadcrumbsConfig,
            configKey,
            enableAppInstall,
            enableForceRestart,
            forceRestartDuration,
            enableResponsiveness
        )
    }
}
