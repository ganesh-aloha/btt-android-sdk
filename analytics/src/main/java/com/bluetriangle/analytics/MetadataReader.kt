package com.bluetriangle.analytics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.TextUtils

internal object MetadataReader {
    private const val SITE_ID = "com.blue-triangle.site-id"
    private const val DEBUG = "com.blue-triangle.debug"
    private const val DEBUG_LEVEL = "com.blue-triangle.debug.level"
    private const val PERFORMANCE_MONITOR_ENABLE = "com.blue-triangle.performance-monitor.enable"
    private const val PERFORMANCE_MONITOR_INTERVAL =
        "com.blue-triangle.performance-monitor.interval-ms"
    private const val TRACK_CRASHES_ENABLE = "com.blue-triangle.track-crashes.enable"
    private const val NETWORK_SAMPLE_RATE = "com.blue-triangle.sample-rate.network"
    private const val TRACK_ANR_ENABLE = "com.blue-triangle.track-anr.enable"
    private const val TRACK_ANR_INTERVAL_SECONDS = "com.blue-triangle.track-anr.interval-sec"
    private const val SCREEN_TRACKING_ENABLE = "com.blue-triangle.screen-tracking.enable"
    private const val LAUNCH_TIME_ENABLE = "com.blue-triangle.launch-time.enable"
    private const val MEMORY_WARNING_ENABLE = "com.blue-triangle.memory-warning.enable"
    private const val CACHE_MEMORY_LIMIT = "com.blue-triangle.cache.memory-limit"
    private const val CACHE_EXPIRY = "com.blue-triangle.cache.expiry"
    private const val TRACK_NETWORK_STATE_ENABLE = "com.blue-triangle.track-network-state.enable"
    private const val BTT_PLUGIN_VERSION = "com.blue-triangle.plugin.version"
    private const val APP_INSTALL_ENABLE = "com.blue-triangle.app-install.enable"
    private const val FORCE_RESTART_ENABLE = "com.blue-triangle.force-restart.enable"
    private const val FORCE_RESTART_DURATION_SECONDS = "com.blue-triangle.force-restart.duration-sec"

    fun applyMetadata(context: Context, configuration: BlueTriangleConfiguration) {
        try {
            val metadata = getMetadata(context)
            if (metadata != null) {
                val siteId = readString(metadata, SITE_ID, configuration.siteId)
                if (TextUtils.isEmpty(siteId)) {
                    configuration.logger?.error("No site ID")
                } else {
                    configuration.siteId = siteId
                }
                configuration.isDebug = readBool(metadata, DEBUG, configuration.isDebug)
                configuration.debugLevel = readInt(metadata, DEBUG_LEVEL, configuration.debugLevel)
                configuration.isPerformanceMonitorEnabled = readBool(
                    metadata,
                    PERFORMANCE_MONITOR_ENABLE,
                    configuration.isPerformanceMonitorEnabled
                )
                configuration.performanceMonitorIntervalMs = readLong(
                    metadata,
                    PERFORMANCE_MONITOR_INTERVAL,
                    configuration.performanceMonitorIntervalMs
                )
                configuration.isTrackCrashesEnabled =
                    readBool(metadata, TRACK_CRASHES_ENABLE, configuration.isTrackCrashesEnabled)
                configuration.networkSampleRate =
                    readDouble(metadata, NETWORK_SAMPLE_RATE, configuration.networkSampleRate)
                configuration.isTrackAnrEnabled =
                    readBool(metadata, TRACK_ANR_ENABLE, configuration.isTrackAnrEnabled)
                configuration.trackAnrIntervalSec =
                    readInt(metadata, TRACK_ANR_INTERVAL_SECONDS, configuration.trackAnrIntervalSec)
                if (configuration.trackAnrIntervalSec < 3) configuration.trackAnrIntervalSec =
                    3 // minimum interval is 3 seconds
                configuration.isScreenTrackingEnabled =
                    readBool(metadata, SCREEN_TRACKING_ENABLE, configuration.isScreenTrackingEnabled)
                configuration.isLaunchTimeEnabled =
                    readBool(metadata, LAUNCH_TIME_ENABLE, configuration.isLaunchTimeEnabled)
                configuration.isMemoryWarningEnabled =
                    readBool(metadata, MEMORY_WARNING_ENABLE, configuration.isMemoryWarningEnabled)
                configuration.cacheMemoryLimit = readLong(
                    metadata,
                    CACHE_MEMORY_LIMIT,
                    configuration.cacheMemoryLimit
                )
                configuration.cacheExpiryDuration = readLong(
                    metadata,
                    CACHE_EXPIRY,
                    configuration.cacheExpiryDuration
                )
                configuration.isTrackNetworkStateEnabled =
                    readBool(metadata, TRACK_NETWORK_STATE_ENABLE, configuration.isTrackNetworkStateEnabled)
                configuration.bttPluginVersion = readString(metadata, BTT_PLUGIN_VERSION, configuration.bttPluginVersion)
                configuration.logger?.debug("bttPluginVersion: ${configuration.bttPluginVersion}")
                configuration.isAppInstallEnabled =
                    readBool(metadata, APP_INSTALL_ENABLE, configuration.isAppInstallEnabled)
                configuration.isForceRestartEnable =
                    readBool(metadata, FORCE_RESTART_ENABLE, configuration.isForceRestartEnable)
                configuration.forceRestartDuration = readDouble(
                    metadata, FORCE_RESTART_DURATION_SECONDS, configuration.forceRestartDuration)
            }
        } catch (e: Throwable) {
            configuration.logger?.error(e, "Error reading metadata configuration")
        }
    }

    @Throws(PackageManager.NameNotFoundException::class)
    private fun getMetadata(context: Context): Bundle? {
        val app = context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        return app.metaData
    }

    private fun readBool(metadata: Bundle, key: String, defaultValue: Boolean): Boolean {
        return metadata.getBoolean(key, defaultValue)
    }

    private fun readString(metadata: Bundle, key: String, defaultValue: String?): String? {
        return metadata.getString(key, defaultValue)
    }

    private fun readInt(metadata: Bundle, key: String, defaultValue: Int): Int {
        return metadata.getInt(key, defaultValue)
    }

    private fun readDouble(metadata: Bundle, key: String, defaultValue: Double): Double {
        // manifest meta-data only reads float
        val value = metadata.getFloat(key, -1f).toDouble()
        return if (value < 0) {
            defaultValue
        } else value
    }

    private fun readLong(metadata: Bundle, key: String, defaultValue: Long): Long {
        // manifest meta-data only reads int if the value is not big enough
        return metadata.getInt(key, defaultValue.toInt()).toLong()
    }
}
