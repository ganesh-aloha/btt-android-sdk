package com.bluetriangle.analytics.jank

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scores a screen's frame health as a single **badness** number, 0 (smooth) to 100 (worst), from its
 * jank severity and its hangs.
 *
 * Mirrors iOS's `ResponsivenessGrade` - the ramps, weights and caps here must stay in step with it
 * or the same screen grades differently per platform.
 *
 * Pure Kotlin, no Android dependencies, so it stays unit-testable without Robolectric.
 */
internal object ResponsivenessGrade {

    /** Neither jank nor hangs can reach a full 100 alone - see [grade]. */
    private const val SINGLE_SIGNAL_CAP = 90f

    /**
     * The screen's overall badness, 0 (best) to 100 (worst).
     *
     * Because both inputs are capped at [SINGLE_SIGNAL_CAP], the reachable maximum is 98 - a screen
     * that is maximally bad on jank *and* hangs. 99 and 100 are unreachable by construction.
     */
    fun grade(jankSeverity: Double, hangCount: Long, longestHangMs: Long): Int {
        val jank = jankScore(jankSeverity)
        val hang = hangScore(hangCount, longestHangMs)
        return combine(jank, hang).roundToInt().coerceIn(0, 100)
    }

    /**
     * Maps one raw measurement onto a 0-100 ramp with three linear segments, so that the interesting
     * range gets most of the resolution: `0..good` spends the first 30 points, `good..bad` the next
     * 40, and `bad..cap` the last 30. Anything past [cap] is a flat 100.
     */
    private fun severity(value: Float, good: Float, bad: Float, cap: Float): Float {
        if (value <= 0f) return 0f
        if (value <= good) return (value / good) * 30f
        if (value <= bad) {
            val t = (value - good) / (bad - good)
            return 30f + t * 40f
        }
        if (value <= cap) {
            val t = (value - bad) / (cap - bad)
            return 70f + t * 30f
        }
        return 100f
    }

    /**
     * Merges two badness scores so that two bad signals escalate past either one alone, without a
     * plain sum's runaway: the worse score sets the floor, and the better one adds a fraction of
     * itself scaled by how bad *both* already are. A mild secondary signal adds nearly its whole
     * self, a severe one adds little (it is already reflected in the floor).
     */
    private fun combine(a: Float, b: Float): Float {
        val worse = max(a, b)
        val better = min(a, b)
        val weight = 1f - (better / 100f)
        return min(worse + weight * better * (worse / 100f), 100f)
    }

    /**
     * Scored straight off [JankMetrics.jankSeverity] - the jank-duration histogram's weighted total,
     * where longer-hitch bins weigh more - so it needs no ramp of its own. Capped so jank alone can
     * never read as a full 100; only [combine] escalating jank *and* hangs together gets near it.
     */
    private fun jankScore(jankSeverity: Double): Float = min(jankSeverity.toFloat(), SINGLE_SIGNAL_CAP)

    /**
     * Worst-of the two hang signals, so a screen is graded on whichever is more damning: a handful
     * of hangs at all, or one very long one. They do not compound with each other - [combine] is
     * only used between jank and hangs.
     *
     * Capped for the same reason as [jankScore].
     */
    private fun hangScore(hangCount: Long, longestHangMs: Long): Float {
        val countScore = severity(hangCount.toFloat(), good = 2f, bad = 5f, cap = 100f)
        val durationScore =
            severity(longestHangMs.toFloat(), good = 1500f, bad = 2500f, cap = 100_000f)
        return min(max(countScore, durationScore), SINGLE_SIGNAL_CAP)
    }
}