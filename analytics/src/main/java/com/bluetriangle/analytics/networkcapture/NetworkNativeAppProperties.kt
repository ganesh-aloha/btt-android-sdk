package com.bluetriangle.analytics.networkcapture

import android.os.Parcelable
import com.bluetriangle.analytics.Constants.SDK_VERSION
import com.bluetriangle.analytics.Constants.APP_VERSION
import com.bluetriangle.analytics.Constants.SDK_ID
import com.bluetriangle.analytics.Timer.Companion.FIELD_NET_STATE_SOURCE
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.deviceinfo.DeviceInfo
import kotlinx.parcelize.Parcelize
import org.json.JSONObject

@Parcelize
data class NetworkNativeAppProperties(
    var err: String?,
    var netState: String? = null,
    var deviceModel: String? = null,
    var netStateSource: String? = null
) : Parcelable {

    val appVersion: String? = Tracker.instance?.appVersion
    val sdkVersion: String = Tracker.sdkVersion
    val sdkId: String = Tracker.sdkId

    fun toJSONObject(): JSONObject {
        val obj = JSONObject()
        obj.put("err", err)
        obj.put(CapturedRequest.FIELD_NETWORK_STATE, netState)
        obj.put(CapturedRequest.FIELD_DEVICE_MODEL, deviceModel)
        if(!netStateSource.isNullOrEmpty()) {
            obj.put(FIELD_NET_STATE_SOURCE, netStateSource)
        }
        obj.put(APP_VERSION, appVersion)
        obj.put(SDK_VERSION, sdkVersion)
        obj.put(SDK_ID, sdkId)
        return obj
    }

    fun add(deviceInfo: DeviceInfo) {
        deviceModel = deviceInfo.deviceModel
    }
}
