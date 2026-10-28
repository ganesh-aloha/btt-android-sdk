package com.bluetriangle.analytics.jank

import com.bluetriangle.analytics.Constants
import com.bluetriangle.analytics.Timer
import org.json.JSONObject
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
 *
 * The beacon carries frame health as exactly two fields - [Constants.RESPONSIVENESS_GRADE], the
 * single 0-98 badness score, and [Constants.RESPONSIVENESS_META], the whole [JankMetrics] snapshot
 * as a JSON *string*. The individual counters and the old per-screen percent/ratio fields are no
 * longer top-level beacon fields, so everything but the grade is read back out of the meta blob.
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
        longestHangMs: Long = 0,
        jankHistogram: String = EMPTY_JANK_HISTOGRAM,
        jankSeverity: Double = 0.0
    ) = JankMetrics(
        totalFrames, jankFrameCount, totalJankDurationMs, longestJankMs,
        hangFrameCount, totalHangDurationMs, longestHangMs, jankHistogram, jankSeverity
    )

    /** The [Constants.RESPONSIVENESS_META] blob, parsed back from the string the beacon carries. */
    private fun JSONObject.responsivenessMeta() =
        JSONObject(getString(Constants.RESPONSIVENESS_META))

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
    fun `toJSONObject serializes every counter into the responsivenessMeta blob`() {
        val timer = Timer("Page", "Segment")
        timer.setJankReportFields(
            metrics(
                totalFrames = 776,
                jankFrameCount = 12, totalJankDurationMs = 1_507, longestJankMs = 489,
                hangFrameCount = 3, totalHangDurationMs = 3_016, longestHangMs = 1_006,
                jankHistogram = "[{50, 8}, {300, 2}, {750, 2}]", jankSeverity = 13.0
            )
        )

        val meta = timer.nativeAppProperties.toJSONObject().responsivenessMeta()

        assertEquals(776L, meta.getLong("totalFrameCount"))
        assertEquals(12L, meta.getLong("hitchCount"))
        assertEquals(1_507L, meta.getLong("totalHitchDuration"))
        assertEquals(489L, meta.getLong("longestHitch"))
        assertEquals(3L, meta.getLong("hangCount"))
        assertEquals(3_016L, meta.getLong("totalHangDuration"))
        assertEquals(1_006L, meta.getLong("longestHang"))
        assertEquals(13.0, meta.getDouble("hitchesSeverity"), 0.0)
        assertEquals("[{50, 8}, {300, 2}, {750, 2}]", meta.getString("hitchHistograms"))
    }

    @Test
    fun `toJSONObject grades the stamped metrics into responsivenessGrade`() {
        val timer = Timer("Page", "Segment")
        val stamped = metrics(
            totalFrames = 776,
            jankFrameCount = 12, totalJankDurationMs = 1_507, longestJankMs = 489,
            hangFrameCount = 3, totalHangDurationMs = 3_016, longestHangMs = 1_006,
            jankHistogram = "[{50, 8}, {300, 2}, {750, 2}]", jankSeverity = 13.0
        )
        timer.setJankReportFields(stamped)

        val json = timer.nativeAppProperties.toJSONObject()

        // jank 13, hangs 43.33 (3 hangs, the 1006ms longest is milder) -> 43.33 + 0.87 * 13 * 0.4333
        assertEquals(48, json.getInt(Constants.RESPONSIVENESS_GRADE))
        assertEquals(stamped.responsivenessGrade, json.getInt(Constants.RESPONSIVENESS_GRADE))
    }

    @Test
    fun `toJSONObject does not carry the individual counters as top-level fields`() {
        val timer = Timer("Page", "Segment")
        timer.setJankReportFields(
            metrics(
                totalFrames = 100,
                jankFrameCount = 5, totalJankDurationMs = 200, longestJankMs = 60,
                hangFrameCount = 1, totalHangDurationMs = 300, longestHangMs = 300
            )
        )

        val json = timer.nativeAppProperties.toJSONObject()

        // the counters live inside the meta blob only
        assertFalse(json.has("hitchCount"))
        assertFalse(json.has("totalHitchDuration"))
        assertFalse(json.has("longestHitch"))
        assertFalse(json.has("hangCount"))
        assertFalse(json.has("totalHangDuration"))
        assertFalse(json.has("longestHang"))
        assertFalse(json.has("totalFrameCount"))
    }

    @Test
    fun `toJSONObject reports grade 0 and an empty meta when no metrics were stamped`() {
        val timer = Timer("Page", "Segment")

        val json = timer.nativeAppProperties.toJSONObject()

        assertEquals(0, json.getInt(Constants.RESPONSIVENESS_GRADE))
        assertEquals("", json.getString(Constants.RESPONSIVENESS_META))
    }

    @Test
    fun `toJSONObject omits frame health fields when responsiveness is not being sent`() {
        val timer = Timer("Page", "Segment")
        timer.setJankReportFields(metrics(totalFrames = 100, jankFrameCount = 5, hangFrameCount = 1))

        val json = timer.nativeAppProperties.toJSONObject(sendResponsiveNess = false)

        assertFalse(json.has(Constants.RESPONSIVENESS_GRADE))
        assertFalse(json.has(Constants.RESPONSIVENESS_META))
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
