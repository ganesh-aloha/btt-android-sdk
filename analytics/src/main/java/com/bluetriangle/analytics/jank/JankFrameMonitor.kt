package com.bluetriangle.analytics.jank

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Window
import androidx.metrics.performance.JankStats
import com.bluetriangle.analytics.BlueTriangleConfiguration
import com.bluetriangle.analytics.Constants
import java.util.Collections
import java.util.WeakHashMap

/**
 * Tracks per-frame health (jank/hitch/hang buckets - see [JankMetrics]) across all of the app's
 * activities by observing frame durations via AndroidX's [JankStats] library, which uses the
 * platform FrameMetrics API on API 24+ and an [android.view.ViewTreeObserver.OnPreDrawListener]
 * fallback below that - see [Constants.JANK_HEURISTIC_MULTIPLIER] for why raw frame duration isn't
 * compared to the per-frame budget directly for the jank bucket.
 *
 * Every frame is attributed to the currently visible tracked screens via [screenAccumulators]
 * (see [onScreenVisible]/[onScreenHidden]); the resulting per-screen metrics ship only as fields
 * on that screen's Timer - there is no app-wide accumulation and no warning events.
 */
internal class JankFrameMonitor(
    configuration: BlueTriangleConfiguration
) : Application.ActivityLifecycleCallbacks {

    private val logger = configuration.logger
    private val screenAccumulators = JankScreenAccumulator()

    private val jankStatsByWindow: MutableMap<Window, JankStats> =
        Collections.synchronizedMap(WeakHashMap())

    /** Application used at [start], retained so [stop] can always unregister even if the Tracker weak context was cleared. */
    private var application: Application? = null

    var isActive: Boolean = false
        private set

    fun start(application: Application) {
        this.application = application
        application.registerActivityLifecycleCallbacks(this)
        isActive = true
    }

    /**
     * Tears down lifecycle registration and disables all [JankStats] listeners.
     * Uses the [Application] passed at [start] when [application] is null so teardown still works
     * if the Tracker weak context was GC'd.
     */
    fun stop(application: Application? = null) {
        if (!isActive) return
        val app = application ?: this.application
        app?.unregisterActivityLifecycleCallbacks(this)
        synchronized(jankStatsByWindow) {
            jankStatsByWindow.values.forEach { it.isTrackingEnabled = false }
            jankStatsByWindow.clear()
        }
        screenAccumulators.clear()
        this.application = null
        isActive = false
    }

    /**
     * Call when a tracked screen becomes visible, keyed the same way
     * [com.bluetriangle.analytics.screenTracking.BTTScreenLifecycleTracker] keys its timers.
     *
     * @param isContent `false` for host Activities; `true` for Fragments/Compose/custom content.
     * When any content screen is visible, frames are attributed only to content screens (not the host).
     */
    fun onScreenVisible(screenKey: String, isContent: Boolean = true) {
        if (!isActive) return
        screenAccumulators.onScreenVisible(screenKey, isContent)
    }

    /**
     * Call when a tracked screen stops being visible. Returns its final jank/hitch snapshot, or
     * null if it was never tracked (e.g. jank tracking was off while it was visible).
     */
    fun onScreenHidden(screenKey: String): JankMetrics? {
        if (!isActive) return null
        return screenAccumulators.onScreenHidden(screenKey)
    }

    override fun onActivityResumed(activity: Activity) {
        if (!isActive) return
        val window = activity.window ?: return
        try {
            val jankStats = synchronized(jankStatsByWindow) {
                jankStatsByWindow.getOrPut(window) { createJankStats(window) }
            }
            jankStats.isTrackingEnabled = true
        } catch (e: Exception) {
            logger?.error(e, "Unable to attach JankStats listener")
        }
    }

    override fun onActivityPaused(activity: Activity) {
        val window = activity.window ?: return
        try {
            synchronized(jankStatsByWindow) { jankStatsByWindow[window] }?.isTrackingEnabled = false
        } catch (e: Exception) {
            logger?.error(e, "Unable to detach JankStats listener")
        }
    }

    private fun createJankStats(window: Window): JankStats {
        return JankStats.createAndTrack(window) { frameData ->
            // Buckets are cut on the frame's full UI duration (see JankMetrics): hang/hitch are
            // fixed wall-time thresholds, and only sub-hitch frames rely on JankStats' isJank
            // classification (duration > multiplier × refresh-rate budget).
            screenAccumulators.recordFrame(frameData.isJank, frameData.frameDurationUiNanos, window.frameBudget)
        }.also { it.jankHeuristicMultiplier = Constants.JANK_HEURISTIC_MULTIPLIER }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

val Window.frameBudget: Long
    get() {
        val refreshRate = windowManager?.defaultDisplay?.refreshRate?.takeIf { it > 0f } ?: Constants.DEFAULT_SCREEN_REFRESH_RATE
        return (1_000_000_000.0 / refreshRate).toLong()
    }