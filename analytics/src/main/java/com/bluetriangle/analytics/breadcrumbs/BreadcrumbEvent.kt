package com.bluetriangle.analytics.breadcrumbs

import com.bluetriangle.analytics.utility.JsonMappable
import com.bluetriangle.analytics.utility.JsonWritable
import org.json.JSONObject

internal sealed class BreadcrumbEvent(
    val type: String,
    val timestamp: Long,
    val data: Data
) : JsonMappable {
    interface Data : JsonWritable

    override fun toJson() = JSONObject().apply {
        put("type", type)
        put("timestamp", timestamp)
        data.writeTo(this)
    }

    companion object {
        fun fromJson(json: JSONObject): BreadcrumbEvent? {
            val type = json.getString("type")
            val timestamp = json.getLong("timestamp")
            return when (type) {
                "ui.lifecycle" -> UiLifecycle(UiLifecycle.UiLifecycleData.fromJson(json))
                "system.event" -> SystemEvent(SystemEvent.SystemEventData.fromJson(json))
                "app.lifecycle" -> AppLifecycle(
                    AppLifecycle.AppLifecycleData.fromJson(json),
                    timestamp
                )

                "network.request" -> NetworkRequest(NetworkRequest.NetworkRequestData.fromJson(json))
                "network.state" -> NetworkState(NetworkState.NetworkStateData.fromJson(json))
                "user.event" -> UserEvent(UserEvent.UserEventData.fromJson(json))
                "app.install" -> AppInstall(AppInstall.AppInstallData.fromJson(json))
                "app.update" -> AppUpdate(AppUpdate.AppUpdateData.fromJson(json))
                else -> null
            }
        }
    }

    class UiLifecycle(data: UiLifecycleData) :
        BreadcrumbEvent("ui.lifecycle", System.currentTimeMillis(), data) {
        data class UiLifecycleData(
            val className: String,
            val event: String
        ) : Data {
            override fun writeTo(jsonObject: JSONObject) {
                jsonObject.put("className", className)
                jsonObject.put("event", event)
            }

            companion object {
                fun fromJson(json: JSONObject): UiLifecycleData {
                    return UiLifecycleData(
                        className = json.getString("className"),
                        event = json.getString("event")
                    )
                }
            }
        }
    }

    class SystemEvent(data: SystemEventData) :
        BreadcrumbEvent("system.event", System.currentTimeMillis(), data) {
        data class SystemEventData(
            val eventType: String,
            val event: String
        ) : Data {
            override fun writeTo(jsonObject: JSONObject) {
                jsonObject.put("eventType", eventType)
                jsonObject.put("event", event)
            }

            companion object {
                fun fromJson(json: JSONObject): SystemEventData {
                    return SystemEventData(
                        eventType = json.getString("eventType"),
                        event = json.getString("event")
                    )
                }
            }
        }
    }

    class AppLifecycle(data: AppLifecycleData, timestamp: Long = System.currentTimeMillis()) :
        BreadcrumbEvent("app.lifecycle", timestamp, data) {
        data class AppLifecycleData(
            val event: String
        ) : Data {
            override fun writeTo(jsonObject: JSONObject) {
                jsonObject.put("event", event)
            }

            companion object {
                fun fromJson(json: JSONObject): AppLifecycleData {
                    return AppLifecycleData(
                        event = json.getString("event")
                    )
                }
            }
        }
    }

    class NetworkRequest(data: NetworkRequestData) :
        BreadcrumbEvent("network.request", System.currentTimeMillis(), data) {
        data class NetworkRequestData(
            val url: String,
            val method:String,
            val statusCode: Int
        ) : Data {
            override fun writeTo(jsonObject: JSONObject) {
                jsonObject.put("url", url)
                jsonObject.put("method", method.uppercase())
                jsonObject.put("statusCode", statusCode.toString())
            }

            companion object {
                fun fromJson(json: JSONObject): NetworkRequestData {
                    return NetworkRequestData(
                        url = json.getString("url"),
                        method = json.getString("method"),
                        statusCode = json.getString("statusCode").toInt()
                    )
                }
            }
        }
    }

    class NetworkState(data: NetworkStateData) :
        BreadcrumbEvent("network.state", System.currentTimeMillis(), data) {
        data class NetworkStateData(
            val state: String
        ) : Data {
            override fun writeTo(jsonObject: JSONObject) {
                jsonObject.put("state", state)
            }

            companion object {
                fun fromJson(json: JSONObject): NetworkStateData {
                    return NetworkStateData(
                        state = json.getString("state")
                    )
                }
            }
        }
    }

    class UserEvent(data: UserEventData) :
        BreadcrumbEvent("user.event", System.currentTimeMillis(), data) {
        data class UserEventData(
            val action: String,
            val targetClass: String?,
            val targetId: String?,
            val x: Float,
            val y: Float
        ) : Data {
            override fun writeTo(jsonObject: JSONObject) {
                jsonObject.put("action", action)
                targetClass?.let {
                    jsonObject.put("targetClass", targetClass)
                }
                targetId?.let {
                    jsonObject.put("targetId", targetId)
                }
                jsonObject.put("x", x.toString())
                jsonObject.put("y", y.toString())
            }

            companion object {
                fun fromJson(json: JSONObject): UserEventData {
                    return UserEventData(
                        action = json.getString("action"),
                        targetClass = json.optString("targetClass", null),
                        targetId = json.optString("targetId", null),
                        x = json.getString("x").toFloat(),
                        y = json.getString("y").toFloat()
                    )
                }
            }
        }
    }

    class AppInstall(data: AppInstallData) :
        BreadcrumbEvent("app.install", System.currentTimeMillis(), data) {
        data class AppInstallData(
            val version: String
        ) : Data {
            override fun writeTo(jsonObject: JSONObject) {
                jsonObject.put("version", version)
            }

            companion object {
                fun fromJson(json: JSONObject): AppInstallData {
                    return AppInstallData(
                        version = json.getString("version")
                    )
                }
            }
        }
    }

    class AppUpdate(data: AppUpdateData) :
        BreadcrumbEvent("app.update", System.currentTimeMillis(), data) {
        data class AppUpdateData(
            val from: String,
            val to: String
        ) : Data {
            override fun writeTo(jsonObject: JSONObject) {
                jsonObject.put("from", from)
                jsonObject.put("to", to)
            }

            companion object {
                fun fromJson(json: JSONObject): AppUpdateData {
                    return AppUpdateData(
                        from = json.getString("from"),
                        to = json.getString("to")
                    )
                }
            }
        }
    }

}