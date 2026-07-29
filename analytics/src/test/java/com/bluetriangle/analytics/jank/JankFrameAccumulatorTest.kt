package com.bluetriangle.analytics.jank

import org.junit.Assert.assertEquals
import org.junit.Test

class JankFrameAccumulatorTest {

    private var currentTimeMs = 0L
    private val accumulator = JankFrameAccumulator(nowMs = { currentTimeMs })

    private fun ms(millis: Long): Long = millis * 1_000_000L

    @Test
    fun `snapshot with no frames recorded returns all zeros`() {
        val snapshot = accumulator.snapshot()
        assertEquals(0L, snapshot.totalFrames)
        assertEquals(0L, snapshot.jankFrameCount)
        assertEquals(0L, snapshot.totalJankDurationMs)
        assertEquals(0.0, snapshot.jankTimeRatio, 0.0)
        assertEquals(0L, snapshot.longestJankMs)
        assertEquals(0L, snapshot.jankFrameCount)
        assertEquals(0L, snapshot.totalJankDurationMs)
        assertEquals(0.0, snapshot.hitchTimeRatio, 0.0)
        assertEquals(0L, snapshot.longestJankMs)
        assertEquals(0L, snapshot.hangFrameCount)
        assertEquals(0L, snapshot.totalHangDurationMs)
        assertEquals(0.0, snapshot.hangTimeRatio, 0.0)
        assertEquals(0L, snapshot.longestHangMs)
    }

    @Test
    fun `frames are classified into mutually exclusive jank, hitch and hang buckets`() {
        accumulator.recordFrame(isJank = false, frameDurationNanos = ms(8), frameBudgetNanos = 16_666_666L)    // normal
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(40), frameBudgetNanos = 16_666_666L)    // jank
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(60), frameBudgetNanos = 16_666_666L)    // jank
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(120), frameBudgetNanos = 16_666_666L)   // hitch
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(300), frameBudgetNanos = 16_666_666L)   // hang

        currentTimeMs = 1000L // 1 second visible

        val snapshot = accumulator.snapshot()
        assertEquals(5L, snapshot.totalFrames)

        assertEquals(2L, snapshot.jankFrameCount)
        assertEquals(100L, snapshot.totalJankDurationMs)
        assertEquals(60L, snapshot.longestJankMs)
        assertEquals(0.1, snapshot.jankTimeRatio, 0.0001)

        assertEquals(1L, snapshot.jankFrameCount)
        assertEquals(120L, snapshot.totalJankDurationMs)
        assertEquals(120L, snapshot.longestJankMs)
        assertEquals(0.12, snapshot.hitchTimeRatio, 0.0001)

        assertEquals(1L, snapshot.hangFrameCount)
        assertEquals(300L, snapshot.totalHangDurationMs)
        assertEquals(300L, snapshot.longestHangMs)
        assertEquals(0.3, snapshot.hangTimeRatio, 0.0001)
    }

    @Test
    fun `hang frame does not also count as jank or hitch`() {
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(500), frameBudgetNanos = 16_666_666L)
        currentTimeMs = 1000L

        val snapshot = accumulator.snapshot()
        assertEquals(1L, snapshot.totalFrames)
        assertEquals(0L, snapshot.jankFrameCount)
        assertEquals(0L, snapshot.jankFrameCount)
        assertEquals(1L, snapshot.hangFrameCount)
    }

    @Test
    fun `bucket boundaries are inclusive at 100ms hitch and 250ms hang`() {
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(99), frameBudgetNanos = 16_666_666L)   // jank (below hitch bound)
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(100), frameBudgetNanos = 16_666_666L)  // hitch (inclusive)
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(249), frameBudgetNanos = 16_666_666L)  // hitch
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(250), frameBudgetNanos = 16_666_666L)  // hang (inclusive)
        currentTimeMs = 1000L

        val snapshot = accumulator.snapshot()
        assertEquals(1L, snapshot.jankFrameCount)
        assertEquals(2L, snapshot.jankFrameCount)
        assertEquals(1L, snapshot.hangFrameCount)
        assertEquals(249L, snapshot.longestJankMs)
        assertEquals(250L, snapshot.longestHangMs)
    }

    @Test
    fun `hitch and hang classification is duration based even when isJank is false`() {
        accumulator.recordFrame(isJank = false, frameDurationNanos = ms(150), frameBudgetNanos = 16_666_666L)
        accumulator.recordFrame(isJank = false, frameDurationNanos = ms(400), frameBudgetNanos = 16_666_666L)
        currentTimeMs = 1000L

        val snapshot = accumulator.snapshot()
        assertEquals(0L, snapshot.jankFrameCount)
        assertEquals(1L, snapshot.jankFrameCount)
        assertEquals(1L, snapshot.hangFrameCount)
    }

    @Test
    fun `non jank frame below thresholds lands in no bucket`() {
        accumulator.recordFrame(isJank = false, frameDurationNanos = ms(50), frameBudgetNanos = 16_666_666L)
        currentTimeMs = 1000L

        val snapshot = accumulator.snapshot()
        assertEquals(1L, snapshot.totalFrames)
    }

    @Test
    fun `longest tracks the maximum frame duration per bucket`() {
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(60), frameBudgetNanos = 16_666_666L)
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(40), frameBudgetNanos = 16_666_666L)
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(55), frameBudgetNanos = 16_666_666L)
        currentTimeMs = 1000L

        assertEquals(60L, accumulator.snapshot().longestJankMs)
    }

    @Test
    fun `time ratios are coerced to at most 1_0`() {
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(5000), frameBudgetNanos = 16_666_666L) // hang
        currentTimeMs = 10L // hang time (5000ms) far exceeds elapsed time (10ms)

        assertEquals(1.0, accumulator.snapshot().hangTimeRatio, 0.0)
    }

    @Test
    fun `time ratios keep four decimals of precision`() {
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(40), frameBudgetNanos = 16_666_666L) // jank
        currentTimeMs = 10_000L // 0.4% of a 10s window

        assertEquals(0.004, accumulator.snapshot().jankTimeRatio, 0.0)
    }

    @Test
    fun `reset zeroes counters and restarts elapsed time baseline`() {
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(40), frameBudgetNanos = 16_666_666L)
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(120), frameBudgetNanos = 16_666_666L)
        accumulator.recordFrame(isJank = true, frameDurationNanos = ms(300), frameBudgetNanos = 16_666_666L)
        currentTimeMs = 500L

        accumulator.reset()

        val snapshot = accumulator.snapshot()
        assertEquals(0L, snapshot.totalFrames)
        assertEquals(0L, snapshot.jankFrameCount)
        assertEquals(0L, snapshot.jankFrameCount)
        assertEquals(0L, snapshot.hangFrameCount)
        assertEquals(0L, snapshot.longestJankMs)
        assertEquals(0L, snapshot.longestJankMs)
        assertEquals(0L, snapshot.longestHangMs)
        assertEquals(0.0, snapshot.jankTimeRatio, 0.0)
        assertEquals(0.0, snapshot.hitchTimeRatio, 0.0)
        assertEquals(0.0, snapshot.hangTimeRatio, 0.0)
    }
}
