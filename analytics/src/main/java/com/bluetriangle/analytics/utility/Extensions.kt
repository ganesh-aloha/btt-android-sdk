package com.bluetriangle.analytics.utility

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.model.Screen
import com.bluetriangle.analytics.model.ScreenType
import com.bluetriangle.analytics.networkstate.BTTNetworkState
import java.io.File
import kotlin.math.round

fun logD(tag: String? = null, message: String) {
    val prefix = tag?.plus(": ").orEmpty()
    Tracker.instance?.configuration?.logger?.debug("$prefix$message")
}

fun logV(tag: String? = null, message: String) {
    val prefix = tag?.plus(": ").orEmpty()
    Tracker.instance?.configuration?.logger?.verbose("$prefix$message")
}

internal val Fragment.screen: Screen
    get() = Screen(
        hashCode().toString(),
        this::class.java.simpleName,
        ScreenType.Fragment
    )

internal fun Activity.getToolbarTitle(): String? {
    return if (this is AppCompatActivity) {
        supportActionBar?.title?.toString() ?: title?.toString()
    } else {
        actionBar?.title?.toString() ?: title?.toString()
    }
}

internal val Activity.screen: Screen
    get() = Screen(
        hashCode().toString(),
        this::class.java.simpleName,
        ScreenType.Activity
    )

fun FragmentActivity.registerFragmentLifecycleCallback(callback: FragmentLifecycleCallbacks) {
    supportFragmentManager.registerFragmentLifecycleCallbacks(callback, true)
}

fun FragmentActivity.unregisterFragmentLifecycleCallback(callback: FragmentLifecycleCallbacks) {
    supportFragmentManager.unregisterFragmentLifecycleCallbacks(callback)
}

val Long.mb: Long
    get() = this / 1024 * 1024

fun <V> any(vararg operations: () -> V?): V? {
    operations.forEach {
        val value = it()
        if (value != null) {
            return@any value
        }
    }
    return null
}

val File.isDirectoryInvalid: Boolean
    get() = !isDirectory || !canRead() || !canWrite()

val wifiTransports = intArrayOf(
    NetworkCapabilities.TRANSPORT_WIFI,
    NetworkCapabilities.TRANSPORT_BLUETOOTH,
    NetworkCapabilities.TRANSPORT_VPN
)

val cellularTransports = intArrayOf(NetworkCapabilities.TRANSPORT_CELLULAR)

val ethernetTransports = intArrayOf(NetworkCapabilities.TRANSPORT_ETHERNET)


internal val BTTNetworkState.value: String
    get() {
        return when (this) {
            BTTNetworkState.Wifi -> "wifi"
            is BTTNetworkState.Cellular -> "cellular ${protocol.description}".trim()
            BTTNetworkState.Ethernet -> "ethernet"
            BTTNetworkState.Offline -> "offline"
            else -> ""
        }
    }

fun getNumberOfCPUCores() = Os.sysconf(OsConstants._SC_NPROCESSORS_CONF)

internal val Context.isDebugBuild: Boolean
    get() {
        return try {
            val appInfo = applicationInfo
            (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

internal inline fun Boolean.onFalse(block: () -> Unit): Boolean {
    if(!this) {
        block()
    }
    return this
}

internal fun postDelayedMain(runnable: ()->Unit, delayInMillis: Long) {
    Handler(Looper.getMainLooper()).postDelayed(runnable, delayInMillis)
}

internal fun Double.roundTo2Decimals(): Double = round(this * 100) / 100

internal fun Double.roundTo4Decimals(): Double = round(this * 10_000) / 10_000
