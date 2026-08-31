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
    object Crash: BTTEvent(7, "NativeAppCrash") // 6 is for iOS crash
    object AppInstall: BTTEvent(8, "AppInstall")
    object ForceRestart: BTTEvent(9, "ForceRestart")
    object FatalANR: BTTEvent(10, "FatalANR")
}