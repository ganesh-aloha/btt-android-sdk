package com.bluetriangle.analytics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.UnsupportedEncodingException
import java.security.SecureRandom
import java.util.*

/**
 * Utility methods
 */
internal object Utils {
    /**
     * Return the string resource for the given key or null if not found.
     *
     * @param context application context
     * @param key     name of the identifier to look up
     * @return string resource for the given key or null if not found.
     */
    fun getResourceString(context: Context, key: String): String? {
        val id = getIdentifier(context, "string", key)
        return if (id != 0) {
            context.resources.getString(id)
        } else {
            null
        }
    }

    /**
     * Get the identifier for the resource with a given type and key.
     *
     * @param context application context
     * @param type    resource type, ex: string
     * @param key     name of the identifier to look up
     * @return resource identifier for requested type and key or 0 if not found
     */
    private fun getIdentifier(context: Context, type: String, key: String): Int {
        return context.resources.getIdentifier(key, type, context.packageName)
    }

    /**
     * Determine if this application is debuggable
     *
     * @param context application context
     * @return true if application flag for debugging is set, else false
     */
    fun isDebuggable(context: Context): Boolean {
        try {
            val packageName = context.packageName
            val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
            return flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        } catch (ignored: PackageManager.NameNotFoundException) {
        }
        return false
    }

    /**
     * Returns a randomly generated ID that is 18 characters long
     *
     * @return random long
     */
    fun generateRandomId(): String {
        val random = Math.abs(Random().nextLong())
        return String.format(Locale.ENGLISH, "%019d", random).substring(0, 19)
    }

    /**
     * Get the applications package info
     *
     * @param context application context
     * @return application's package info
     */
    private fun getAppPackageInfo(context: Context): PackageInfo? {
        try {
            return context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_CONFIGURATIONS)
        } catch (ignore: PackageManager.NameNotFoundException) {
            // this should never happen, we are looking up ourself
        }
        return null
    }

    /**
     * Get the application's version
     *
     * @param context application context
     * @return The application's version or UNKNOWN if not found
     */
    fun getAppVersion(context: Context): String {
        val packageInfo = getAppPackageInfo(context)
        return if (packageInfo != null) {
            packageInfo.versionName
        } else "UNKNOWN"
    }

    val os: String
        get() = "${Constants.OS} ${Build.VERSION.RELEASE}"

    fun isTablet(context: Context): Boolean {
        return context.resources.getBoolean(R.bool.isTablet)
    }

    fun getAppName(context: Context): String {
        return try {
            val applicationInfo = context.applicationInfo
            val appNameStringResourceId = applicationInfo.labelRes
            if (appNameStringResourceId == 0) applicationInfo.nonLocalizedLabel.toString() else context.getString(
                appNameStringResourceId
            )
        } catch (e: Exception) {
            ""
        }
    }

    fun getAppNameAndOs(context: Context): String {
        return "${getAppName(context)} $os"
    }

    /**
     * Builds a user agent string
     * @param context application context
     * @return User-Agent: My-App/1.0 iOS/15.5 (iPhone14,2) btt-swift-sdk/3.1.0
     */
    fun buildUserAgent(context: Context): String {
        val appName = getAppName(context)
        val appVersion = getAppVersion(context)
        return "$appName/$appVersion Android/${Build.VERSION.RELEASE} ($deviceName) ${Tracker.sdkId}/${Tracker.sdkVersion}"
    }

    val deviceName: String
        get() = if (Build.MODEL.startsWith(Build.MANUFACTURER)) {
            capitalize(Build.MODEL)
        } else capitalize(Build.MANUFACTURER) + " " + Build.MODEL

    fun capitalize(str: String?): String {
        return str?.replaceFirstChar { it.uppercaseChar() } ?: ""
    }

    /**
     * Build the base 64 encoded data to POST to the API
     *
     * @return base 64 encoded JSON payload
     * @throws UnsupportedEncodingException if UTF-8 encoding is not supported
     */
    @Throws(UnsupportedEncodingException::class)
    fun b64encode(data: String): ByteArray {
        return Base64.encode(data.toByteArray(), Base64.DEFAULT)
    }

    fun exceptionToStacktrace(message: String?, e: Throwable, hideExceptionName: Boolean = false): ExceptionInfo {
        val lines = e.stackTraceToString().lines()

        val appFrame = Tracker.instance?.getStackTracePackageFrame(lines)

        val title = listOfNotNull(
            message?.takeIf(String::isNotBlank)
                ?: if (hideExceptionName) e.message else lines.first(),
            appFrame
        ).joinToString("~~")

        val stacktraceLines = if (hideExceptionName) {
            lines.drop(1)
        } else {
            lines
        }

        val prefix = when {
            !message.isNullOrBlank() -> "$message~~"
            hideExceptionName && !e.message.isNullOrBlank() -> "${e.message}~~"
            else -> ""
        }

        val stacktrace = prefix + stacktraceLines
            .filter(String::isNotBlank)
            .joinToString("~~")

        return ExceptionInfo(title, stacktrace)
    }

    /**
     * determine if samples should be captured
     * @param sampleRate the rate at which samples should captured
     * @return true if this instance should sample, false otherwise
     */
    fun shouldSample(sampleRate: Double): Boolean {
        val random = SecureRandom()
        return sampleRate >= random.nextDouble()
    }
}

internal data class ExceptionInfo(val title: String?, val stackTrace: String)

/**
 * Extension to JSONObject to add a method to convert a JSON object to a Map<String, String>
 */
fun JSONObject.toMap(): Map<String, Any> {
    return keys().asSequence().associateWith { key ->
        kotlin.runCatching {
            when (val value = this[key]) {
                is JSONArray -> (0 until value.length()).map { value[it] }
                is JSONObject -> value.toMap()
                JSONObject.NULL -> null
                else -> value
            }
        }.getOrNull()
    }.filterValues { it != null }.mapValues { it.value as Any }
}
