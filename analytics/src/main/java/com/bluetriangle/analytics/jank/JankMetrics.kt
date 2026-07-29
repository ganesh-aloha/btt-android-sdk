package com.bluetriangle.analytics.jank

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A snapshot of cumulative frame-health stats accumulated since the last [JankFrameAccumulator.reset].
 *
 * Every observed frame lands in at most one of three mutually exclusive buckets, by full frame
 * duration (most severe wins):
 * - **hang**: duration >= [com.bluetriangle.analytics.Constants.HANG_THRESHOLD_MS]
 * - **hitch**: duration >= [com.bluetriangle.analytics.Constants.HITCH_THRESHOLD_MS]
 * - **jank**: classified janky by [androidx.metrics.performance.JankStats]
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
 * @param hitchCount frames classified as hitches
 * @param totalHitchDurationMs cumulative full duration (ms) of hitch frames
 * @param longestHitchMs longest single hitch frame (ms)
 * @param hangCount frames classified as hangs
 * @param totalHangDurationMs cumulative full duration (ms) of hang frames
 * @param longestHangMs longest single hang frame (ms)
 */
@Parcelize
internal data class JankMetrics(
    val totalFrames: Long,
    val jankFrameCount: Long,
    val totalJankDurationMs: Long,
    val longestJankMs: Long,
    val hitchCount: Long,
    val totalHitchDurationMs: Long,
    val longestHitchMs: Long,
    val hangCount: Long,
    val totalHangDurationMs: Long,
    val longestHangMs: Long
) : Parcelable
