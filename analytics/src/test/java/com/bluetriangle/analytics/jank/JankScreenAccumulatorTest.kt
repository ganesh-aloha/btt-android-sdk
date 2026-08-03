package com.bluetriangle.analytics.jank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JankScreenAccumulatorTest {

    private val registry = JankScreenAccumulator()

    /**
     * Frames carry their own overrun, which is what the buckets measure. These cases describe a
     * frame by duration/budget, so derive the overrun from them.
     */
    private fun JankScreenAccumulator.record(
        isJank: Boolean,
        frameDurationNanos: Long,
        frameBudgetNanos: Long
    ) = recordFrame(isJank, frameDurationNanos, frameBudgetNanos, frameDurationNanos - frameBudgetNanos)

    @Test
    fun `frame recorded before any screen is visible is dropped`() {
        registry.record(isJank = true, frameDurationNanos = 40_000_000L, frameBudgetNanos = 16_666_666L)
        // Nothing to assert on directly, but a later screen shouldn't see it.
        registry.onScreenVisible("Activity#1", isContent = false)
        registry.record(isJank = false, frameDurationNanos = 8_000_000L, frameBudgetNanos = 16_666_666L)
        val snapshot = registry.onScreenHidden("Activity#1")
        assertEquals(1L, snapshot?.totalFrames)
        assertEquals(0L, snapshot?.jankFrameCount)
    }

    @Test
    fun `frames while only the activity is visible are attributed to the activity`() {
        registry.onScreenVisible("Activity#1", isContent = false)
        registry.record(isJank = true, frameDurationNanos = 40_000_000L, frameBudgetNanos = 16_666_666L)
        registry.record(isJank = false, frameDurationNanos = 8_000_000L, frameBudgetNanos = 16_666_666L)

        val activitySnapshot = registry.onScreenHidden("Activity#1")
        assertEquals(2L, activitySnapshot?.totalFrames)
        assertEquals(1L, activitySnapshot?.jankFrameCount)
    }

    @Test
    fun `fragment hosted in an activity gets frames instead of the host activity`() {
        // Ideal order: Activity first, then Fragment
        registry.onScreenVisible("HostActivity#1", isContent = false)
        registry.record(isJank = true, frameDurationNanos = 40_000_000L, frameBudgetNanos = 16_666_666L) // activity only

        registry.onScreenVisible("ChildFragment#2", isContent = true)
        registry.record(isJank = true, frameDurationNanos = 40_000_000L, frameBudgetNanos = 16_666_666L)
        registry.record(isJank = false, frameDurationNanos = 8_000_000L, frameBudgetNanos = 16_666_666L)

        val fragmentSnapshot = registry.onScreenHidden("ChildFragment#2")
        assertEquals(2L, fragmentSnapshot?.totalFrames)
        assertEquals(1L, fragmentSnapshot?.jankFrameCount)

        // back to activity-only
        registry.record(isJank = false, frameDurationNanos = 8_000_000L, frameBudgetNanos = 16_666_666L)

        val activitySnapshot = registry.onScreenHidden("HostActivity#1")
        // 1 frame before fragment + 1 frame after fragment (frames during fragment are not on host)
        assertEquals(2L, activitySnapshot?.totalFrames)
        assertEquals(1L, activitySnapshot?.jankFrameCount)
    }

    @Test
    fun `production lifecycle order fragment then activity still attributes frames to fragment`() {
        // Real Android order: Fragment.onResume (content) fires before Activity onPostResumed (host).
        registry.onScreenVisible("ChildFragment#2", isContent = true)
        registry.onScreenVisible("HostActivity#1", isContent = false)

        registry.record(isJank = true, frameDurationNanos = 40_000_000L, frameBudgetNanos = 16_666_666L)
        registry.record(isJank = false, frameDurationNanos = 8_000_000L, frameBudgetNanos = 16_666_666L)

        val fragmentSnapshot = registry.onScreenHidden("ChildFragment#2")
        assertEquals(2L, fragmentSnapshot?.totalFrames)
        assertEquals(1L, fragmentSnapshot?.jankFrameCount)

        val activitySnapshot = registry.onScreenHidden("HostActivity#1")
        // Host must not steal frames while content is visible
        assertEquals(0L, activitySnapshot?.totalFrames)
        assertEquals(0L, activitySnapshot?.jankFrameCount)
    }

    @Test
    fun `compose content screen gets frames not the host activity`() {
        registry.onScreenVisible("HostActivity#1", isContent = false)
        registry.onScreenVisible("HomeScreen#42", isContent = true)

        registry.record(isJank = true, frameDurationNanos = 40_000_000L, frameBudgetNanos = 16_666_666L)
        registry.record(isJank = true, frameDurationNanos = 40_000_000L, frameBudgetNanos = 16_666_666L)
        registry.record(isJank = false, frameDurationNanos = 8_000_000L, frameBudgetNanos = 16_666_666L)

        val composeSnapshot = registry.onScreenHidden("HomeScreen#42")
        assertEquals(3L, composeSnapshot?.totalFrames)
        assertEquals(2L, composeSnapshot?.jankFrameCount)

        val activitySnapshot = registry.onScreenHidden("HostActivity#1")
        assertEquals(0L, activitySnapshot?.totalFrames)
    }

    @Test
    fun `multi-fragment concurrent visibility attributes frames to each fragment`() {
        registry.onScreenVisible("HostActivity#1", isContent = false)
        registry.onScreenVisible("MasterFragment#1", isContent = true)
        registry.onScreenVisible("DetailFragment#2", isContent = true)

        registry.record(isJank = true, frameDurationNanos = 40_000_000L, frameBudgetNanos = 16_666_666L)
        registry.record(isJank = false, frameDurationNanos = 8_000_000L, frameBudgetNanos = 16_666_666L)

        val master = registry.onScreenHidden("MasterFragment#1")
        val detail = registry.onScreenHidden("DetailFragment#2")
        val activity = registry.onScreenHidden("HostActivity#1")

        assertEquals(2L, master?.totalFrames)
        assertEquals(1L, master?.jankFrameCount)
        assertEquals(2L, detail?.totalFrames)
        assertEquals(1L, detail?.jankFrameCount)
        assertEquals(0L, activity?.totalFrames)
    }

    @Test
    fun `hiding an untracked screen key returns null`() {
        assertNull(registry.onScreenHidden("NeverShown#99"))
    }

    @Test
    fun `revisiting the same screen key starts a fresh accumulator`() {
        registry.onScreenVisible("Activity#1", isContent = false)
        registry.record(isJank = true, frameDurationNanos = 40_000_000L, frameBudgetNanos = 16_666_666L)
        registry.onScreenHidden("Activity#1")

        registry.onScreenVisible("Activity#1", isContent = false)
        registry.record(isJank = false, frameDurationNanos = 8_000_000L, frameBudgetNanos = 16_666_666L)
        val snapshot = registry.onScreenHidden("Activity#1")

        assertEquals(1L, snapshot?.totalFrames)
        assertEquals(0L, snapshot?.jankFrameCount)
    }

    @Test
    fun `duplicate onScreenVisible without hide does not reset frames`() {
        registry.onScreenVisible("Fragment#1", isContent = true)
        registry.record(isJank = true, frameDurationNanos = 40_000_000L, frameBudgetNanos = 16_666_666L)

        // Re-entrant visible must not wipe the accumulator
        registry.onScreenVisible("Fragment#1", isContent = true)
        registry.record(isJank = false, frameDurationNanos = 8_000_000L, frameBudgetNanos = 16_666_666L)

        val snapshot = registry.onScreenHidden("Fragment#1")
        assertEquals(2L, snapshot?.totalFrames)
        assertEquals(1L, snapshot?.jankFrameCount)
    }

    @Test
    fun `clear drops all in-flight screen state`() {
        registry.onScreenVisible("Activity#1", isContent = false)
        registry.record(isJank = true, frameDurationNanos = 40_000_000L, frameBudgetNanos = 16_666_666L)
        registry.clear()

        assertNull(registry.onScreenHidden("Activity#1"))
        registry.onScreenVisible("Activity#1", isContent = false)
        registry.record(isJank = false, frameDurationNanos = 8_000_000L, frameBudgetNanos = 16_666_666L)
        assertEquals(1L, registry.onScreenHidden("Activity#1")?.totalFrames)
    }
}
