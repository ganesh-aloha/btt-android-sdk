package com.bluetriangle.analytics.jank

import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Attributes frames to currently visible tracked screens.
 *
 * Product rules:
 * - **Activity without content (Fragment/Compose/Custom):** frames go to the host Activity.
 * - **Activity with content screens:** frames go to **every currently visible content screen**
 *   (each Fragment / Compose destination), not the host Activity. This avoids the host stealing
 *   frames when Activity lifecycle callbacks fire after Fragment/Compose resume.
 * - Concurrent multi-pane Fragments each receive the same frames for their overlapping visible
 *   windows (each screen's timer then reports its own full-window rates).
 *
 * Pure Kotlin, no Android dependencies, so it stays unit-testable without Robolectric.
 */
internal class JankScreenAccumulator {

    private val lock = Any()

    /** Insertion-ordered map of currently visible screen keys → whether the screen is content. */
    private val visibleScreens = LinkedHashMap<String, Boolean>()
    private val accumulators = ConcurrentHashMap<String, JankFrameAccumulator>()

    /**
     * Call when a screen becomes visible.
     *
     * @param isContent `false` for host Activities; `true` for Fragments, Compose destinations,
     * and other content screens. When any content screen is visible, hosts are excluded from
     * frame attribution.
     *
     * Re-entrant visibility for the same key is ignored so frames already accumulated are not
     * discarded (e.g. duplicate [onScreenVisible] without a matching hide).
     */
    fun onScreenVisible(screenKey: String, isContent: Boolean = true) {
        synchronized(lock) {
            if (visibleScreens.containsKey(screenKey)) return
            visibleScreens[screenKey] = isContent
            accumulators[screenKey] = JankFrameAccumulator()
        }
    }

    /**
     * Call when a screen is no longer visible. Stops attributing further frames to it and returns
     * its final snapshot, or null if it was never tracked.
     */
    fun onScreenHidden(screenKey: String): JankMetrics? {
        synchronized(lock) {
            visibleScreens.remove(screenKey)
            return accumulators.remove(screenKey)?.snapshot()
        }
    }

    /**
     * Routes a frame to every currently-eligible visible screen:
     * - if any content screen is visible → all content screens
     * - else → all host screens
     */
    fun recordFrame(isJank: Boolean, frameDurationNanos: Long, frameBudgetNanos: Long) {
        synchronized(lock) {
            if (visibleScreens.isEmpty()) return
            val hasContent = visibleScreens.values.any { it }
            for ((key, isContent) in visibleScreens) {
                if (isContent == hasContent) {
                    accumulators[key]?.recordFrame(isJank, frameDurationNanos, frameBudgetNanos)
                }
            }
        }
    }

    /** Drops all in-flight per-screen state (e.g. when jank tracking is torn down). */
    fun clear() {
        synchronized(lock) {
            visibleScreens.clear()
            accumulators.clear()
        }
    }
}
