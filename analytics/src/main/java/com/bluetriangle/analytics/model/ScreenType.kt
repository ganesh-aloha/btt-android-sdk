package com.bluetriangle.analytics.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
sealed class ScreenType(val value: String):Parcelable {
    object Activity : ScreenType("Activity")
    object Fragment : ScreenType("Fragment")
    object Composable : ScreenType("Composable")
    object ReactNative : ScreenType("ReactNative")
    class Custom(val type: String) : ScreenType(type)
}