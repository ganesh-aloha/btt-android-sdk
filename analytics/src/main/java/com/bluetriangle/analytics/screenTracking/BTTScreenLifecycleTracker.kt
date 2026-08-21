package com.bluetriangle.analytics.screenTracking

import com.bluetriangle.analytics.Constants.TIMER_MIN_PGTM
import com.bluetriangle.analytics.Timer
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.checkout.event.CheckoutEvent
import com.bluetriangle.analytics.model.Screen
import com.bluetriangle.analytics.screenTracking.grouping.BTTTimerGroupManager
import com.bluetriangle.analytics.model.ScreenType
import com.bluetriangle.analytics.utility.logD

internal class BTTScreenLifecycleTracker(
    private val screenTrackingEnabled: Boolean,
    var groupingEnabled: Boolean,
    idleTime: Int,
    internal var ignoreScreens: List<String>
) : ScreenLifecycleTracker {

    private var loadStartTime = hashMapOf<String, Long>()
    private var viewStartTime = hashMapOf<String, Long>()
    private val timers = hashMapOf<String, Timer>()
    private val TAG = this::class.java.simpleName

    var groupIdleTime = idleTime
        set(value) {
            field = value
            groupManager.groupIdleTime = value
        }

    private val groupManager = BTTTimerGroupManager(groupIdleTime)

    companion object {
        const val AUTOMATED_TIMERS_PAGE_TYPE = "ScreenTracker"
        const val AUTOMATED_TIMERS_TRAFFIC_SEGMENT = "ScreenTracker"
    }

    fun grouped(automated: Boolean): Boolean = groupingEnabled && automated

    override fun destroy() {
        groupManager.destroy()
    }

    override fun onLoadStarted(screen: Screen, automated: Boolean) {
        if (!screenTrackingEnabled) return
        if (shouldIgnore(screen.pageName(grouped(automated)))) return

        logD(TAG, "onLoadStarted: $screen")
        createTimerAndCaptureLoadStartTime(screen, grouped(automated))
        Tracker.instance?.checkoutEventReporter?.onCheckoutEvent(CheckoutEvent.ClassEvent(screen.name))
    }

    override fun onLoadEnded(screen: Screen, automated: Boolean) {
        if (!screenTrackingEnabled) return
        if (shouldIgnore(screen.pageName(grouped(automated)))) return

        timers[screen.toString()]?.setPageName(screen.pageName(grouped(automated)))
        logD(TAG, "onLoadEnded: $screen")
        if (timers[screen.toString()] == null) {
            createTimerAndCaptureLoadStartTime(screen, grouped(automated))
        }
    }

    override fun onViewStarted(screen: Screen, automated: Boolean) {
        if (!screenTrackingEnabled) return
        if (shouldIgnore(screen.pageName(grouped(automated)))) return

        if (timers[screen.toString()] == null) {
            createTimerAndCaptureLoadStartTime(screen, grouped(automated))
        }
        logD(TAG, "onViewStarted: $screen")
        viewStartTime[screen.toString()] = System.currentTimeMillis()

        if(grouped(automated)) {
            // the title isn't available instantly. Hence, it's not fetched in onViewStarted instantly.
            // Once the title is stabilized then the title property in the screen would be updated and this callback will be called.
            screen.onTitleUpdated = {
                timers[screen.toString()]?.setPageName(screen.pageName(grouped(automated)))
            }
        }

        // Host Activities are isContent=false so frames go to Fragments/Compose when present;
        // Activity-only screens still receive frames when no content screens are visible.
        Tracker.instance?.jankFrameMonitor?.onScreenVisible(
            screen.toString(),
            isContent = screen.type != ScreenType.Activity
        )
    }

    @Synchronized
    private fun createTimerAndCaptureLoadStartTime(screen: Screen, isGrouped: Boolean) {
        val timer = Timer(screen.pageName(isGrouped), null)
        timers[screen.toString()] = timer
        if(isGrouped) {
            groupManager.add(screen, timer)
            timer.startSilent()
        } else {
            timer.start()
        }
        loadStartTime[screen.toString()] = System.currentTimeMillis()
    }

    override fun onViewEnded(screen: Screen, automated: Boolean) {
        if (!screenTrackingEnabled) return
        if (shouldIgnore(screen.pageName(grouped(automated)))) return

        logD(TAG, "onViewEnded: $screen")
        val scr = screen.toString()
        val timer = timers[scr] ?: return

        Tracker.instance?.jankFrameMonitor?.onScreenHidden(scr)?.let { metrics ->
            timer.setJankReportFields(metrics)
        }

        generateMetaData(screen, timer)

        if(grouped(automated)) {
            timer.end()
        } else {
            timer.end().submit()
        }

        Tracker.instance?.onScreenPause(timer)

        timers.remove(scr)
    }

    override fun generateMetaData(screen: Screen, timer: Timer) {
        val scr  = screen.toString()
        val loadStartTm = loadStartTime[scr] ?: 0L
        val viewStartTm = viewStartTime[scr] ?: 0L

        var confidenceRate = 100
        var confidenceMsg: String? = null

        var pgTm = viewStartTm - loadStartTm

        if(loadStartTm == 0L) {
            confidenceRate = 0
            confidenceMsg = when(screen.type) {
                ScreenType.Activity, ScreenType.Fragment -> "onCreate/onStart not called on ${screen.name}"
                ScreenType.Composable -> "Composable load time could not be calculated"
                ScreenType.ReactNative -> "React Native load time could not be calculated"
                is ScreenType.Custom -> "onLoadStarted/onLoadEnded methods were not called"
            }
        } else if(viewStartTm == 0L) {
            confidenceRate = 0
            confidenceMsg = when(screen.type) {
                ScreenType.Activity, ScreenType.Fragment -> "onResume not called on ${screen.name}"
                ScreenType.Composable -> "Composable load time could not be calculated"
                ScreenType.ReactNative -> "React Native load time could not be calculated"
                is ScreenType.Custom -> "onViewStarted method was not called"
            }
        } else if(pgTm > 20_000) {
            pgTm = 20_000
            confidenceRate = 50
            confidenceMsg = "load time calculation gone out of bounds"
        }
        val disappearTm = System.currentTimeMillis()

        val tracker = Tracker.instance
        if(tracker == null || !tracker.isGlobalFieldSet(Timer.FIELD_CONTENT_GROUP_NAME)) {
            timer.setContentGroupName(AUTOMATED_TIMERS_PAGE_TYPE)
        }
        if(tracker == null || !tracker.isGlobalFieldSet(Timer.FIELD_TRAFFIC_SEGMENT_NAME)) {
            timer.setTrafficSegmentName(AUTOMATED_TIMERS_TRAFFIC_SEGMENT)
        }

        if (timer.getField(Timer.FIELD_TRAFFIC_SEGMENT_NAME) == null || timer.getField(Timer.FIELD_TRAFFIC_SEGMENT_NAME) == "null") {
            timer.setTrafficSegmentName(AUTOMATED_TIMERS_TRAFFIC_SEGMENT)
        }

        timer.pageTimeCalculator = {
            (pgTm).coerceAtLeast(TIMER_MIN_PGTM)
        }

        timer.generateNativeAppProperties()

        timer.nativeAppProperties.loadStartTime = loadStartTm
        timer.nativeAppProperties.loadEndTime = viewStartTm
        timer.nativeAppProperties.disappearTime = disappearTm
        timer.nativeAppProperties.className = screen.name

        timer.nativeAppProperties.loadTime = viewStartTm - loadStartTm
        timer.nativeAppProperties.loadTime = pgTm
        timer.nativeAppProperties.fullTime = disappearTm - loadStartTm
        timer.nativeAppProperties.screenType = screen.type
        timer.nativeAppProperties.confidenceRate = confidenceRate
        timer.nativeAppProperties.confidenceMsg = confidenceMsg
    }

    private fun shouldIgnore(name: String): Boolean {
        return ignoreScreens.contains(name)
    }

    override fun setGroupName(groupName: String) {
        if(!groupingEnabled) return

        groupManager.setGroupName(groupName)
    }

    override fun setNewGroup(groupName: String) {
        if(!groupingEnabled) return

        groupManager.setNewGroup(groupName)
    }

}
