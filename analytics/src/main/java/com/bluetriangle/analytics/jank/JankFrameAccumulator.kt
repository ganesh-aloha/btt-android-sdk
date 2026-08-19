package com.bluetriangle.analytics.jank

import com.bluetriangle.analytics.Constants
import com.bluetriangle.analytics.utility.roundTo2Decimals
import com.bluetriangle.analytics.utility.roundTo4Decimals
import java.util.concurrent.atomic.AtomicLong

/**
 * Accumulates per-frame data into cumulative jank/hang counters. Pure Kotlin, no Android
 * dependencies, so it stays unit-testable without Robolectric.
 *
 * Each frame lands in at most one bucket (most severe wins) - see [JankMetrics] for the
 * classification rules and threshold rationale.
 */
internal class JankFrameAccumulator(private val nowMs: () -> Long = System::currentTimeMillis) {

    private class Bucket {
        val count = AtomicLong(0)
        val totalDurationNanos = AtomicLong(0)
        val longestDurationNanos = AtomicLong(0)

        fun record(durationNanos: Long) {
            count.incrementAndGet()
            totalDurationNanos.addAndGet(durationNanos)
            longestDurationNanos.updateMax(durationNanos)
        }

        fun reset() {
            count.set(0)
            totalDurationNanos.set(0)
            longestDurationNanos.set(0)
        }
    }

    private val totalFrames = AtomicLong(0)
    private val jank = Bucket()
    private val hang = Bucket()

    /**
     * Distribution of jank (hitch) frame overruns, keyed by inclusive upper bound in ms and kept in
     * ascending bound order so [snapshot] can emit the bins as-is.
     */
    private val jankHistogram: Map<Long, AtomicLong> =
        JANK_HISTOGRAM_BOUNDS_MS.associateWithTo(LinkedHashMap()) { AtomicLong(0) }

    @Volatile
    private var startTimeMs: Long = nowMs()

    /**
     * @param isJank whether the platform (via [androidx.metrics.performance.JankStats]) classified
     * this frame as janky - used only for the jank bucket; hang are pure duration thresholds
     * (any frame long enough for those buckets exceeds the JankStats heuristic anyway).
     * @param frameDurationNanos the frame's full UI duration.
     * @param frameBudgetNanos the frame's budgeted duration.
     * @param frameOverrunNanos the frame's overrun duration.
     */
    fun recordFrame(isJank: Boolean, frameDurationNanos: Long, frameBudgetNanos: Long, frameOverrunNanos: Long) {
        totalFrames.incrementAndGet()
        val durationNanos = frameOverrunNanos.coerceAtLeast(0L)
        when {
            durationNanos >= HANG_THRESHOLD_NANOS -> hang.record(durationNanos)
            isJank -> {
                jank.record(durationNanos)
                recordJankHistogram(durationNanos)
            }
        }
    }

    /**
     * Bins a hitch by its overrun. Every hitch is below the hang threshold, which is also the last
     * bound, so exactly one bin always matches.
     */
    private fun recordJankHistogram(durationNanos: Long) {
        val durationMs = durationNanos.nanosToMs()
        for ((upperBoundMs, count) in jankHistogram) {
            if (durationMs <= upperBoundMs) {
                count.incrementAndGet()
                return
            }
        }
    }

    /**
     * Flattens the live counters to the beacon's histogram form - `[{50, 5}, {150, 2}, {450, 1}]`,
     * one `{upperBoundMs, count}` pair per populated bin in ascending bound order.
     *
     * Empty bins are dropped, so pairs are not positional (consumers must key on the bound) and a
     * screen with no hitches renders as [EMPTY_JANK_HISTOGRAM]. Each counter is read exactly once
     * so a concurrent hitch can't be filtered in and then rendered with a different count.
     */
    private fun jankHistogramString(): String = jankHistogram.entries
        .mapNotNull { (upperBoundMs, counter) ->
            counter.get().takeIf { it > 0 }?.let { count -> "{$upperBoundMs, $count}" }
        }
        .joinToString(separator = ", ", prefix = "[", postfix = "]")

    /**
     * Weighted severity of the recorded jank's - `sum(count * weight)` over
     * [JANK_SEVERITY_WEIGHTS] - so it grows with both severity and volume: 20 mild hitches score
     * 10.0 where 2 score 1.0. 0.0 when there were none.
     *
     * Deliberately *not* divided by the jank count: [ResponsivenessGrade] scores it against a
     * 0-100 badness ramp and caps it, which needs a total that can exceed the cap, not a mean
     * bounded by the heaviest bin weight. Matches iOS's `weightedMean`.
     */
    private fun jankSeverity(): Double {
        var weightedSum = 0.0

        for ((upperBoundMs, counter) in jankHistogram) {
            val count = counter.get()
            if (count == 0L) continue

            weightedSum += count * (JANK_SEVERITY_WEIGHTS[upperBoundMs] ?: 0.0)
        }

        return (weightedSum).roundTo2Decimals()
    }

    fun snapshot(): JankMetrics {
        return JankMetrics(
            totalFrames = totalFrames.get(),
            jankFrameCount = jank.count.get(),
            totalJankDurationMs = jank.totalDurationNanos.get().nanosToMs(),
            longestJankMs = jank.longestDurationNanos.get().nanosToMs(),
            hangFrameCount = hang.count.get(),
            totalHangDurationMs = hang.totalDurationNanos.get().nanosToMs(),
            longestHangMs = hang.longestDurationNanos.get().nanosToMs(),
            jankHistogram = jankHistogramString(),
            jankSeverity = jankSeverity()
        )
    }

    fun reset() {
        totalFrames.set(0)
        jank.reset()
        hang.reset()
        jankHistogram.values.forEach { it.set(0) }
        startTimeMs = nowMs()
    }

    private fun timeRatio(bucket: Bucket, elapsedMs: Long): Double {
        if (elapsedMs == 0L) return 0.0
        val durationMs = bucket.totalDurationNanos.get() / 1_000_000.0
        return (durationMs / elapsedMs).coerceAtMost(1.0).roundTo4Decimals()
    }

    internal companion object {
        const val HANG_THRESHOLD_NANOS = Constants.HANG_THRESHOLD_MS * 1_000_000L

        /**
         * Inclusive upper bounds (ms) of the hitch histogram bins, ascending. The last bound is the
         * hang threshold - anything at or above it is a hang, not a hitch - so the bins cover every
         * possible hitch overrun with no spill-over bucket needed.
         */
        val JANK_HISTOGRAM_BOUNDS_MS =
            listOf(50L, 150L, 300L, 450L, Constants.HANG_THRESHOLD_MS)

        /**
         * Weights the bins of longer hitches more heavily for [jankSeverity]. Calculation-only -
         * not part of any bin's state, so it is never sent in a payload. Mirrors iOS's
         * `weightsByUpperBoundMs`; keep the two in step or the same screen scores differently per
         * platform.
         */
        val JANK_SEVERITY_WEIGHTS = mapOf(
            50L to 0.25,
            150L to 0.75,
            300L to 2.0,
            450L to 2.5,
            Constants.HANG_THRESHOLD_MS to 3.5
        )

        fun Long.nanosToMs(): Long = this / 1_000_000L

        /** CAS max - [AtomicLong.accumulateAndGet] needs API 24, the SDK's minSdk is lower. */
        fun AtomicLong.updateMax(value: Long) {
            while (true) {
                val current = get()
                if (value <= current || compareAndSet(current, value)) return
            }
        }
    }
}