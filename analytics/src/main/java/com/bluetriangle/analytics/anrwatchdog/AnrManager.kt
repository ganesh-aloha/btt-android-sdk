package com.bluetriangle.analytics.anrwatchdog

import com.bluetriangle.analytics.BlueTriangleConfiguration
import com.bluetriangle.analytics.Tracker

internal class AnrManager(
    private val configuration: BlueTriangleConfiguration
) :
    AnrListener {

    val detector: AnrDetector = RunnableAnrDetector(configuration.trackAnrIntervalSec)

    init {
        detector.addAnrListener("AnrManager", this)
    }

    internal val anrRecordsHolder = ANRRecordsHolder()

    fun start() {
        detector.startDetection()
    }

    fun stop() {
        detector.stopDetection()
        anrRecordsHolder.stop()
    }


    override fun onAppNotResponding(error: ANRWarningException) {
        configuration.logger?.debug("Anr Received: ${error.message}")

        val mostRecentTimer = Tracker.instance?.getMostRecentTimer()
        mostRecentTimer.let {
            if(it == null) {
                Tracker.instance?.anrReporter?.reportANR(null, error)
            } else {
                anrRecordsHolder.recordANR(it, error)
            }
        }
    }
}