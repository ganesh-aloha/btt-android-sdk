package com.bluetriangle.analytics

/**
 * constant values used across the SDK
 */
object Constants {
    internal const val UNKNOWN = "unknown"
    const val OS = "Android"
    const val CRASH_PAGE_NAME = "Android Crash"
    const val BROWSER = "Native App"
    const val DEVICE_TABLET = "Tablet"
    const val DEVICE_MOBILE = "Mobile"
    const val UTF_8 = "UTF-8"
    const val METHOD_POST = "POST"
    const val HEADER_USER_AGENT = "User-Agent"
    const val HEADER_CONTENT_TYPE = "Content-Type"
    const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"
    const val CHECK_INTERVAL: Long = 1000
    const val ANR_DEFAULT_INTERVAL: Int = 5 // in seconds
    const val TIMER_MIN_PGTM = 15L

    /**
     * Max length of extended custom variable strings
     */
    const val EXTENDED_CUSTOM_VARIABLE_MAX_LENGTH = 1024

    /**
     * The max size of the extended custom variable JSON payload
     */
    const val EXTENDED_CUSTOM_VARIABLE_MAX_PAYLOAD = 1024 * 1024 * 3  // 3 MB
    const val BUFFER_REPOSITORY = "Buffer"
    const val DEFAULT_NETWORK_SAMPLE_RATE = 0.05

    internal const val DEFAULT_GROUPING_IDLE_TIME = 2
    internal const val DEFAULT_ENABLE_GROUPING = true
    internal const val DEFAULT_ENABLE_GROUPING_TAP_DETECTION = true
    internal const val DEFAULT_ENABLE_NETWORK_STATE_TRACKING = true
    internal const val DEFAULT_ENABLE_CRASH_TRACKING = true
    internal const val DEFAULT_ENABLE_ANR_TRACKING = true
    internal const val DEFAULT_ENABLE_MEMORY_WARNING = true
    internal const val DEFAULT_ENABLE_LAUNCH_TIME = true
    internal const val DEFAULT_ENABLE_WEB_VIEW_STITCHING = true
    internal const val DEFAULT_ENABLE_APP_INSTALL = true
    internal const val DEFAULT_ENABLE_FORCE_RESTART = true
    internal const val DEFAULT_FORCE_RESTART_DURATION = 10.0
    internal const val DEFAULT_GROUPED_VIEW_SAMPLE_RATE = 0.05

    internal const val DEFAULT_CHECKOUT_TRACKING_ENABLED = false
    internal const val DEFAULT_CHECKOUT_AMOUNT = 1.0
    internal const val DEFAULT_CART_COUNT = 1
    internal const val DEFAULT_CART_COUNT_CHECKOUT = 1
    internal const val DEFAULT_TIMER_VALUE = 100

    internal const val DEFAULT_CONFIG_KEY = UNKNOWN

    internal const val SDK_VERSION = "sdkVersion"
    internal const val APP_VERSION = "appVersion"
    internal const val APP_LAST_FOREGROUND_TIME = "appLastForegroundTime"

    internal const val BREADCRUMBS = "breadcrumbs"
    internal const val NUMBER_OF_CPU_CORES = "numberOfCPUCores"
    internal const val SCREEN_TYPE = "screenType"
    internal const val MAX_MAIN_THREAD_USAGE = "maxMainThreadUsage"
    internal const val FULL_TIME = "fullTime"
    internal const val LOAD_TIME = "loadTime"
    internal const val LAUNCH_SCREEN_NAME = "launchScreenName"

    internal const val NETWORK_TYPE_WIFI = "wifi"
    internal const val NETWORK_TYPE_CELLULAR = "cellular"
    internal const val NETWORK_TYPE_ETHERNET = "ethernet"
    internal const val NETWORK_TYPE_OFFLINE = "offline"
    internal const val GROUPED = "grouped"
    internal const val GROUPING_CAUSE = "groupingCause"
    internal const val GROUPING_CAUSE_INTERVAL = "groupingCauseInterval"
    internal const val EVENT_ID = "eventID"
    internal const val CONFIDENCE_RATE = "confidenceRate"
    internal const val CONFIDENCE_MSG = "confidenceMsg"

    internal const val AUTO_CHECKOUT = "autoCheckout"
    internal const val CONFIG_KEY = "configKey"

    internal const val DEFAULT_TRAFFIC_SEGMENT_NAME = "Main Segment"
    internal const val DEFAULT_CONTENT_GROUP_NAME = "Main Group"
    internal const val INSTALL_TIME = "installTime"
    internal const val MAX_FIELD_CHAR_LENGTH: Int = 512

    internal const val DEFAULT_ENABLE_BREADCRUMBS = true
    internal const val DEFAULT_BREADCRUMBS_CAPACITY = 150

    internal const val DEFAULT_ENABLE_JANK_TRACKING = true

    internal const val DEFAULT_SCREEN_REFRESH_RATE = 60f
    // Per-screen frame health report fields (see [com.bluetriangle.analytics.jank.JankMetrics])
    internal const val TOTAL_FRAME_COUNT = "totalFrames"
    internal const val JANK_FRAME_COUNT = "jankFrameCount"
    internal const val TOTAL_JANK_DURATION = "totalJankDuration"
    internal const val JANK_TIME_RATIO = "jankTimeRatio"
    internal const val LONGEST_JANK = "longestJank"
    internal const val HITCH_COUNT = "hitchCount"
    internal const val TOTAL_HITCH_DURATION = "totalHitchDuration"
    internal const val HITCH_TIME_RATIO = "hitchTimeRatio"
    internal const val LONGEST_HITCH = "longestHitch"
    internal const val HANG_COUNT = "hangCount"
    internal const val TOTAL_HANG_DURATION = "totalHangDuration"
    internal const val HANG_TIME_RATIO = "hangTimeRatio"
    internal const val LONGEST_HANG = "longestHang"

    /**
     * Frames at or above this full duration (but below [HANG_THRESHOLD_MS]) are classified as
     * hitches - RAIL's "perceptible delay" boundary. Frames JankStats flags as janky but shorter
     * than this stay in the jank bucket. The three buckets are mutually exclusive.
     */
    internal const val HITCH_THRESHOLD_MS = 100L

    /**
     * Frames at or above this full duration are classified as hangs (Apple's hang convention).
     */
    internal const val HANG_THRESHOLD_MS = 250L

    /**
     * Passed to [androidx.metrics.performance.JankStats.jankHeuristicMultiplier]: a frame is only
     * classified as janky once its duration exceeds this multiple of the device's per-frame
     * budget. Raw frame duration routinely runs above a single vsync interval even for visually
     * smooth frames (double/triple-buffered GPU command issue + swap time), so a bare "any
     * overrun" check flags nearly every frame. This is JankStats' own default multiplier, set
     * explicitly here so behavior doesn't silently shift if the library's default ever changes.
     */
    internal const val JANK_HEURISTIC_MULTIPLIER = 2.0f
}
