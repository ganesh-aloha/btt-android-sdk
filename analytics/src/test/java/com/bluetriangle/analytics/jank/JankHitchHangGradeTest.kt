package com.bluetriangle.analytics.jank

import com.bluetriangle.analytics.Constants
import com.bluetriangle.analytics.Timer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Hitch/hang cases where the bad frames arrive in **several separate windows** of a single screen's
 * visible time (a burst while a list loads, another on a scroll, another on a dialog), rather than
 * all at once. The accumulator keeps no per-window state, so every window folds into one per-screen
 * total, and the beacon's `hitchTimePercent` is that total spread over the whole time on screen -
 * these cases pin that behaviour down and then grade the resulting beacon.
 *
 * Grade (worst dimension wins):
 *
 * | Grade | hitchTimePercent (ms/s) | Hang count | Longest hang     |
 * |-------|-----------------------|------------|------------------|
 * | Good  | < 5                   | 0          | -                |
 * | Bad   | 5 - 10                | 1          | <= 2500ms        |
 * | Worst | > 10                  | >= 2       | any hang > 2500ms|
 *
 * The grade itself is derived here, in the test, from the same three beacon fields the portal reads
 * ([Constants.JANK_TIME_PERCENT], [Constants.HANG_COUNT], [Constants.LONGEST_HANG_DURATION]) - the SDK
 * only ships the numbers.
 */
@RunWith(RobolectricTestRunner::class)
class JankHitchHangGradeTest {

    private enum class Grade { GOOD, BAD, WORST }

    /**
     * One stretch of a screen's visible time.
     *
     * @param hitchesMs overruns (ms) of the janky frames in this window - each must stay under the
     * hang threshold to land in the hitch bucket.
     * @param hangsMs overruns (ms) of the hang frames in this window.
     * @param healthyFrames frames that met their budget.
     */
    private data class Window(
        val hitchesMs: List<Long> = emptyList(),
        val hangsMs: List<Long> = emptyList(),
        val healthyFrames: Int = 60
    )

    /** 60Hz frame budget. */
    private val budgetNanos = 16_666_666L

    private fun JankScreenAccumulator.frame(isJank: Boolean, overrunMs: Long) =
        recordFrame(
            isJank = isJank,
            frameDurationNanos = budgetNanos + overrunMs * 1_000_000L,
            frameBudgetNanos = budgetNanos,
            frameOverrunNanos = overrunMs * 1_000_000L
        )

    /** Plays [windows] against one visible screen and returns its final metrics. */
    private fun metricsFor(vararg windows: Window): JankMetrics {
        val registry = JankScreenAccumulator()
        registry.onScreenVisible(SCREEN, isContent = true)
        windows.forEach { window ->
            repeat(window.healthyFrames) { registry.frame(isJank = false, overrunMs = 0) }
            window.hitchesMs.forEach { registry.frame(isJank = true, overrunMs = it) }
            window.hangsMs.forEach { registry.frame(isJank = true, overrunMs = it) }
        }
        return registry.onScreenHidden(SCREEN)!!
    }

    /** Stamps [metrics] on a timer that was on screen for [screenTimeMs] and serializes the beacon. */
    private fun beacon(metrics: JankMetrics, screenTimeMs: Long): JSONObject {
        val timer = Timer("Page", "Segment")
        timer.setJankReportFields(metrics)
        timer.nativeAppProperties.fullTime = screenTimeMs
        return timer.nativeAppProperties.toJSONObject()
    }

    private fun beaconFor(screenTimeMs: Long, vararg windows: Window): JSONObject =
        beacon(metricsFor(*windows), screenTimeMs)

    /** Grades a beacon the way the portal does: worst of the three dimensions wins. */
    private fun gradeOf(json: JSONObject): Grade {
        val hitchTimePercent = json.getDouble(Constants.JANK_TIME_PERCENT)
        val hangCount = json.getLong(Constants.HANG_COUNT)
        val longestHangMs = json.getLong(Constants.LONGEST_HANG_DURATION)

        val hitchGrade = when {
            hitchTimePercent > 10.0 -> Grade.WORST
            hitchTimePercent >= 5.0 -> Grade.BAD
            else -> Grade.GOOD
        }
        val hangCountGrade = when {
            hangCount >= 2L -> Grade.WORST
            hangCount == 1L -> Grade.BAD
            else -> Grade.GOOD
        }
        val longestHangGrade = when {
            longestHangMs > 2500L -> Grade.WORST
            longestHangMs > 0L -> Grade.BAD
            else -> Grade.GOOD
        }
        return maxOf(hitchGrade, hangCountGrade, longestHangGrade)
    }

