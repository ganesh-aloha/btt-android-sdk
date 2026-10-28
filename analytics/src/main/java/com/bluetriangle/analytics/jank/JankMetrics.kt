package com.bluetriangle.analytics.jank

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.json.JSONObject

/**
 * A snapshot of cumulative frame-health stats accumulated since the last [JankFrameAccumulator.reset].
 *
 * Every observed frame lands in at most one of three mutually exclusive buckets, by full frame
 * duration (most severe wins):
 * - **hang**: duration >= [com.bluetriangle.analytics.Constants.HANG_THRESHOLD_MS]
 *   (duration > [com.bluetriangle.analytics.Constants.JANK_HEURISTIC_MULTIPLIER] x frame budget)
 *
 * Durations are the **full** frame duration in ms (not the overrun beyond the frame budget).
 *
 * @param totalFrames total number of frames observed
 * @param jankFrameCount frames classified as jank
 * @param totalJankDurationMs cumulative full duration (ms) of jank frames
 * @param longestJankMs longest single jank frame (ms)
 * @param hangFrameCount frames classified as hangs
 * @param totalHangDurationMs cumulative full duration (ms) of hang frames
 * @param longestHangMs longest single hang frame (ms)
 * @param jankHistogram how the jank (hitch) frames spread across the fixed duration bins in
 * [JankFrameAccumulator.JANK_HISTOGRAM_BOUNDS_MS], already flattened to its beacon form -
 * `[{50, 5}, {150, 2}, {300, 4}, {450, 1}]`, one `{upperBoundMs, count}` pair per **populated** bin,
 * ascending by bound. Empty bins are omitted, so a screen with no jank's is [EMPTY_JANK_HISTOGRAM];
 * the counts sum to [jankFrameCount].
 * @param jankSeverity weighted severity of those hitches, `sum(count * weight)` over
 * [JankFrameAccumulator.JANK_SEVERITY_WEIGHTS] - 0.0 when no jank was recorded, otherwise growing
 * with **both** how bad the hitches were and how many there were (10 mild hitches score the same
 * 5.0 as one 300-450ms hitch plus a mild one). Unbounded and not normalized by time on screen, so
 * it is a raw badness total, not a rate; [ResponsivenessGrade] caps it when scoring.
 */
@Parcelize
internal data class JankMetrics(
    val totalFrames: Long,
    val jankFrameCount: Long,
    val totalJankDurationMs: Long,
    val longestJankMs: Long,
    val hangFrameCount: Long,
    val totalHangDurationMs: Long,
    val longestHangMs: Long,
    val jankHistogram: String = EMPTY_JANK_HISTOGRAM,
    val jankSeverity: Double = 0.0
) : Parcelable{
    fun toJsonString(): String {
        return JSONObject().apply {
            put("totalFrameCount", totalFrames)
            put("hitchCount", jankFrameCount)
            put("hangCount", hangFrameCount)
            put("totalHitchDuration", totalJankDurationMs)
            put("totalHangDuration", totalHangDurationMs)
            put("longestHitch", longestJankMs)
            put("longestHang", longestHangMs)
            put("hitchesSeverity", jankSeverity)
            put("hitchHistograms", jankHistogram)
        }.toString()
    }
}

/** Hitch histogram value for a screen with no hitches in any bin. */
internal const val EMPTY_JANK_HISTOGRAM = "[]"

/** This screen's [ResponsivenessGrade] badness score, 0 (smooth) to 98 (worst reachable). */
internal val JankMetrics.responsivenessGrade: Int
    get() = ResponsivenessGrade.grade(jankSeverity, hangFrameCount, longestHangMs)