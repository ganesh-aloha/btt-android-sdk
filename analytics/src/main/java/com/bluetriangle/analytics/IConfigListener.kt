package com.bluetriangle.analytics

import com.bluetriangle.analytics.breadcrumbs.config.BreadcrumbsFeature

// Config Listener expose config to React Native SDK
interface IConfigListener {
    fun onConfigurationChanged(config: SdkConfiguration)
}

class SdkConfiguration(
    val enableAllTracking: Boolean,
    val ignoreScreens: List<String>,
    val enableScreenTracking: Boolean,
    val networkSampleRateSDK: Double,
    val enableGrouping: Boolean,
    val groupingIdleTime: Int,
    val enableCrashTracking: Boolean,
    val enableANRTracking: Boolean,
    val enableMemoryWarning: Boolean,
    val enableLaunchTime: Boolean,
    val enableWebViewStitching: Boolean,
    val enableNetworkStateTracking: Boolean,
    val enableGroupingTapDetection: Boolean,
    val configKey: String,
    val checkoutTrackingEnabled: Boolean,
    val checkoutClassName: List<String>,
    val checkoutURL: String,
    val checkoutAmount: Double,
    val checkoutCartCount: Int,
    val checkoutCartCountCheckout: Int,
    val checkoutOrderNumber: String,
    val checkoutTimeValue: Int,
    val ignoreBreadcrumbs: List<String>,
    val enableBreadcrumbs: Boolean,
    val enableAppInstall: Boolean,
    val enableForceRestart: Boolean,
    val forceRestartDuration: Double,
    val enableScreenResponsiveness: Boolean,
    val fatalErrorSampleRate: Double?,
    val nonFatalErrorSampleRate: Double?,
    val shouldNetworkCapture: Boolean,
    val shouldReportFatalErrors: Boolean,
    val shouldReportNonFatalErrors: Boolean
) {
    override fun toString(): String {
        return "SdkConfiguration { enableAllTracking: $enableAllTracking, ignoreScreens: $ignoreScreens, enableScreenTracking: $enableScreenTracking, networkSampleRateSDK: $networkSampleRateSDK, enableGrouping: $enableGrouping, groupingIdleTime: $groupingIdleTime, enableCrashTracking: $enableCrashTracking, enableANRTracking: $enableANRTracking, enableMemoryWarning: $enableMemoryWarning, enableLaunchTime: $enableLaunchTime, enableWebViewStitching: $enableWebViewStitching, enableNetworkStateTracking: $enableNetworkStateTracking, enableGroupingTapDetection: $enableGroupingTapDetection, configKey: $configKey, checkoutTrackingEnabled: $checkoutTrackingEnabled, checkoutClassName: $checkoutClassName, checkoutURL: $checkoutURL, checkoutAmount: $checkoutAmount, checkoutCartCount: $checkoutCartCount, checkoutCartCountCheckout: $checkoutCartCountCheckout, checkoutOrderNumber: $checkoutOrderNumber, checkoutTimeValue: $checkoutTimeValue, ignoreBreadcrumbs: $ignoreBreadcrumbs, enableBreadcrumbs: $enableBreadcrumbs, enableAppInstall: $enableAppInstall, enableForceRestart: $enableForceRestart, forceRestartDuration: $forceRestartDuration, enableScreenResponsiveness: $enableScreenResponsiveness, fatalErrorSampleRate: $fatalErrorSampleRate, nonFatalErrorSampleRate: $nonFatalErrorSampleRate, shouldNetworkCapture: $shouldNetworkCapture, shouldReportFatalErrors: $shouldReportFatalErrors, shouldReportNonFatalErrors: $shouldReportNonFatalErrors }"
    }
}