    // region accumulation across windows

    @Test
    fun `hitches from every window fold into one per-screen total`() {
        val metrics = metricsFor(
            Window(hitchesMs = listOf(30, 20)),   // window 1: 50ms over 2 frames
            Window(healthyFrames = 120),           // window 2: quiet stretch
            Window(hitchesMs = listOf(10)),        // window 3: 10ms
            Window(hitchesMs = listOf(40, 25))     // window 4: 65ms
        )

        assertEquals(5L, metrics.jankFrameCount)
        assertEquals(125L, metrics.totalJankDurationMs)
        assertEquals(40L, metrics.longestJankMs)
        assertEquals(0L, metrics.hangFrameCount)
        assertEquals(305L, metrics.totalFrames) // 300 healthy + 5 hitches
    }

    @Test
    fun `hangs in separate windows each count, and the longest wins`() {
        val metrics = metricsFor(
            Window(hitchesMs = listOf(20), hangsMs = listOf(900)),
            Window(hangsMs = listOf(1800)),
            Window(hangsMs = listOf(1200))
        )

        assertEquals(3L, metrics.hangFrameCount)
        assertEquals(3900L, metrics.totalHangDurationMs)
        assertEquals(1800L, metrics.longestHangMs)
        // The hang frames stay out of the hitch bucket even though they were reported as jank
        assertEquals(1L, metrics.jankFrameCount)
        assertEquals(20L, metrics.totalJankDurationMs)
    }

    @Test
    fun `hitchTimePercent spreads all windows over the whole time on screen`() {
        // 3 windows, 120ms of hitches in total, 20s on screen -> 6 ms/s
        val json = beaconFor(
            screenTimeMs = 20_000,
            Window(hitchesMs = listOf(40, 20)),
            Window(hitchesMs = listOf(30)),
            Window(hitchesMs = listOf(20, 10))
        )

        assertEquals(5L, json.getLong(Constants.JANK_FRAME_COUNT))
        assertEquals(120L, json.getLong(Constants.TOTAL_JANK_DURATION))
        assertEquals(40L, json.getLong(Constants.JANK_FRAME_PERCENT))
        assertEquals(6.0, json.getDouble(Constants.JANK_TIME_PERCENT), 0.0)
    }

    @Test
    fun `windows from a previous visit to the same screen do not leak into the next visit`() {
        val registry = JankScreenAccumulator()

        registry.onScreenVisible(SCREEN, isContent = true)
        registry.frame(isJank = true, overrunMs = 200)
        registry.frame(isJank = true, overrunMs = 3000)
        val firstVisit = registry.onScreenHidden(SCREEN)!!

        registry.onScreenVisible(SCREEN, isContent = true)
        registry.frame(isJank = true, overrunMs = 20)
        val secondVisit = registry.onScreenHidden(SCREEN)!!

        assertEquals(Grade.WORST, gradeOf(beacon(firstVisit, screenTimeMs = 10_000)))
        assertEquals(200L, firstVisit.totalJankDurationMs)

        assertEquals(20L, secondVisit.totalJankDurationMs)
        assertEquals(0L, secondVisit.hangFrameCount)
        assertEquals(Grade.GOOD, gradeOf(beacon(secondVisit, screenTimeMs = 10_000)))
    }

    // endregion

    // region Good

