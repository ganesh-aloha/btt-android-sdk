package com.bluetriangle.analytics.event

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
sealed class BTTEvent(val id: Int, val defaultPageName: String): Parcelable {
    object ColdLaunch: BTTEvent(1, "ColdLaunchTime")
    object WarmLaunch: BTTEvent(2, "WarmLaunchTime")
    object HotLaunch: BTTEvent(3, "HotLaunchTime")
    object ANRWarning: BTTEvent(4, "ANRWarning")
    object MemoryWarning: BTTEvent(5, "MemoryWarning")
    object Crash: BTTEvent(6, "NativeAppCrash") // 6 is for iOS crash // After Release 2.20.0 Made iOS and Android eventId same to 6
    object AppInstall: BTTEvent(8, "AppInstall")
    object ForceRestart: BTTEvent(15, "ForceRestart")
    object FatalANR: BTTEvent(19, "FatalANR") // 16, 17, 18 reserved for iOS metric kit errors
    object ExcessResourceUsage: BTTEvent(20, "ExcessResourceUsage")
    object NetworkError: BTTEvent(22, "NetworkError")
}

sealed class CrashSource(val name: String) {
    object GlobalExceptionHandler : CrashSource("GlobalExceptionHandler")
    object MainThreadWatcher : CrashSource("MainThreadWatcher")
    object AppExitInformation : CrashSource("AppExitInformation")
    object ReactJS : CrashSource("ReactJS")
    object OkHttp : CrashSource("OkHttp") //  Network Error
    object ReactFetch : CrashSource("ReactFetch") // Network Error
}