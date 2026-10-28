package com.bluetriangle.analytics.jank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the grading ramps, caps and escalation so a change here has to be a deliberate one - the same
 * numbers live in iOS's `ResponsivenessGrade` and the two are only comparable while they agree.
 */
class ResponsivenessGradeTest {

    private fun grade(severity: Double = 0.0, hangs: Long = 0, longestHangMs: Long = 0) =
        ResponsivenessGrade.grade(severity, hangs, longestHangMs)

    @Test
    fun `a screen with no jank and no hangs grades 0`() {
        assertEquals(0, grade())
    }

    @Test
    fun `jank severity is its own score, so it passes through unramped`() {
        assertEquals(7, grade(severity = 6.5))
        assertEquals(30, grade(severity = 30.0))
    }

    @Test
    fun `jank alone cannot reach 100 - it caps at 90`() {
        assertEquals(90, grade(severity = 90.0))
        assertEquals(90, grade(severity = 5_000.0))
    }

    @Test
    fun `hang count ramps 30 at good, 70 at bad, and caps at 90`() {
        assertEquals(30, grade(hangs = 2))    // good
        assertEquals(70, grade(hangs = 5))    // bad
        assertEquals(90, grade(hangs = 100))  // ramp hits 100 at the cap, clamped to 90
        assertEquals(90, grade(hangs = 4_000))
    }

    @Test
    fun `hang count below good scales linearly into the first 30 points`() {
        assertEquals(15, grade(hangs = 1))
    }

    @Test
    fun `longest hang ramps 30 at 1500ms, 70 at 2500ms, and caps at 90`() {
        assertEquals(30, grade(longestHangMs = 1_500))
        assertEquals(70, grade(longestHangMs = 2_500))
        assertEquals(90, grade(longestHangMs = 100_000))
        assertEquals(90, grade(longestHangMs = 600_000))
    }

    @Test
    fun `the two hang signals do not compound - the worse one wins`() {
        val manyShort = grade(hangs = 5, longestHangMs = 800)      // count 70, duration ~16
        val oneLong = grade(hangs = 1, longestHangMs = 2_500)      // count 15, duration 70

        assertEquals(70, manyShort)
        assertEquals(70, oneLong)
        // both signals at once still scores the worse of the two, not their sum
        assertEquals(70, grade(hangs = 5, longestHangMs = 2_500))
    }

    @Test
    fun `jank and hangs together escalate past either one alone`() {
        val jankOnly = grade(severity = 40.0)
        val hangOnly = grade(hangs = 5)
        val both = grade(severity = 40.0, hangs = 5)

        assertEquals(40, jankOnly)
        assertEquals(70, hangOnly)
        // worse 70 + (1 - 40/100) * 40 * (70/100) = 70 + 16.8
        assertEquals(87, both)
        assertTrue(both > jankOnly && both > hangOnly)
    }

    @Test
    fun `a mild second signal adds nearly all of itself, a severe one adds little`() {
        // secondary 5 on top of 70: 70 + 0.95 * 5 * 0.7 = 73.3
        assertEquals(73, grade(severity = 5.0, hangs = 5))
        // secondary 70 on top of 70: 70 + 0.3 * 70 * 0.7 = 84.7
        assertEquals(85, grade(severity = 70.0, hangs = 5))
    }

    @Test
    fun `the worst reachable grade is 98, since both inputs cap at 90`() {
        // 90 + (1 - 90/100) * 90 * (90/100) = 98.1
        assertEquals(98, grade(severity = 10_000.0, hangs = 10_000, longestHangMs = 600_000))
    }

    @Test
    fun `grade reads the metrics snapshot's own jank and hang fields`() {
        val metrics = JankMetrics(
            totalFrames = 600,
            jankFrameCount = 12,
            totalJankDurationMs = 400,
            longestJankMs = 90,
            hangFrameCount = 5,
            totalHangDurationMs = 4_000,
            longestHangMs = 2_500,
            jankHistogram = "[{50, 8}, {150, 4}]",
            jankSeverity = 8.0
        )

        assertEquals(
            ResponsivenessGrade.grade(
                jankSeverity = 8.0,
                hangCount = 5,
                longestHangMs = 2_500
            ),
            metrics.responsivenessGrade
        )
    }
}