    @Test
    fun `hitches in several windows still grade Good while the ratio stays under 5`() {
        // 40ms of hitches over 3 windows, 10s on screen -> 4 ms/s, no hangs
        val json = beaconFor(
            screenTimeMs = 10_000,
            Window(hitchesMs = listOf(10, 10)),
            Window(hitchesMs = listOf(8)),
            Window(hitchesMs = listOf(12))
        )

        assertEquals(4.0, json.getDouble(Constants.JANK_TIME_PERCENT), 0.0)
        assertEquals(0L, json.getLong(Constants.HANG_COUNT))
        assertEquals(0L, json.getLong(Constants.LONGEST_HANG_DURATION))
        assertEquals(Grade.GOOD, gradeOf(json))
    }

    @Test
    fun `a long visit dilutes the same hitch windows down to Good`() {
        val windows = arrayOf(
            Window(hitchesMs = listOf(60, 40)),
            Window(hitchesMs = listOf(50)),
            Window(hitchesMs = listOf(50))
        )
        // 200ms of hitches: harsh over 20s (10 ms/s), harmless over 60s (3.33 ms/s)
        assertEquals(Grade.BAD, gradeOf(beaconFor(20_000, *windows)))

        val json = beaconFor(60_000, *windows)
        assertEquals(3.33, json.getDouble(Constants.JANK_TIME_PERCENT), 0.0)
        assertEquals(Grade.GOOD, gradeOf(json))
    }

    // endregion

    // region Bad

    @Test
    fun `hitch windows adding up to a ratio between 5 and 10 grade Bad`() {
        // 70ms of hitches over 3 windows, 10s on screen -> 7 ms/s
        val json = beaconFor(
            screenTimeMs = 10_000,
            Window(hitchesMs = listOf(30, 10)),
            Window(hitchesMs = listOf(20)),
            Window(hitchesMs = listOf(10))
        )

        assertEquals(7.0, json.getDouble(Constants.JANK_TIME_PERCENT), 0.0)
        assertEquals(0L, json.getLong(Constants.HANG_COUNT))
        assertEquals(Grade.BAD, gradeOf(json))
    }

    @Test
    fun `a single hang under 2500ms drags an otherwise Good screen to Bad`() {
        // hitch ratio 4 ms/s (Good), but one 2000ms hang in the last window
        val json = beaconFor(
            screenTimeMs = 10_000,
            Window(hitchesMs = listOf(20, 10)),
            Window(hitchesMs = listOf(10)),
            Window(hangsMs = listOf(2000))
        )

        assertEquals(4.0, json.getDouble(Constants.JANK_TIME_PERCENT), 0.0)
        assertEquals(1L, json.getLong(Constants.HANG_COUNT))
        assertEquals(2000L, json.getLong(Constants.LONGEST_HANG_DURATION))
        assertEquals(Grade.BAD, gradeOf(json))
    }

    @Test
    fun `ratio boundaries 5 and 10 both grade Bad`() {
        // 50ms / 10s -> exactly 5 ms/s
        val atFive = beaconFor(
            screenTimeMs = 10_000,
            Window(hitchesMs = listOf(30)),
            Window(hitchesMs = listOf(20))
        )
        assertEquals(5.0, atFive.getDouble(Constants.JANK_TIME_PERCENT), 0.0)
        assertEquals(Grade.BAD, gradeOf(atFive))

        // 100ms / 10s -> exactly 10 ms/s
        val atTen = beaconFor(
            screenTimeMs = 10_000,
            Window(hitchesMs = listOf(60)),
            Window(hitchesMs = listOf(40))
        )
        assertEquals(10.0, atTen.getDouble(Constants.JANK_TIME_PERCENT), 0.0)
        assertEquals(Grade.BAD, gradeOf(atTen))
    }

    @Test
    fun `a hang of exactly 2500ms is still Bad`() {
        val json = beaconFor(
            screenTimeMs = 20_000,
            Window(hitchesMs = listOf(10)),
            Window(hangsMs = listOf(2500))
        )

        assertEquals(2500L, json.getLong(Constants.LONGEST_HANG_DURATION))
        assertEquals(1L, json.getLong(Constants.HANG_COUNT))
        assertEquals(Grade.BAD, gradeOf(json))
    }

    // endregion

    // region Worst

