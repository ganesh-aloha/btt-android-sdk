package com.bluetriangle.analytics.jank

import org.junit.Assert.assertEquals
import org.junit.Test

class JankFrameAccumulatorTest {

    private val accumulator = JankFrameAccumulator()

    /** 60Hz frame budget. */
    private val budget = 16_666_666L

    private fun ms(millis: Long): Long = millis * 1_000_000L

    /**
     * Frames carry their own overrun, which is what the buckets measure. These cases describe a
     * frame by duration/budget, so derive the overrun from them.
     */
    private fun JankFrameAccumulator.record(
        isJank: Boolean,
        frameDurationNanos: Long,
        frameBudgetNanos: Long
    ) = recordFrame(isJank, frameDurationNanos, frameBudgetNanos, frameDurationNanos - frameBudgetNanos)

    @Test
    fun `snapshot with no frames recorded returns all zeros`() {
        val snapshot = accumulator.snapshot()
        assertEquals(0L, snapshot.totalFrames)
        assertEquals(0L, snapshot.jankFrameCount)
        assertEquals(0L, snapshot.totalJankDurationMs)
        assertEquals(0L, snapshot.longestJankMs)
        assertEquals(0L, snapshot.hangFrameCount)
        assertEquals(0L, snapshot.totalHangDurationMs)
        assertEquals(0L, snapshot.longestHangMs)
    }

    @Test
    fun `frames are classified into jank and hang buckets by excess over budget`() {
        accumulator.record(isJank = false, frameDurationNanos = ms(8), frameBudgetNanos = budget)     // normal
        accumulator.record(isJank = true, frameDurationNanos = ms(40), frameBudgetNanos = budget)     // jank
        accumulator.record(isJank = true, frameDurationNanos = ms(60), frameBudgetNanos = budget)     // jank
        accumulator.record(isJank = true, frameDurationNanos = ms(800), frameBudgetNanos = budget)    // hang (excess ~783ms)

        val snapshot = accumulator.snapshot()
        assertEquals(4L, snapshot.totalFrames)

        // Bucket durations are the excess over budget, not the full frame duration
        val jankExcess1 = ms(40) - budget
        val jankExcess2 = ms(60) - budget
        assertEquals(2L, snapshot.jankFrameCount)
        assertEquals((jankExcess1 + jankExcess2) / 1_000_000L, snapshot.totalJankDurationMs)
        assertEquals(jankExcess2 / 1_000_000L, snapshot.longestJankMs)

        val hangExcess = ms(800) - budget
        assertEquals(1L, snapshot.hangFrameCount)
        assertEquals(hangExcess / 1_000_000L, snapshot.totalHangDurationMs)
        assertEquals(hangExcess / 1_000_000L, snapshot.longestHangMs)
    }

    @Test
    fun `hang frame does not also count as jank`() {
        accumulator.record(isJank = true, frameDurationNanos = ms(1000), frameBudgetNanos = budget)

        val snapshot = accumulator.snapshot()
        assertEquals(1L, snapshot.totalFrames)
        assertEquals(0L, snapshot.jankFrameCount)
        assertEquals(1L, snapshot.hangFrameCount)
    }

    @Test
    fun `hang boundary at 750ms excess is inclusive`() {
        // excess exactly at threshold -> hang
        accumulator.record(isJank = true, frameDurationNanos = ms(750) + budget, frameBudgetNanos = budget)
        // excess just below threshold -> jank
        accumulator.record(isJank = true, frameDurationNanos = ms(749) + budget, frameBudgetNanos = budget)

        val snapshot = accumulator.snapshot()
        assertEquals(1L, snapshot.hangFrameCount)
        assertEquals(750L, snapshot.longestHangMs)
        assertEquals(1L, snapshot.jankFrameCount)
        assertEquals(749L, snapshot.longestJankMs)
    }

    @Test
    fun `hang classification is excess based even when isJank is false`() {
        accumulator.record(isJank = false, frameDurationNanos = ms(900), frameBudgetNanos = budget)

        val snapshot = accumulator.snapshot()
        assertEquals(0L, snapshot.jankFrameCount)
        assertEquals(1L, snapshot.hangFrameCount)
    }

    @Test
    fun `non jank frame below hang threshold lands in no bucket`() {
        accumulator.record(isJank = false, frameDurationNanos = ms(50), frameBudgetNanos = budget)

        val snapshot = accumulator.snapshot()
        assertEquals(1L, snapshot.totalFrames)
        assertEquals(0L, snapshot.jankFrameCount)
        assertEquals(0L, snapshot.hangFrameCount)
    }

    @Test
    fun `jank frame faster than budget records zero excess`() {
        accumulator.record(isJank = true, frameDurationNanos = ms(10), frameBudgetNanos = budget)

        val snapshot = accumulator.snapshot()
        assertEquals(1L, snapshot.jankFrameCount)
        assertEquals(0L, snapshot.totalJankDurationMs)
        assertEquals(0L, snapshot.longestJankMs)
    }

    @Test
    fun `longest tracks the maximum excess per bucket`() {
        accumulator.record(isJank = true, frameDurationNanos = ms(60), frameBudgetNanos = budget)
        accumulator.record(isJank = true, frameDurationNanos = ms(40), frameBudgetNanos = budget)
        accumulator.record(isJank = true, frameDurationNanos = ms(55), frameBudgetNanos = budget)

        assertEquals((ms(60) - budget) / 1_000_000L, accumulator.snapshot().longestJankMs)
    }

    @Test
    fun `reset zeroes all counters`() {
        accumulator.record(isJank = true, frameDurationNanos = ms(40), frameBudgetNanos = budget)
        accumulator.record(isJank = true, frameDurationNanos = ms(900), frameBudgetNanos = budget)

        accumulator.reset()

        val snapshot = accumulator.snapshot()
        assertEquals(0L, snapshot.totalFrames)
        assertEquals(0L, snapshot.jankFrameCount)
        assertEquals(0L, snapshot.totalJankDurationMs)
        assertEquals(0L, snapshot.longestJankMs)
        assertEquals(0L, snapshot.hangFrameCount)
        assertEquals(0L, snapshot.totalHangDurationMs)
        assertEquals(0L, snapshot.longestHangMs)
    }
}
