package com.bluetriangle.analytics.jank

import com.bluetriangle.analytics.Constants
import com.bluetriangle.analytics.Timer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers how per-screen frame health metrics ride on [Timer.nativeAppProperties] and are
 * serialized into the NATIVEAPP beacon field.
 */
@RunWith(RobolectricTestRunner::class)
class JankTimerFieldsTest {

    private fun metrics(
        totalFrames: Long,
        jankFrameCount: Long = 0,
        totalJankDurationMs: Long = 0,
        jankTimeRatio: Double = 0.0,
        longestJankMs: Long = 0,
        hitchCount: Long = 0,
        totalHitchDurationMs: Long = 0,
        hitchTimeRatio: Double = 0.0,
        longestHitchMs: Long = 0,
        hangCount: Long = 0,
        totalHangDurationMs: Long = 0,
        hangTimeRatio: Double = 0.0,
        longestHangMs: Long = 0
    ) = JankMetrics(
        totalFrames, jankFrameCount, totalJankDurationMs, jankTimeRatio, longestJankMs,
        hitchCount, totalHitchDurationMs, hitchTimeRatio, longestHitchMs,
        hangCount, totalHangDurationMs, hangTimeRatio, longestHangMs
    )

    @Test
    fun `setJankReportFields stamps metrics onto nativeAppProperties`() {
        val timer = Timer("Page", "Segment")
        val stamped = metrics(totalFrames = 10, jankFrameCount = 2, totalJankDurationMs = 80)

        timer.setJankReportFields(stamped)

        assertEquals(stamped, timer.nativeAppProperties.jankMetrics)
    }

    @Test
    fun `setJankReportFields does not overwrite already-stamped metrics`() {
        val timer = Timer("Page", "Segment")
        val first = metrics(totalFrames = 10, jankFrameCount = 2)
        timer.setJankReportFields(first)

        timer.setJankReportFields(metrics(totalFrames = 100, jankFrameCount = 99))

        assertEquals(first, timer.nativeAppProperties.jankMetrics)
    }

    @Test
    fun `generateNativeAppProperties preserves stamped metrics across regeneration`() {
        val timer = Timer("Page", "Segment")
        val stamped = metrics(totalFrames = 10, jankFrameCount = 2)
        timer.setJankReportFields(stamped)

        timer.generateNativeAppProperties()

        assertEquals(stamped, timer.nativeAppProperties.jankMetrics)
    }

    @Test
    fun `copyJankReportFieldsFrom copies metrics from the source timer`() {
        val source = Timer("Source", "Seg")
        val stamped = metrics(
            totalFrames = 12, jankFrameCount = 3, totalJankDurationMs = 120,
            hangCount = 1, totalHangDurationMs = 270, longestHangMs = 270
        )
        source.setJankReportFields(stamped)

        val target = Timer("Group", "Seg")
        target.copyJankReportFieldsFrom(source)

        assertEquals(stamped, target.nativeAppProperties.jankMetrics)
    }

    @Test
    fun `copyJankReportFieldsFrom does not overwrite metrics already on the target`() {
        val source = Timer("Source", "Seg")
        source.setJankReportFields(metrics(totalFrames = 12, jankFrameCount = 3))

        val target = Timer("Group", "Seg")
        val targetOwn = metrics(totalFrames = 50, jankFrameCount = 7)
        target.setJankReportFields(targetOwn)
        target.copyJankReportFieldsFrom(source)

        assertEquals(targetOwn, target.nativeAppProperties.jankMetrics)
    }

    @Test
    fun `copyJankReportFieldsFrom is a no-op when the source has no metrics`() {
        val source = Timer("Source", "Seg")
        val target = Timer("Group", "Seg")

        target.copyJankReportFieldsFrom(source)

        assertNull(target.nativeAppProperties.jankMetrics)
    }

    @Test
    fun `toJSONObject serializes all thirteen frame health fields`() {
        val timer = Timer("Page", "Segment")
        timer.setJankReportFields(
            metrics(
                totalFrames = 100,
                jankFrameCount = 5, totalJankDurationMs = 200, jankTimeRatio = 0.02, longestJankMs = 60,
                hitchCount = 2, totalHitchDurationMs = 260, hitchTimeRatio = 0.026, longestHitchMs = 140,
                hangCount = 1, totalHangDurationMs = 300, hangTimeRatio = 0.03, longestHangMs = 300
            )
        )

        val json = timer.nativeAppProperties.toJSONObject()

        assertEquals(100L, json.getLong(Constants.TOTAL_FRAME_COUNT))
        assertEquals(5L, json.getLong(Constants.JANK_FRAME_COUNT))
        assertEquals(200L, json.getLong(Constants.TOTAL_JANK_DURATION))
        assertEquals(0.02, json.getDouble(Constants.JANK_TIME_RATIO), 0.0)
        assertEquals(60L, json.getLong(Constants.LONGEST_JANK))
        assertEquals(2L, json.getLong(Constants.HITCH_COUNT))
        assertEquals(260L, json.getLong(Constants.TOTAL_HITCH_DURATION))
        assertEquals(0.026, json.getDouble(Constants.HITCH_TIME_RATIO), 0.0)
        assertEquals(140L, json.getLong(Constants.LONGEST_HITCH))
        assertEquals(1L, json.getLong(Constants.HANG_COUNT))
        assertEquals(300L, json.getLong(Constants.TOTAL_HANG_DURATION))
        assertEquals(0.03, json.getDouble(Constants.HANG_TIME_RATIO), 0.0)
        assertEquals(300L, json.getLong(Constants.LONGEST_HANG))
    }

    @Test
    fun `toJSONObject omits frame health fields when no metrics were stamped`() {
        val timer = Timer("Page", "Segment")

        val json = timer.nativeAppProperties.toJSONObject()

        assertFalse(json.has(Constants.TOTAL_FRAME_COUNT))
        assertFalse(json.has(Constants.JANK_FRAME_COUNT))
        assertFalse(json.has(Constants.HITCH_COUNT))
        assertFalse(json.has(Constants.HANG_COUNT))
    }

    @Test
    fun `metrics stay unset until stamped`() {
        val timer = Timer("Page", "Segment")
        assertNull(timer.nativeAppProperties.jankMetrics)

        timer.setJankReportFields(metrics(totalFrames = 20, jankFrameCount = 5))
        assertTrue(timer.nativeAppProperties.jankMetrics != null)
        assertEquals(20L, timer.nativeAppProperties.jankMetrics?.totalFrames)
    }
}