    @Test
    fun `hitch windows pushing the ratio past 10 grade Worst`() {
        // 150ms of hitches over 3 windows, 10s on screen -> 15 ms/s
        val json = beaconFor(
            screenTimeMs = 10_000,
            Window(hitchesMs = listOf(60, 30)),
            Window(hitchesMs = listOf(40)),
            Window(hitchesMs = listOf(20))
        )

        assertEquals(15.0, json.getDouble(Constants.JANK_TIME_PERCENT), 0.0)
        assertEquals(Grade.WORST, gradeOf(json))
    }

    @Test
    fun `two hangs in two different windows grade Worst even when both are short`() {
        // hitch ratio 2 ms/s (Good), each hang well under 2500ms (Bad on its own) - the count decides
        val json = beaconFor(
            screenTimeMs = 20_000,
            Window(hitchesMs = listOf(20, 20), hangsMs = listOf(800)),
            Window(healthyFrames = 120),
            Window(hangsMs = listOf(1000))
        )

        assertEquals(2.0, json.getDouble(Constants.JANK_TIME_PERCENT), 0.0)
        assertEquals(2L, json.getLong(Constants.HANG_COUNT))
        assertEquals(1000L, json.getLong(Constants.LONGEST_HANG_DURATION))
        assertEquals(1800L, json.getLong(Constants.TOTAL_HANG_DURATION))
        assertEquals(Grade.WORST, gradeOf(json))
    }

    @Test
    fun `one hang over 2500ms grades Worst on its own`() {
        // hitch ratio 1 ms/s, a single hang -> both Good/Bad, but the 3000ms hang wins
        val json = beaconFor(
            screenTimeMs = 20_000,
            Window(hitchesMs = listOf(20)),
            Window(hangsMs = listOf(3000))
        )

        assertEquals(1.0, json.getDouble(Constants.JANK_TIME_PERCENT), 0.0)
        assertEquals(1L, json.getLong(Constants.HANG_COUNT))
        assertEquals(3000L, json.getLong(Constants.LONGEST_HANG_DURATION))
        assertEquals(Grade.WORST, gradeOf(json))
    }

    @Test
    fun `hitches and hangs across many windows compound into Worst`() {
        val json = beaconFor(
            screenTimeMs = 30_000,
            Window(hitchesMs = listOf(80, 60)),
            Window(hitchesMs = listOf(50), hangsMs = listOf(1500)),
            Window(hitchesMs = listOf(70, 90)),
            Window(hangsMs = listOf(2800))
        )

        // 350ms of hitches over 30s -> 11.67 ms/s (Worst), 2 hangs (Worst), longest 2800ms (Worst)
        assertEquals(11.67, json.getDouble(Constants.JANK_TIME_PERCENT), 0.0)
        assertEquals(5L, json.getLong(Constants.JANK_FRAME_COUNT))
        assertEquals(90L, json.getLong(Constants.JANK_FRAME_PERCENT))
        assertEquals(2L, json.getLong(Constants.HANG_COUNT))
        assertEquals(2800L, json.getLong(Constants.LONGEST_HANG_DURATION))
        assertEquals(4300L, json.getLong(Constants.TOTAL_HANG_DURATION))
        assertEquals(Grade.WORST, gradeOf(json))
    }

    // endregion

    @Test
    fun `each screen is graded on its own windows`() {
        val registry = JankScreenAccumulator()

        registry.onScreenVisible("ListScreen", isContent = true)
        registry.frame(isJank = true, overrunMs = 15)
        registry.frame(isJank = true, overrunMs = 10)
        val listMetrics = registry.onScreenHidden("ListScreen")!!

        registry.onScreenVisible("DetailScreen", isContent = true)
        registry.frame(isJank = true, overrunMs = 30)
        registry.frame(isJank = true, overrunMs = 2600)
        val detailMetrics = registry.onScreenHidden("DetailScreen")!!

        // 25ms over 10s -> 2.5 ms/s, no hangs
        assertEquals(Grade.GOOD, gradeOf(beacon(listMetrics, screenTimeMs = 10_000)))
        // 30ms over 10s is Good, but the 2600ms hang decides it
        assertEquals(Grade.WORST, gradeOf(beacon(detailMetrics, screenTimeMs = 10_000)))
    }

    private companion object {
        const val SCREEN = "HitchWindowScreen"
    }
}
