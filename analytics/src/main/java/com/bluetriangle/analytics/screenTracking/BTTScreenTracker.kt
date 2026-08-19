package com.bluetriangle.analytics.screenTracking

import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.model.Screen
import com.bluetriangle.analytics.model.ScreenType

class BTTScreenTracker(private val pageName: String, private val screenType: ScreenType) {

    private val id = "${pageName}#${System.currentTimeMillis()}"
    private var isConsumed = false

    fun onLoadStarted() {
        if(isConsumed) {
            logConsumedError()
            return
        }
        Tracker.instance?.screenTrackMonitor?.onLoadStarted(Screen(
            id,
            pageName,
            screenType
        ))
    }

    fun onLoadEnded() {
        if(isConsumed) {
            logConsumedError()
            return
        }
        Tracker.instance?.screenTrackMonitor?.onLoadEnded(Screen(
            id,
            pageName,
            screenType
        ))
    }

    fun onViewStarted() {
        if(isConsumed) {
            logConsumedError()
            return
        }
        Tracker.instance?.screenTrackMonitor?.onViewStarted(Screen(
            id,
            pageName,
            screenType
        ))
    }

    fun onViewEnded() {
        if(isConsumed) {
            logConsumedError()
            return
        }
        isConsumed = true
        Tracker.instance?.screenTrackMonitor?.onViewEnded(Screen(
            id,
            pageName,
            screenType
        ))
    }

    private fun logConsumedError() {
        Tracker.instance?.configuration?.logger?.error("This object is supposed to be used only once for the lifecycle of a view")
    }

}
