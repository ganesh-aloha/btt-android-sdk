package com.bluetriangle.analytics.jank

import com.bluetriangle.analytics.Constants
import com.bluetriangle.analytics.Timer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Hitch/hang cases where the bad frames arrive in **several separate windows** of a single screen's
 * visible time (a burst while a list loads, another on a scroll, another on a dialog), rather than
 * all at once. The accumulator keeps no per-window state, so every window folds into one per-screen
 * total - these cases pin that down and then grade the resulting beacon.
 *
 * The beacon ships frame health as two fields only: [Constants.RESPONSIVENESS_GRADE], a single
 * 0-98 badness score the SDK computes itself via [ResponsivenessGrade], and
 * [Constants.RESPONSIVENESS_META], the whole [JankMetrics] snapshot as a JSON string. The old
 * per-screen ratio fields (`hitchTimePercent`, `hangTimePercent`, ...) are gone, and with them the
 * old three-column Good/Bad/Worst table the portal derived from them - so time on screen no longer
 * enters the grade at all, and hitch **volume and severity** ([JankMetrics.jankSeverity], the
 * weighted histogram total) drive the hitch side instead of a duration ratio.
 *
 * Grade bands used below follow [ResponsivenessGrade]'s own ramp anchors, which place 30 at each
 * signal's "good" threshold and 70 at its "bad" one:
 *
 * | Band  | responsivenessGrade |
 * |-------|---------------------|
 * | Good  | < 30                |
 * | Bad   | 30 - 70             |
 * | Worst | > 70                |
 *
 * Neither signal alone can exceed 90, and only jank *and* hangs compounding get past 70, so a
 * single bad dimension now tops out in Bad where the old table would have called it Worst.
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
    private fun beacon(metrics: JankMetrics, screenTimeMs: Long = 10_000): JSONObject {
        val timer = Timer("Page", "Segment")
        timer.setJankReportFields(metrics)
        timer.nativeAppProperties.fullTime = screenTimeMs
        return timer.nativeAppProperties.toJSONObject()
    }

    private fun beaconFor(vararg windows: Window): JSONObject = beacon(metricsFor(*windows))

    /** The [Constants.RESPONSIVENESS_META] blob, parsed back from the string the beacon carries. */
    private fun JSONObject.meta() = JSONObject(getString(Constants.RESPONSIVENESS_META))

    private fun JSONObject.grade() = getInt(Constants.RESPONSIVENESS_GRADE)

    /** Bands a grade the way [ResponsivenessGrade]'s ramp anchors it - see the class doc. */
    private fun bandOf(grade: Int): Grade = when {
        grade > 70 -> Grade.WORST
        grade >= 30 -> Grade.BAD
        else -> Grade.GOOD
    }

    private fun bandOf(json: JSONObject): Grade = bandOf(json.grade())

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
        // every one of them fits the 50ms bin, so they share a single histogram entry
        assertEquals("[{50, 5}]", metrics.jankHistogram)
        assertEquals(1.25, metrics.jankSeverity, 0.0) // 5 * 0.25
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
        // The hang frames stay out of the hitch bucket and the histogram even though they were
        // reported as jank
        assertEquals(1L, metrics.jankFrameCount)
        assertEquals(20L, metrics.totalJankDurationMs)
        assertEquals("[{50, 1}]", metrics.jankHistogram)
        assertEquals(0.25, metrics.jankSeverity, 0.0)
    }

    @Test
    fun `responsivenessMeta carries the per-screen total, not one window`() {
        val json = beaconFor(
            Window(hitchesMs = listOf(40, 20)),
            Window(hitchesMs = listOf(30)),
            Window(hitchesMs = listOf(20, 10))
        )

        val meta = json.meta()
        assertEquals(185L, meta.getLong("totalFrameCount")) // 180 healthy + 5 hitches
        assertEquals(5L, meta.getLong("hitchCount"))
        assertEquals(120L, meta.getLong("totalHitchDuration"))
        assertEquals(40L, meta.getLong("longestHitch"))
        assertEquals("[{50, 5}]", meta.getString("hitchHistograms"))
        assertEquals(1.25, meta.getDouble("hitchesSeverity"), 0.0)
        assertEquals(0L, meta.getLong("hangCount"))
        assertEquals(0L, meta.getLong("totalHangDuration"))
        assertEquals(0L, meta.getLong("longestHang"))

        assertEquals(1, json.grade()) // 1.25 of hitch severity, no hangs
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

        // hitch severity 2.0 (the 300ms bin) compounding with a 3000ms hang's 70.15
        assertEquals(72, beacon(firstVisit).grade())
        assertEquals(Grade.WORST, bandOf(beacon(firstVisit)))
        assertEquals(200L, firstVisit.totalJankDurationMs)

        assertEquals(20L, secondVisit.totalJankDurationMs)
        assertEquals(0L, secondVisit.hangFrameCount)
        assertEquals(0, beacon(secondVisit).grade())
        assertEquals(Grade.GOOD, bandOf(beacon(secondVisit)))
    }

    @Test
    fun `time on screen no longer changes the grade or the meta`() {
        val metrics = metricsFor(
            Window(hitchesMs = listOf(60, 40)),
            Window(hitchesMs = listOf(50)),
            Window(hitchesMs = listOf(50))
        )
        // 200ms of hitches: the old beacon graded that harshly over 20s and harmlessly over 60s
        val shortVisit = beacon(metrics, screenTimeMs = 20_000)
        val longVisit = beacon(metrics, screenTimeMs = 60_000)

        assertEquals(2, shortVisit.grade()) // 3 * 0.25 + 0.75 of severity
        assertEquals(shortVisit.grade(), longVisit.grade())
        assertEquals(
            shortVisit.getString(Constants.RESPONSIVENESS_META),
            longVisit.getString(Constants.RESPONSIVENESS_META)
        )
    }

    // endregion

    // region Good

    @Test
    fun `mild hitches across several windows stay Good`() {
        val json = beaconFor(
            Window(hitchesMs = listOf(10, 10)),
            Window(hitchesMs = listOf(8)),
            Window(hitchesMs = listOf(12))
        )

        assertEquals(1.0, json.meta().getDouble("hitchesSeverity"), 0.0) // 4 * 0.25
        assertEquals(0L, json.meta().getLong("hangCount"))
        assertEquals(1, json.grade())
        assertEquals(Grade.GOOD, bandOf(json))
    }

    @Test
    fun `mild hitches climb out of Good on volume alone`() {
        // Same 50ms-bin hitches either way - only how many of them there were differs
        val few = beaconFor(Window(hitchesMs = List(8) { 30L }))
        val many = beaconFor(Window(hitchesMs = List(200) { 30L }))

        assertEquals(2, few.grade())          // 8 * 0.25
        assertEquals(Grade.GOOD, bandOf(few))

        assertEquals(50, many.grade())        // 200 * 0.25
        assertEquals(Grade.BAD, bandOf(many))
    }

    // endregion

    // region Bad

    @Test
    fun `severe hitches accumulating across windows grade Bad`() {
        // 10 hitches in the heaviest (750ms) bin across two windows -> 10 * 3.5 of severity
        val json = beaconFor(
            Window(hitchesMs = List(5) { 700L }),
            Window(hitchesMs = List(5) { 700L })
        )

        assertEquals(10L, json.meta().getLong("hitchCount"))
        assertEquals("[{750, 10}]", json.meta().getString("hitchHistograms"))
        assertEquals(35.0, json.meta().getDouble("hitchesSeverity"), 0.0)
        assertEquals(0L, json.meta().getLong("hangCount"))
        assertEquals(35, json.grade())
        assertEquals(Grade.BAD, bandOf(json))
    }

    @Test
    fun `a single hang under 2500ms drags an otherwise Good screen to Bad`() {
        val json = beaconFor(
            Window(hitchesMs = listOf(20, 10)),
            Window(hitchesMs = listOf(10)),
            Window(hangsMs = listOf(2000))
        )

        assertEquals(0.75, json.meta().getDouble("hitchesSeverity"), 0.0) // Good on its own
        assertEquals(1L, json.meta().getLong("hangCount"))
        assertEquals(2000L, json.meta().getLong("longestHang"))
        // the 2000ms hang scores 50 on the duration ramp, and the hitches add their sliver on top
        assertEquals(50, json.grade())
        assertEquals(Grade.BAD, bandOf(json))
    }

    @Test
    fun `a hang of exactly 2500ms sits on the Bad boundary`() {
        val json = beaconFor(
            Window(hitchesMs = listOf(10)),
            Window(hangsMs = listOf(2500))
        )

        assertEquals(2500L, json.meta().getLong("longestHang"))
        assertEquals(1L, json.meta().getLong("hangCount"))
        assertEquals(70, json.grade()) // 2500ms is the ramp's "bad" anchor, worth exactly 70
        assertEquals(Grade.BAD, bandOf(json))
    }

    @Test
    fun `two short hangs in separate windows are graded on their count`() {
        val json = beaconFor(
            Window(hitchesMs = listOf(20, 20), hangsMs = listOf(800)),
            Window(healthyFrames = 120),
            Window(hangsMs = listOf(1000))
        )

        assertEquals(2L, json.meta().getLong("hangCount"))
        assertEquals(1000L, json.meta().getLong("longestHang"))
        assertEquals(1800L, json.meta().getLong("totalHangDuration"))
        // 2 hangs is the count ramp's "good" anchor (30); neither hang is long enough to beat it
        assertEquals(30, json.grade())
        assertEquals(Grade.BAD, bandOf(json))
    }

    // endregion

    // region Worst

    @Test
    fun `severe hitches alone reach Worst and cap at 90`() {
        val json = beaconFor(Window(hitchesMs = List(30) { 700L }))

        // the meta keeps the raw severity even though the grade caps
        assertEquals(105.0, json.meta().getDouble("hitchesSeverity"), 0.0) // 30 * 3.5
        assertEquals(90, json.grade())
        assertEquals(Grade.WORST, bandOf(json))
    }

    @Test
    fun `hitches and hangs across many windows compound past either one alone`() {
        val hitchesOnly = beaconFor(Window(hitchesMs = List(10) { 700L }))
        val hangsOnly = beaconFor(Window(hangsMs = List(5) { 1600L }))
        val both = beaconFor(
            Window(hitchesMs = List(5) { 700L }, hangsMs = List(2) { 1600L }),
            Window(hitchesMs = List(5) { 700L }, hangsMs = List(3) { 1600L })
        )

        assertEquals(35, hitchesOnly.grade())  // severity 35, no hangs
        assertEquals(70, hangsOnly.grade())    // 5 hangs is the count ramp's "bad" anchor
        // 70 + (1 - 35/100) * 35 * (70/100)
        assertEquals(86, both.grade())
        assertTrue(both.grade() > hitchesOnly.grade() && both.grade() > hangsOnly.grade())
        assertEquals(Grade.WORST, bandOf(both))
        assertEquals(Grade.BAD, bandOf(hitchesOnly))
        assertEquals(Grade.BAD, bandOf(hangsOnly))
    }

    @Test
    fun `past 2500ms the longest-hang ramp is nearly flat, so only an extreme hang reaches Worst`() {
        val threeSeconds = beaconFor(
            Window(hitchesMs = listOf(20)),
            Window(hangsMs = listOf(3_000))
        )
        val aMinute = beaconFor(
            Window(hitchesMs = listOf(20)),
            Window(hangsMs = listOf(60_000))
        )

        assertEquals(3_000L, threeSeconds.meta().getLong("longestHang"))
        assertEquals(70, threeSeconds.grade())
        assertEquals(Grade.BAD, bandOf(threeSeconds))

        assertEquals(60_000L, aMinute.meta().getLong("longestHang"))
        assertEquals(88, aMinute.grade())
        assertEquals(Grade.WORST, bandOf(aMinute))
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
        repeat(10) { registry.frame(isJank = true, overrunMs = 700) }
        repeat(5) { registry.frame(isJank = true, overrunMs = 1600) }
        val detailMetrics = registry.onScreenHidden("DetailScreen")!!

        // two mild hitches, severity 0.5
        assertEquals(1, beacon(listMetrics).grade())
        assertEquals(Grade.GOOD, bandOf(beacon(listMetrics)))
        // severity 35 compounding with 5 hangs
        assertEquals(86, beacon(detailMetrics).grade())
        assertEquals(Grade.WORST, bandOf(beacon(detailMetrics)))
    }

    private companion object {
        const val SCREEN = "HitchWindowScreen"
    }
}
