package com.bluetriangle.analytics.profilemanager

/**
 * Callback for profiling events.
 *
 * Invoked on the SDK background executor (never on the main thread), including
 * replayed buffered events from [ProfilingEventTracker.addProfilingEventListener].
 * Hosts that update UI must post to the main thread themselves.
 */
fun interface ProfilingEventListener {
    fun onProfilingEvent(event: ProfilingEvent)
}
