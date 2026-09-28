package com.bluetriangle.analytics

import com.bluetriangle.analytics.breadcrumbs.config.BreadcrumbsFeature

// Config Listener expose config to React Native SDK
interface IConfigListener {
    fun onConfigurationChanged(config: SdkConfiguration)
}

class SdkConfiguration(
    val networkSampleRate: Double?,
    val ignoreScreens: List<String>,
    val enableAllTracking: Boolean,
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
    val checkoutConfig: SdkCheckoutConfig,
    val breadcrumbsConfig: SdkBreadcrumbsConfig,
    val configKey: String,
    val enableAppInstall: Boolean,
    val enableForceRestart: Boolean,
    val forceRestartDuration: Double,
    val enableScreenResponsiveness: Boolean,
    val enableReportFatalAnr: Boolean
){
    override fun toString(): String {
        return "SdkConfiguration { networkSampleRate: $networkSampleRate, ignoreList: ${ignoreScreens}, enableAllTracking: $enableAllTracking,  enableScreenTracking: $enableScreenTracking, enableGrouping: $enableGrouping, groupingIdleTime: $groupingIdleTime, enableGroupingTapDetection: $enableGroupingTapDetection, enableNetworkStateTracking: $enableNetworkStateTracking, enableCrashTracking: $enableCrashTracking, enableANRTracking: $enableANRTracking, enableMemoryWarning: $enableMemoryWarning, enableLaunchTime: $enableLaunchTime, enableWebViewStitching: $enableWebViewStitching, checkoutConfig: $checkoutConfig, breadcrumbsConfig: $breadcrumbsConfig, configKey: $configKey, enableAppInstall; $enableAppInstall, enableForceRestart: $enableForceRestart, enableScreenResponsiveness: $enableScreenResponsiveness, enableReportFatalAnr: $enableReportFatalAnr }"
    }
}

class SdkCheckoutConfig(
    val isEnabled: Boolean,
    val classNames: List<String>,
    val networkUrlPattern: String?,
    val checkoutAmount: Double,
    val cartCount: Int,
    val cartCountCheckout: Int,
    val orderNumber: String?,
    val timerValue: Int
){
    override fun toString(): String {
        return """SdkCheckoutConfig(
            |isEnabled: $isEnabled
            |classNames: "${classNames.joinToString()}"
            |networkUrlPattern: "$networkUrlPattern"
            |checkoutAmount: $checkoutAmount
            |cartCount: $cartCount
            |cartCountCheckout: $cartCountCheckout
            |orderNumber: "$orderNumber"
            |timerValue: $timerValue
            |)
        """.trimMargin()
    }
}

class SdkBreadcrumbsConfig(
    val isEnabled: Boolean,
    val capacity: Int,
    val ignoredFeatures: List<BreadcrumbsFeature>
){
    override fun toString(): String {
        return """SdkBreadcrumbsConfig(
            |isEnabled: ${isEnabled},
            |capacity: ${capacity},
            |ignoredFeatures: ${ignoredFeatures.joinToString()}
            |)
        """.trimMargin()
    }
}

