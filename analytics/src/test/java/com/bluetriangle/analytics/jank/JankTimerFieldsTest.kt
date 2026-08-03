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
        longestJankMs: Long = 0,
        hangFrameCount: Long = 0,
        totalHangDurationMs: Long = 0,
        longestHangMs: Long = 0
    ) = JankMetrics(
        totalFrames, jankFrameCount, totalJankDurationMs, longestJankMs,
        hangFrameCount, totalHangDurationMs, longestHangMs
    )

    @Test
    fun `setJankReportFields stamps metrics onto nativeAppProperties`() {
        val timer = Timer("Page", "Segment")
        val stamped = metrics(totalFrames = 10, jankFrameCount = 2, totalJankDurationMs = 80)

        timer.setJankReportFields(stamped)

        assertEquals(stamped, timer.nativeAppProperties.jankMetrics)
    }

    @Test
    fun `setJankReportFields replaces previously stamped metrics`() {
        val timer = Timer("Page", "Segment")
        timer.setJankReportFields(metrics(totalFrames = 10, jankFrameCount = 2))

        val latest = metrics(totalFrames = 100, jankFrameCount = 99)
        timer.setJankReportFields(latest)

        assertEquals(latest, timer.nativeAppProperties.jankMetrics)
    }

    @Test
    fun `copyJankReportFieldsFrom copies metrics from the source timer`() {
        val source = Timer("Source", "Seg")
        val stamped = metrics(
            totalFrames = 12, jankFrameCount = 3, totalJankDurationMs = 120,
            hangFrameCount = 1, totalHangDurationMs = 270, longestHangMs = 270
        )
        source.setJankReportFields(stamped)

        val target = Timer("Group", "Seg")
        target.copyJankReportFieldsFrom(source)

        assertEquals(stamped, target.nativeAppProperties.jankMetrics)
    }

    @Test
    fun `copyJankReportFieldsFrom is a no-op when the source has no metrics`() {
        val source = Timer("Source", "Seg")
        val target = Timer("Group", "Seg")

        target.copyJankReportFieldsFrom(source)

        assertNull(target.nativeAppProperties.jankMetrics)
    }

    @Test
    fun `toJSONObject serializes frame health fields with ratios derived from fullTime`() {
        val timer = Timer("Page", "Segment")
        timer.setJankReportFields(
            metrics(
                totalFrames = 100,
                jankFrameCount = 5, totalJankDurationMs = 200, longestJankMs = 60,
                hangFrameCount = 1, totalHangDurationMs = 300, longestHangMs = 300
            )
        )
        timer.nativeAppProperties.fullTime = 5_000 // 5 seconds on screen

        val json = timer.nativeAppProperties.toJSONObject()

        assertEquals(5L, json.getLong(Constants.JANK_FRAME_COUNT))
        assertEquals(200L, json.getLong(Constants.TOTAL_JANK_DURATION))
        assertEquals(60L, json.getLong(Constants.LONGEST_JANK_DURATION))
        // ratio = totalJankDurationMs / (fullTime ms / 1000) = 200 / 5
        assertEquals(40.0, json.getDouble(Constants.JANK_TIME_RATIO), 0.0)
        assertEquals(1L, json.getLong(Constants.HANG_COUNT))
        assertEquals(300L, json.getLong(Constants.TOTAL_HANG_DURATION))
        assertEquals(300L, json.getLong(Constants.LONGEST_HANG_DURATION))
        assertEquals(60.0, json.getDouble(Constants.HANG_TIME_RATIO), 0.0)
    }

    @Test
    fun `toJSONObject zeroes frame health fields when no metrics were stamped`() {
        val timer = Timer("Page", "Segment")

        val json = timer.nativeAppProperties.toJSONObject()

        assertEquals(0L, json.getLong(Constants.JANK_FRAME_COUNT))
        assertEquals(0L, json.getLong(Constants.TOTAL_JANK_DURATION))
        assertEquals(0L, json.getLong(Constants.HANG_COUNT))
        assertEquals(0L, json.getLong(Constants.TOTAL_HANG_DURATION))
    }

    @Test
    fun `toJSONObject omits frame health fields when responsiveness is not being sent`() {
        val timer = Timer("Page", "Segment")
        timer.setJankReportFields(metrics(totalFrames = 100, jankFrameCount = 5, hangFrameCount = 1))

        val json = timer.nativeAppProperties.toJSONObject(sendResponsiveNess = false)

        assertFalse(json.has(Constants.JANK_FRAME_COUNT))
        assertFalse(json.has(Constants.TOTAL_JANK_DURATION))
        assertFalse(json.has(Constants.HANG_COUNT))
        assertFalse(json.has(Constants.TOTAL_HANG_DURATION))
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
