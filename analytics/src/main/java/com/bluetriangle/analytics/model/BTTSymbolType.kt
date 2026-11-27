package com.bluetriangle.analytics.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
sealed class BTTSymbolType(val value: String) : Parcelable {
    object IosNative : BTTSymbolType("ios-native")
    object AndroidNative : BTTSymbolType("android-native")
    object AndroidNdk : BTTSymbolType("android-ndk")
    object ReactNativeJs : BTTSymbolType("react-native-js")
    class Other(val type: String) : BTTSymbolType(type)
}