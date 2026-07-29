/*
 * Copyright (c) 2024, Blue Triangle
 * All rights reserved.
 *
 */
package com.bluetriangle.analytics.dynamicconfig.model

import com.bluetriangle.analytics.breadcrumbs.config.BreadcrumbsConfig
import com.bluetriangle.analytics.checkout.config.CheckoutConfig

internal class BTTSavedRemoteConfiguration(
    networkSampleRate: Double?,
    ignoreScreens: List<String>,
    enableAllTracking: Boolean,
    enableScreenTracking: Boolean,
    enableGrouping: Boolean,
    groupingIdleTime: Int,
    enableGroupingTapDetection: Boolean,
    enableNetworkStateTracking: Boolean,
    enableCrashTracking: Boolean,
    enableANRTracking: Boolean,
    enableMemoryWarning: Boolean,
    enableLaunchTime: Boolean,
    enableWebViewStitching: Boolean,
    checkoutConfig: CheckoutConfig,
    breadcrumbsConfig: BreadcrumbsConfig,
    configKey: String,
    val savedDate: Long,
    enableAppInstall: Boolean,
    enableForceRestart: Boolean,
    forceRestartDuration: Double,
    enableJankTracking: Boolean
) : BTTRemoteConfiguration(
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
    enableJankTracking
) {

    companion object {
        fun from(remoteConfig: BTTRemoteConfiguration) = BTTSavedRemoteConfiguration(
            remoteConfig.networkSampleRate,
            remoteConfig.ignoreScreens,
            remoteConfig.enableAllTracking,
            remoteConfig.enableScreenTracking,
            remoteConfig.enableGrouping,
            remoteConfig.groupingIdleTime,
            remoteConfig.enableGroupingTapDetection,
            remoteConfig.enableNetworkStateTracking,
            remoteConfig.enableCrashTracking,
            remoteConfig.enableANRTracking,
            remoteConfig.enableMemoryWarning,
            remoteConfig.enableLaunchTime,
            remoteConfig.enableWebViewStitching,
            remoteConfig.checkoutConfig,
            remoteConfig.breadcrumbsConfig,
            remoteConfig.configKey,
            System.currentTimeMillis(),
            remoteConfig.enableAppInstall,
            remoteConfig.enableForceRestart,
            remoteConfig.forceRestartDuration,
            remoteConfig.enableJankTracking
        )
    }

    override fun equals(other: Any?): Boolean {
        if(other is BTTSavedRemoteConfiguration) {
            return super.equals(other) && this.savedDate == other.savedDate
        }
        if(other is BTTRemoteConfiguration) {
            return super.equals(other)
        }
        return false
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + savedDate.hashCode()
        return result
    }

}
