package com.bluetriangle.analytics.profilemanager

data class ProfilingEvent(
    val eventType: EventType,
    val timestampMs: Long,
    val processName: String? = null,
    val pid: Int? = null,
    val description: String? = null,
    val importance: Int? = null,
    val pssKb: Long? = null,
    val rssKb: Long? = null,
    /**
     * Truncated stack preview for in-memory/UI use.
     * Full ANR stacks from ApplicationExitInfo are persisted under filesDir/exit_traces
     * when [traceFilePath] is set.
     */
    val stackTrace: String? = null,
    /** Path to ProfilingManager / saved result file, if available. */
    val traceFilePath: String? = null,
    val profilingTag: String? = null,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
    /** From ActivityManager AnrWarningResult (API 37+). */
    val anrId: Int? = null,
    val anrType: Int? = null,
    val consumedMillis: Long? = null,
    val timeoutMillis: Long? = null,
    val profilingTriggerType: Int? = null,
) {
    enum class EventType {
        UNKNOWN,
        MANUAL_TRACE,

        /** Historical ApplicationExitInfo REASON_ANR (API 30+). */
        FATAL_ANR,

        /** Live AnrWarningListener or ProfilingTrigger.TRIGGER_TYPE_ANR. */
        ANR_WARNING,

        /** ProfilingTrigger.TRIGGER_TYPE_OOM (API 37+). */
        OOM_WARNING,

        /** Historical ApplicationExitInfo REASON_LOW_MEMORY (API 30+). */
        LOW_MEMORY_EXIT,

        /** App-requested near-ANR stack sample (not a system ANR trigger). */
        STACK_SAMPLE,
    }
}
