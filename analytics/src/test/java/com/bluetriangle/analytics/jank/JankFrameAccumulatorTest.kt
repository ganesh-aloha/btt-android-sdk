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
        assertEquals("[]", snapshot.jankHistogram)
    }

    /** Records a hitch with an exact overrun, which is what the histogram bins on. */
    private fun JankFrameAccumulator.hitch(overrunMs: Long) = recordFrame(
        isJank = true,
        frameDurationNanos = ms(overrunMs) + budget,
        frameBudgetNanos = budget,
        frameOverrunNanos = ms(overrunMs)
    )

    @Test
    fun `hitches land in the first bin their overrun fits, bounds inclusive`() {
        accumulator.hitch(overrunMs = 0)
        accumulator.hitch(overrunMs = 50)     // on the first bound
        accumulator.hitch(overrunMs = 51)
        accumulator.hitch(overrunMs = 150)    // on the second bound
        accumulator.hitch(overrunMs = 300)
        accumulator.hitch(overrunMs = 301)
        accumulator.hitch(overrunMs = 749)    // last hitch before the hang threshold

        assertEquals(
            "[{50, 2}, {150, 2}, {300, 1}, {450, 1}, {750, 1}]",
            accumulator.snapshot().jankHistogram
        )
    }

    @Test
    fun `snapshot histogram is the flattened string with empty bins omitted`() {
        repeat(5) { accumulator.hitch(overrunMs = 30) }
        repeat(2) { accumulator.hitch(overrunMs = 120) }
        repeat(4) { accumulator.hitch(overrunMs = 250) }
        accumulator.hitch(overrunMs = 440)
        // nothing in the 450-750 bin, so it must not appear

        assertEquals(
            "[{50, 5}, {150, 2}, {300, 4}, {450, 1}]",
            accumulator.snapshot().jankHistogram
        )
    }

    @Test
    fun `histogram keeps the gaps between populated bins closed`() {
        accumulator.hitch(overrunMs = 30)
        accumulator.hitch(overrunMs = 700)

        assertEquals("[{50, 1}, {750, 1}]", accumulator.snapshot().jankHistogram)
    }

    @Test
    fun `histogram is empty brackets when no hitches were recorded`() {
        accumulator.record(isJank = false, frameDurationNanos = ms(8), frameBudgetNanos = budget)

        assertEquals("[]", accumulator.snapshot().jankHistogram)
    }

    @Test
    fun `histogram counts only hitches, excluding hangs and normal frames`() {
        accumulator.hitch(overrunMs = 20)
        accumulator.hitch(overrunMs = 400)
        accumulator.record(isJank = true, frameDurationNanos = ms(900), frameBudgetNanos = budget)  // hang
        accumulator.record(isJank = false, frameDurationNanos = ms(8), frameBudgetNanos = budget)   // normal

        val snapshot = accumulator.snapshot()
        assertEquals(2L, snapshot.jankFrameCount)
        assertEquals(1L, snapshot.hangFrameCount)
        // counts sum to jankFrameCount - the hang and the normal frame are absent
        assertEquals("[{50, 1}, {450, 1}]", snapshot.jankHistogram)
    }

    @Test
    fun `hitch severity is zero when no hitches were recorded`() {
        accumulator.record(isJank = false, frameDurationNanos = ms(8), frameBudgetNanos = budget)
        accumulator.record(isJank = true, frameDurationNanos = ms(900), frameBudgetNanos = budget)  // hang

        assertEquals(0.0, accumulator.snapshot().jankSeverity, 0.0)
    }

    @Test
    fun `hitch severity of a single hitch is its own bin weight`() {
        accumulator.hitch(overrunMs = 30)
        assertEquals(0.5, accumulator.snapshot().jankSeverity, 0.0)

        accumulator.reset()

        accumulator.hitch(overrunMs = 700)
        assertEquals(4.0, accumulator.snapshot().jankSeverity, 0.0)
    }

    @Test
    fun `hitch severity is the weighted total of the populated bins`() {
        repeat(3) { accumulator.hitch(overrunMs = 30) }    // weight 0.5
        accumulator.hitch(overrunMs = 250)                // weight 2.0
        accumulator.hitch(overrunMs = 440)                // weight 3.0

        // 3*0.5 + 2.0 + 3.0, not divided by the 5 hitches
        assertEquals(6.5, accumulator.snapshot().jankSeverity, 0.0)
    }

    @Test
    fun `hitch severity grows with volume, not just with how bad each hitch was`() {
        repeat(2) { accumulator.hitch(overrunMs = 30) }
        accumulator.hitch(overrunMs = 250)
        val few = accumulator.snapshot().jankSeverity

        accumulator.reset()

        repeat(200) { accumulator.hitch(overrunMs = 30) }
        repeat(100) { accumulator.hitch(overrunMs = 250) }
        val many = accumulator.snapshot().jankSeverity

        assertEquals(3.0, few, 0.0)     // 2*0.5 + 2.0
        assertEquals(300.0, many, 0.0)  // 200*0.5 + 100*2.0
    }

    @Test
    fun `reset clears hitch severity`() {
        accumulator.hitch(overrunMs = 700)
        accumulator.reset()

        assertEquals(0.0, accumulator.snapshot().jankSeverity, 0.0)
    }
}
