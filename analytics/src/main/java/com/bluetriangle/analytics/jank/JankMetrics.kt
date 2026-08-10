package com.bluetriangle.analytics.jank

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.math.roundToInt

/**
 * A snapshot of cumulative frame-health stats accumulated since the last [JankFrameAccumulator.reset].
 *
 * Every observed frame lands in at most one of three mutually exclusive buckets, by full frame
 * duration (most severe wins):
 * - **hang**: duration >= [com.bluetriangle.analytics.Constants.HANG_THRESHOLD_MS]
 *   (duration > [com.bluetriangle.analytics.Constants.JANK_HEURISTIC_MULTIPLIER] x frame budget)
 *
 * Durations are the **full** frame duration in ms (not the overrun beyond the frame budget).
 * Each `*TimeRatio` is that bucket's total duration divided by the wall-clock ms elapsed since
 * the last reset (i.e. the time the screen was visible), 0.0-1.0.
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
 * @param jankSeverity weighted mean severity of those hitches, `sum(count * weight) / sum(count)`
 * over [JankFrameAccumulator.JANK_SEVERITY_WEIGHTS] - between the lightest and heaviest bin weight
 * (0.5-4.0) when any jank was recorded, 0.0 when none was. Scale-free: it says how bad the typical
 * hitch was, not how many there were, so pair it with [jankFrameCount] to tell a screen with one
 * long jank from a screen full of them.
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
) : Parcelable

/** Hitch histogram value for a screen with no hitches in any bin. */
internal const val EMPTY_JANK_HISTOGRAM = "[]"

internal val JankMetrics.jankFramePercentage: Int
    get() = if (totalFrames == 0L) {
        0
    } else {
        (jankFrameCount * 100.0 / totalFrames).roundToInt()
    }

internal val JankMetrics.hangFramePercentage: Int
    get() = if (totalFrames == 0L) {
        0
    } else {
        (hangFrameCount * 100.0 / totalFrames).roundToInt()
    }

internal fun JankMetrics.getJankTimePercentage(screenTimeInSeconds: Long): Int {
    if (screenTimeInSeconds == 0L) return 0
    return (totalJankDurationMs * 100.0 / screenTimeInSeconds).roundToInt()
}

internal fun JankMetrics.getHangTimePercentage(screenTimeInSeconds: Long): Int {
    if (screenTimeInSeconds == 0L) return 0
    return (totalHangDurationMs * 100.0 / screenTimeInSeconds).roundToInt()
}