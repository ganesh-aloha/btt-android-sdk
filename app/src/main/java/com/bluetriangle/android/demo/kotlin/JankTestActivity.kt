package com.bluetriangle.android.demo.kotlin

import android.animation.ValueAnimator
import android.os.Bundle
import android.os.SystemClock
import android.view.MenuItem
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.bluetriangle.android.demo.R
import com.bluetriangle.android.demo.databinding.ActivityJankTestBinding
import com.bluetriangle.android.demo.kotlin.JankTestActivity.Companion.HITCH_FRAME_SLEEP_MS


/**
 * Demo screen that produces hitch and hang frames on demand so the SDK's per-screen frame
 * health metrics (JankStats-based) can be exercised and verified in the timer beacon.
 *
 * A [ValueAnimator] keeps the box animating, so a frame is rendered on every vsync while this
 * screen is visible. The buttons then block the main thread *inside* the animation update - i.e.
 * during a frame - so that frame's duration overruns its budget:
 * - **Hitches**: every frame sleeps ~130ms for a 5 second window, producing a burst of janky
 *   frames whose overrun stays well below the hang threshold.
 * - **Hang**: a single frame sleeps 1000ms, overrunning far past the hang threshold (750ms excess).
 */
class JankTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJankTestBinding
    private var animator: ValueAnimator? = null

    /** Uptime until which every animation frame sleeps [HITCH_FRAME_SLEEP_MS]. */
    private var hitchUntilUptimeMs = 0L

    /** One-shot main-thread sleep applied to the next animation frame. */
    private var pendingHangMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJankTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setTitle(R.string.jank_test)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.simulateHitch.setOnClickListener {
            binding.statusText.text = getString(R.string.jank_test_hitching)
            hitchUntilUptimeMs = SystemClock.uptimeMillis() + HITCH_WINDOW_MS
        }

        binding.simulateHang.setOnClickListener {
            binding.statusText.text = getString(R.string.jank_test_hanging)
            pendingHangMs = HANG_SLEEP_MS
        }

        startAnimation()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    private fun startAnimation() {
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 360L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                binding.animatedBox.rotation = animation.animatedValue as Float
                blockFrameIfRequested()
            }
            start()
        }
    }

    /** Counts animation pulses so the hitch window only slows every Nth frame. */
    private var frameIndex = 0L

    /**
     * Runs on the main thread during the animation phase of a frame, so sleeping here stretches
     * the current frame's duration - exactly what JankStats measures.
     */
    private fun blockFrameIfRequested() {
        frameIndex++

        val hangMs = pendingHangMs
        if (hangMs > 0L) {
            pendingHangMs = 0L
            SystemClock.sleep(hangMs)
            binding.statusText.text = getString(R.string.jank_test_idle)
            return
        }

        if (SystemClock.uptimeMillis() < hitchUntilUptimeMs) {
            // Only every Nth frame sleeps: the SDK sums overruns across *consecutive* janky
            // frames into a hang, so hitches must be separated by normal frames to stay hitches.
            if (frameIndex % HITCH_FRAME_INTERVAL == 0L) {
                SystemClock.sleep(HITCH_FRAME_SLEEP_MS)
            }
        } else {
            binding.statusText.text = getString(R.string.jank_test_idle)
        }
    }

    override fun onDestroy() {
        animator?.cancel()
        animator = null
        super.onDestroy()
    }

    companion object {
        /** Sleep applied to the slow frames of the hitch window - janky, but far below a hang. */
        private const val HITCH_FRAME_SLEEP_MS = 130L

        /** Every Nth frame hitches; the normal frames in between reset the hang accumulation. */
        private const val HITCH_FRAME_INTERVAL = 3L

        /** How long the hitch burst lasts. */
        private const val HITCH_WINDOW_MS = 5_000L

        /** Single-frame freeze long enough to cross the SDK's hang threshold (750ms excess). */
        private const val HANG_SLEEP_MS = 1_000L
    }
}
