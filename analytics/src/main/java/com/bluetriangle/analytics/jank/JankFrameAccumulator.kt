package com.bluetriangle.analytics.jank

import com.bluetriangle.analytics.Constants
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
            isJank -> jank.record(durationNanos)
        }
    }

    fun snapshot(): JankMetrics {
        return JankMetrics(
            totalFrames = totalFrames.get(),
            jankFrameCount = jank.count.get(),
            totalJankDurationMs = jank.totalDurationNanos.get().nanosToMs(),
            longestJankMs = jank.longestDurationNanos.get().nanosToMs(),
            hangFrameCount = hang.count.get(),
            totalHangDurationMs = hang.totalDurationNanos.get().nanosToMs(),
            longestHangMs = hang.longestDurationNanos.get().nanosToMs()
        )
    }

    fun reset() {
        totalFrames.set(0)
        jank.reset()
        hang.reset()
        startTimeMs = nowMs()
    }

    private fun timeRatio(bucket: Bucket, elapsedMs: Long): Double {
        if (elapsedMs == 0L) return 0.0
        val durationMs = bucket.totalDurationNanos.get() / 1_000_000.0
        return (durationMs / elapsedMs).coerceAtMost(1.0).roundTo4Decimals()
    }

    private companion object {
        const val HANG_THRESHOLD_NANOS = Constants.HANG_THRESHOLD_MS * 1_000_000L

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