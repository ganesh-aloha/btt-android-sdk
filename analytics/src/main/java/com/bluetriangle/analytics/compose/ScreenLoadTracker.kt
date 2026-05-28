package com.bluetriangle.analytics.compose

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver

class ScreenLoadTracker(private val view: View) {

    private var onDrawListener: ViewTreeObserver.OnDrawListener? = null
    private var idleRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // How long to wait after the last draw before declaring idle
    private val IDLE_TIMEOUT_MS = 100L

    fun trackScreenLoad(
        onLoaded: (Long) -> Unit
    ) {
        val startTime = System.nanoTime()

        // Each time a draw happens, we reset the idle timer.
        // When the timer finally fires without being reset, 
        // the screen has settled.
        onDrawListener = ViewTreeObserver.OnDrawListener {
            // A draw is happening — screen is not idle yet, reset the timer
            idleRunnable?.let { mainHandler.removeCallbacks(it) }

            idleRunnable = Runnable {
                val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
                onLoaded(elapsedMs)
                cleanup(view)
            }

            mainHandler.postDelayed(idleRunnable!!, IDLE_TIMEOUT_MS)
        }

        view.viewTreeObserver.addOnDrawListener(onDrawListener!!)
    }

    private fun cleanup(decorView: View) {
        onDrawListener?.let { decorView.viewTreeObserver.removeOnDrawListener(it) }
        idleRunnable?.let { mainHandler.removeCallbacks(it) }
        onDrawListener = null
        idleRunnable = null
    }
}
