package com.bluetriangle.analytics.networkcapture

import com.bluetriangle.analytics.Timer
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.event.CrashSource
import com.bluetriangle.analytics.eventhub.sdkeventhub.SDKEventHub
import com.bluetriangle.analytics.eventhub.sdkeventhub.TimerEventConsumer
import org.json.JSONArray

internal class NetworkError(
    val message: String,
    val timeStamp: String,
    val breadcrumbs: JSONArray?,
    val source: CrashSource
)

/**
 * Holds network errors against the timer that was active when they occurred, and submits them
 * once that timer's page analytics beacon has been sent (same as ANR warnings).
 */
internal class NetworkErrorHolder : TimerEventConsumer {

    init {
        SDKEventHub.instance.addConsumer(this)
    }

    private val networkErrors = mutableMapOf<Long, MutableList<NetworkError>>()

    /**
     * Records the network error
     */
    fun recordNetworkError(timer: Timer, error: NetworkError) {
        synchronized(networkErrors) {
            networkErrors.getOrPut(timer.start) { mutableListOf() }.add(error)
        }
    }

    private fun submitNetworkErrors(timer: Timer) {
        val errors = synchronized(networkErrors) {
            networkErrors.remove(timer.start)
        }
        errors?.forEach {
            Tracker.instance?.submitNetworkError(timer, it)
        }
    }

    /**
     * called by SDKEventHub when any Timer is submitted
     */
    override fun onTimerSubmitted(timer: Timer) {
        submitNetworkErrors(timer)
    }

    fun stop() {
        SDKEventHub.instance.removeConsumer(this)
    }
}
