package com.bluetriangle.analytics

import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import com.bluetriangle.analytics.eventhub.sdkeventhub.SDKEventHub
import com.bluetriangle.analytics.jank.JankMetrics
import com.bluetriangle.analytics.model.NativeAppProperties
import com.bluetriangle.analytics.performancemonitor.PerformanceSpan
import com.bluetriangle.analytics.utility.getNumberOfCPUCores

/**
 * A timer instance that can be started, marked interactive, and ended.
 *
 *
 * Timers maintain the start, interactive, and end times in milliseconds. They also maintain a map of attributes for the
 * timer such as page name, brand value, campaign name, AB test, etc.
 *
 *
 * Additional attributes beyond the ones defined below can be set on a timer as well.
 */
class Timer : Parcelable {
    companion object {
        const val EXTRA_TIMER = "BTT_TIMER"
        const val FIELD_PAGE_NAME = "pageName"
        const val FIELD_NST = "nst"
        const val FIELD_NAVIGATION_START = "nStart"
        const val FIELD_UNLOAD_EVENT_START = "unloadEventStart"
        const val FIELD_CONTENT_GROUP_NAME = "pageType"
        @Deprecated("Page value is deprecated in 2.12.0 and will be removed in upcoming major releases")
        const val FIELD_PAGE_VALUE = "pageValue"
        const val FIELD_PAGE_TIME = "pgTm"
        const val FIELD_DOM_INTERACTIVE = "domInteractive"
        const val FIELD_NAVIGATION_TYPE = "navigationType"
        const val FIELD_CART_VALUE = "cartValue"
        const val FIELD_ORDER_NUMBER = "ONumBr"
        const val FIELD_ORDER_TIME = "orderTND"
        const val FIELD_EVENT_TYPE = "eventType"
        const val FIELD_SITE_ID = "siteID"
        const val FIELD_TRAFFIC_SEGMENT_NAME = "txnName"
        const val FIELD_CAMPAIGN = "campaign"
        const val FIELD_TIME_ON_PAGE = "top"
        const val FIELD_BRAND_VALUE = "bv"
        const val FIELD_URL = "thisURL"
        const val FIELD_BVZN = "bvzn"
        const val FIELD_OS = "EUOS"
        const val FIELD_LONG_SESSION_ID = "sessionID"
        const val FIELD_SESSION_ID = "sID"
        const val FIELD_GLOBAL_USER_ID = "gID"
        const val FIELD_CUSTOM_VALUE_4 = "CV4"
        const val FIELD_RV = "RV"
        const val FIELD_WCD = "wcd"
        const val FIELD_AB_TEST_ID = "AB"
        const val FIELD_CAMPAIGN_SOURCE = "CmpS"
        const val FIELD_CAMPAIGN_MEDIUM = "CmpM"
        const val FIELD_CAMPAIGN_NAME = "CmpN"
        const val FIELD_DATACENTER = "DCTR"
        const val FIELD_REFERRER_URL = "RefURL"
        const val FIELD_BROWSER = "browser"
        const val FIELD_NATIVE_OS = "os"
        const val FIELD_DEVICE = "device"
        const val FIELD_BROWSER_VERSION = "browserVersion"
        const val FIELD_SDK_VERSION = "VER"
        const val FIELD_WCDTT = "WCDtt"
        const val FIELD_EXCLUDED = "excluded"
        const val FIELD_NATIVE_APP = "NATIVEAPP"
        const val FIELD_ERR = "ERR"
        const val FIELD_NA_FLG = "NAflg"
        internal const val FIELD_CART_COUNT = "c_count"
        internal const val FIELD_CART_COUNT_CHECKOUT = "co_count"
        internal const val FIELD_NET_STATE_SOURCE = "netStateSource"
        const val FIELD_EXTENDED_CUSTOM_VARIABLES = "ECV"

        /**
         * A map of fields and their associated default values
         */
        private val DEFAULT_VALUES: Map<String, String> = mapOf(
            FIELD_BVZN to "",
            FIELD_OS to Constants.OS,
            FIELD_NATIVE_OS to Constants.OS,
            FIELD_EVENT_TYPE to "9",
            FIELD_NAVIGATION_TYPE to "9",
            FIELD_RV to "0",
            FIELD_CUSTOM_VALUE_4 to "0",
            FIELD_WCD to "0",
            FIELD_DATACENTER to "Default",
            FIELD_AB_TEST_ID to "Default",
            FIELD_BRAND_VALUE to "0",
            FIELD_TIME_ON_PAGE to "0",
            FIELD_CAMPAIGN to "",
            FIELD_CAMPAIGN_NAME to "",
            FIELD_CAMPAIGN_MEDIUM to "",
            FIELD_CAMPAIGN_SOURCE to "",
            FIELD_REFERRER_URL to "",
            FIELD_URL to "",
            FIELD_CART_VALUE to "0",
            FIELD_ORDER_NUMBER to "",
            FIELD_CONTENT_GROUP_NAME to "",
        )

        @JvmField
        val CREATOR: Parcelable.Creator<Timer> = object : Parcelable.Creator<Timer> {
            override fun createFromParcel(`in`: Parcel): Timer {
                return Timer(`in`)
            }

            override fun newArray(size: Int): Array<Timer?> {
                return arrayOfNulls(size)
            }
        }
    }

    /**
     * Tracker
     */
    private val tracker = Tracker.instance

    /**
     * Logger
     */
    private val logger = tracker?.configuration?.logger

    /**
     * A map of all fields for this timer to be sent to the cloud server
     */
    private val fields: MutableMap<String, String>

    /**
     * Start time in milliseconds
     */
    var start: Long = 0
        private set

    /**
     * The current time when the interactive call was made in milliseconds
     */
    var interactive: Long = 0
        private set

    /**
     * The time in milliseconds this timer was ended
     */
    var end: Long = 0
        private set

    /**
     * Performance monitor thread
     */
    internal var performanceSpan: PerformanceSpan? = null

    internal var nativeAppProperties = NativeAppProperties(
        null,
        null,
        performanceSpan?.maxMainThreadUsage,
        null
    )

    private fun isTrackingEnabled():Boolean {
        // tracker field is set when the timer object is created
        // Tracker.instance is the current state of the Tracker
        // Tracking is enabled only if the tracker was not null from the time the Timer object was created till now
        // so checking both these edge cases is necessary
        return tracker != null && Tracker.instance != null
    }

    fun generateNativeAppProperties() {
        // Carry frame health metrics across regeneration - they're stamped when the screen is
        // hidden, which can happen before this rebuild (e.g. Tracker.prepareTimerRunnable).
        val stampedJankMetrics = nativeAppProperties.jankMetrics
        nativeAppProperties = NativeAppProperties(
            null,
            null,
            performanceSpan?.maxMainThreadUsage,
            null,
            getNumberOfCPUCores(),
            appVersion = tracker?.appVersion,
            sdkVersion = BuildConfig.SDK_VERSION,
            jankMetrics = stampedJankMetrics
        )
        Tracker.instance?.networkTimelineTracker?.let {
            val networkSlice = it.sliceStats(
                start,
                if (end == 0L) System.currentTimeMillis() else end
            )
            nativeAppProperties.cellular = networkSlice.cellular
            nativeAppProperties.wifi = networkSlice.wifi
            nativeAppProperties.ethernet = networkSlice.ethernet
            nativeAppProperties.offline = networkSlice.offline
            nativeAppProperties.netStateSource = networkSlice.sources.joinToString("|")
        }
    }

    /**
     * Create a timer instance with no page name or traffic segment name. These will need to be set later before
     * submitting the timer.
     */
    constructor() : super() {
        fields = DEFAULT_VALUES.toMutableMap()
        if (tracker?.configuration?.isPerformanceMonitorEnabled == true) {
            performanceSpan = tracker.createPerformanceSpan()
        }
    }

    /**
     * Create a timer instance with the given page name and traffic segment name
     *
     * @param pageName           page name
     * @param trafficSegmentName traffic segment name
     */
    constructor(pageName: String?, trafficSegmentName: String?) : this() {
        if (pageName != null) {
            fields[FIELD_PAGE_NAME] = pageName
        }
        if (trafficSegmentName != null) {
            fields[FIELD_TRAFFIC_SEGMENT_NAME] = trafficSegmentName
        }
    }

    /**
     * Create a timer instance with the given page name, traffic segment name, optional AB test id, and optional content
     * group name.
     *
     * @param pageName           page name
     * @param trafficSegmentName traffic segment name
     * @param abTestIdentifier   AB Test ID/Name
     * @param contentGroupName   Content Group Name
     */
    constructor(
        pageName: String?,
        trafficSegmentName: String?,
        abTestIdentifier: String?,
        contentGroupName: String?
    ) : this(pageName, trafficSegmentName) {
        if (abTestIdentifier != null) {
            fields[FIELD_AB_TEST_ID] = abTestIdentifier
        }
        if (contentGroupName != null) {
            fields[FIELD_CONTENT_GROUP_NAME] = contentGroupName
        }
    }

    /**
     * Start this timer if not already started. If already started, will log an error.
     *
     * @return this timer
     */
    @Synchronized
    fun start(): Timer {
        if(!isTrackingEnabled()) return this

        if (start == 0L) {
            start = System.currentTimeMillis()
            setField(FIELD_UNLOAD_EVENT_START, start)
            setField(FIELD_NST, start)
            tracker?.setMostRecentTimer(this)
            SDKEventHub.instance.onTimerStarted(this)
        } else {
            logger?.error("Timer already started")
        }
        performanceSpan?.start()
        return this
    }

    internal fun startWithoutPerformanceMonitor(): Timer {
        if (performanceSpan != null) {
            tracker?.clearPerformanceSpan(performanceSpan!!.id)
            performanceSpan = null
        }
        return start()
    }

    /**
     * Mark this timer interactive at current time if the timer has been started and not already marked interactive. If
     * the timer has not been started yet, log an error. If the timer has already been marked interactive, log an
     * error.
     *
     * @return this timer
     */
    @Synchronized
    fun interactive(): Timer {
        if(!isTrackingEnabled()) return this

        if (start > 0 && interactive == 0L) {
            interactive = System.currentTimeMillis()
            setField(FIELD_DOM_INTERACTIVE, interactive)
        } else {
            if (start == 0L) {
                logger?.error("Timer never started")
            } else if (interactive != 0L) {
                logger?.error("Timer already marked as interactive")
            }
        }
        return this
    }

    var pageTimeCalculator: () -> Long = {
        end - start
    }

    internal var onEnded: () -> Unit = {}

    /**
     * End this timer.
     *
     * @return this timer.
     */
    @Synchronized
    fun end(): Timer {
        if(!isTrackingEnabled()) return this

        if (start > 0 && end == 0L) {
            end = System.currentTimeMillis()
            setField(FIELD_PAGE_TIME, pageTimeCalculator())
        } else {
            if (start == 0L) {
                logger?.error("Timer never started")
            } else if (end != 0L) {
                logger?.error("Timer already ended")
            }
        }
        if(!fields.containsKey(FIELD_ORDER_TIME)) {
            setOrderTime(end)
        }
        performanceSpan?.let {
            it.stop()
            val performanceReport = it.performanceFields
            logger?.debug("Performance Report: $performanceReport")
            setPerformanceReportFields(performanceReport.mapKeys { it.key.field })
            tracker?.clearPerformanceSpan(it.id)
        }
        tracker?.removeFromTimerStack(this)
        onEnded()
        return this
    }

    /**
     * Determines if this timer is currently running
     *
     * @return True if timer has started but not yet ended.
     */
    fun isRunning(): Boolean = start > 0 && end == 0L

    /**
     * Determines if this timer has been ended
     *
     * @return True if ended, else false
     */
    fun hasEnded(): Boolean = start > 0 && end > 0


    /**
     * Determines if this timer has been marked as interactive yet
     *
     * @return True if marked interactive, else false
     */
    fun isInteractive(): Boolean = start > 0 && interactive > 0

    /**
     * Convenience method to submit this timer to the global tracker
     */
    fun submit() {
        if(!isTrackingEnabled()) return

        val tracker = Tracker.instance
        if (tracker != null) {
            tracker.submitTimer(this)
        } else {
            logger?.error("Tracker not initialized")
        }
    }

    /**
     * Get all the fields currently associated with this timer.
     *
     * @return returns a new hash map with all the fields currently.
     */
    fun getFields(): Map<String, String> {
        return synchronized(fields) { fields.toMap() }
    }

    /**
     * Set the timer's page name
     *
     * @param pageName name of the page for this timer
     * @return this timer
     */
    fun setPageName(pageName: String): Timer {
        return setField(FIELD_PAGE_NAME, pageName)
    }

    /**
     * Set the timer's traffic segment name
     *
     * @param trafficSegmentName name of the traffic segment for this timer
     * @return this timer
     */
    fun setTrafficSegmentName(trafficSegmentName: String): Timer {
        return setField(FIELD_TRAFFIC_SEGMENT_NAME, trafficSegmentName)
    }

    /**
     * Set this timer's AB test identifier
     *
     * @param abTestIdentifier the AB test id
     * @return this timer
     */
    fun setAbTestIdentifier(abTestIdentifier: String): Timer {
        return setField(FIELD_AB_TEST_ID, abTestIdentifier)
    }

    /**
     * Set the content group name or page type for this timer
     *
     * @param contentGroupName name of content group or page type
     * @return this timer
     */
    fun setContentGroupName(contentGroupName: String): Timer {
        return setField(FIELD_CONTENT_GROUP_NAME, contentGroupName)
    }

    /**
     * Set the value of this page/timer
     *
     * @param pageValue value of page
     * @return this timer
     */
    @Deprecated("Page value is deprecated in 2.12.0 and will be removed in upcoming major releases")
    fun setPageValue(pageValue: Double): Timer {
        return setField(FIELD_PAGE_VALUE, pageValue)
    }

    /**
     * Set the brand value of this timer
     *
     * @param brandValue brand's value
     * @return this timer
     */
    fun setBrandValue(brandValue: Double): Timer {
        return setField(FIELD_BRAND_VALUE, brandValue)
    }

    /**
     * Set the value of the cart for this timer
     *
     * @param cartValue value of cart
     * @return this timer
     */
    fun setCartValue(cartValue: Double): Timer {
        return setField(FIELD_CART_VALUE, cartValue)
    }

    /**
     * Set the order number for this timer
     *
     * @param orderNumber order number
     * @return this timer
     */
    fun setOrderNumber(orderNumber: String): Timer {
        return setField(FIELD_ORDER_NUMBER, orderNumber)
    }

    /**
     * Set the time of the order
     *
     * @param orderTime epoch time of order in milliseconds
     * @return this timer
     */
    fun setOrderTime(orderTime: Long): Timer {
        return setField(FIELD_ORDER_TIME, orderTime)
    }

    /**
     * Set the cart count for this timer
     *
     * @param cartCount the cart count for this timer
     * @return this timer
     */
    fun setCartCount(cartCount: Int): Timer {
        return setField(FIELD_CART_COUNT, cartCount)
    }

    /**
     * Set the checkout cart count for this timer
     *
     * @param cartCountCheckout the checkout cart count for this timer
     * @return this timer
     */
    fun setCartCountCheckout(cartCountCheckout: Int):Timer {
        return setField(FIELD_CART_COUNT_CHECKOUT, cartCountCheckout)
    }

    /**
     * Set the name of the campaign
     *
     * @param campaignName name of campaign
     * @return this timer
     */
    fun setCampaignName(campaignName: String): Timer {
        return setField(FIELD_CAMPAIGN_NAME, campaignName)
    }

    /**
     * Set the source of the campaign
     *
     * @param campaignSource source of campaign
     * @return this timer
     */
    fun setCampaignSource(campaignSource: String): Timer {
        return setField(FIELD_CAMPAIGN_SOURCE, campaignSource)
    }

    /**
     * Set the medium of the campaign
     *
     * @param campaignMedium medium of campaign
     * @return this timer
     */
    fun setCampaignMedium(campaignMedium: String): Timer {
        return setField(FIELD_CAMPAIGN_MEDIUM, campaignMedium)
    }

    /**
     * Set campaign details
     *
     * @param campaignName   name of campaign
     * @param campaignSource source of campaign
     * @param campaignMedium medium of campaign
     * @return this timer
     */
    fun setCampaign(campaignName: String, campaignSource: String, campaignMedium: String): Timer {
        setCampaignName(campaignName)
        setCampaignMedium(campaignMedium)
        setCampaignSource(campaignSource)
        return this
    }

    /**
     * Set time on page for this timer
     *
     * @param timeOnPage time on page in milliseconds
     * @return this timer
     */
    fun setTimeOnPage(timeOnPage: Long): Timer {
        return setField(FIELD_TIME_ON_PAGE, timeOnPage)
    }

    /**
     * Set the URL for this timer
     *
     * @param url the url for this timer
     * @return this timer
     */
    fun setUrl(url: String): Timer {
        return setField(FIELD_URL, url)
    }

    /**
     * Set the referrer URL for this timer
     *
     * @param referrer the referrer URL
     * @return this timer
     */
    fun setReferrer(referrer: String): Timer {
        return setField(FIELD_REFERRER_URL, referrer)
    }

    /**
     * Allows the setting of multiple fields via a Map
     *
     * @param fields A map of attribute names as keys and their associated string value to set
     * @return this timer
     */
    fun setFields(fields: Map<String, String>): Timer {
        synchronized(this.fields) { this.fields.putAll(fields) }
        return this
    }

    /**
     * Allows the setting of multiple fields via a Map. Only sets a field if it already doesn't exist in the internal fields.
     *
     * @param fields A map of attribute names as keys and their associated string value to set
     * @return this timer
     */
    fun setFieldsIfAbsent(fields: Map<String, String>): Timer {
        synchronized(this.fields) {
            fields.forEach {
                if(!this.fields.containsKey(it.key)) {
                    this.fields[it.key] = it.value
                }
            }
        }
        return this
    }

    /**
     * Sets a field name with the given value
     *
     * @param fieldName the name of the field to set
     * @param value     the value to set for the given field
     * @return this timer
     */
    fun setField(fieldName: String, value: String): Timer {
        synchronized(fields) { fields[fieldName] = value }
        return this
    }

    fun setPerformanceReportFields(performanceReport: Map<String, String>): Timer {
        synchronized(fields) { fields.putAll(performanceReport) }
        return this
    }

    /**
     * Stamps the per-screen frame health metrics captured while this screen was visible (set by
     * [com.bluetriangle.analytics.screenTracking.BTTScreenLifecycleTracker] when a Fragment/Activity
     * is hidden) onto [nativeAppProperties], where they ship inside the NATIVEAPP beacon field.
     * Metrics already stamped are never overwritten.
     */
    internal fun setJankReportFields(metrics: JankMetrics): Timer {
        if (nativeAppProperties.jankMetrics == null) {
            nativeAppProperties.jankMetrics = metrics
        }
        return this
    }

    /**
     * A grouped screen's own Timer is never submitted directly - only the synthetic group Timer
     * is - so [com.bluetriangle.analytics.screenTracking.grouping.BTTTimerGroup] uses this to carry
     * jank/hitch/hang numbers from a chosen member Timer (the one with the most observed frames)
     * onto the group Timer that actually gets submitted.
     */
    internal fun copyJankReportFieldsFrom(other: Timer) {
        other.nativeAppProperties.jankMetrics?.let { setJankReportFields(it) }
    }

    /**
     * Sets a field name with the given value
     *
     * @param fieldName the name of the field to set
     * @param value     the value to set for the given field
     * @return this timer
     */
    fun setField(fieldName: String, value: Int): Timer {
        return setField(fieldName, Integer.toString(value))
    }

    /**
     * Sets a field name with the given value
     *
     * @param fieldName the name of the field to set
     * @param value     the value to set for the given field
     * @return this timer
     */
    fun setField(fieldName: String, value: Double): Timer {
        return setField(fieldName, java.lang.Double.toString(value))
    }

    /**
     * Sets a field name with the given value
     *
     * @param fieldName the name of the field to set
     * @param value     the value to set for the given field
     * @return this timer
     */
    fun setField(fieldName: String, value: Float): Timer {
        return setField(fieldName, java.lang.Float.toString(value))
    }

    /**
     * Sets a field name with the given value
     *
     * @param fieldName the name of the field to set
     * @param value     the value to set for the given field
     * @return this timer
     */
    fun setField(fieldName: String, value: Boolean): Timer {
        return setField(fieldName, java.lang.Boolean.toString(value))
    }

    /**
     * Sets a field name with the given value
     *
     * @param fieldName the name of the field to set
     * @param value     the value to set for the given field
     * @return this timer
     */
    fun setField(fieldName: String, value: Long): Timer {
        return setField(fieldName, java.lang.Long.toString(value))
    }

    /**
     * Get the current value for the given field
     *
     * @param fieldName the name of the field to get
     * @return the current value for the given field or null if not set
     */
    fun getField(fieldName: String): String? {
        return synchronized(fields) { fields[fieldName].toString() }
    }

    /**
     * Get the current value for the given field or default if null or empty
     *
     * @param fieldName the name of the field to get
     * @param defaultValue the default value to return if null or empty
     * @return the current value for the given field or null if not set
     */
    fun getField(fieldName: String, defaultValue: String): String {
        return synchronized(fields) {
            val fieldValue = fields[fieldName]
            return@synchronized if (fieldValue.isNullOrEmpty()) {
                defaultValue
            } else fieldValue
        }
    }

    /**
     * Sets a field if it's not already set. i.e. it still has the default value or doesn't exist at all
     *
     * @param fieldName name of field to remove
     * @return this timer
     */
    internal fun setFieldIfNotSet(fieldName: String, value: String): Timer {
        synchronized(fields) {
            if(!isFieldSet(fieldName)) {
                setField(fieldName, value)
            }
        }
        return this
    }

    /**
     * Checks if a field is set or not. i.e. it still has the default value or doesn't exist at all
     *
     * @param fieldName name of field to remove
     * @return true if the field exists or false otherwise
     */
    internal fun isFieldSet(fieldName: String): Boolean {
        return synchronized(fields) {
            val exists = fields.containsKey(fieldName)
            val hasDefaultValue = DEFAULT_VALUES.containsKey(fieldName) && fields[fieldName] == DEFAULT_VALUES[fieldName]

            exists && !hasDefaultValue
        }
    }

    /**
     * Resets a field to the default value if there is one else removes the field completely.
     *
     * @param fieldName name of field to remove
     * @return this timer
     */
    fun clearField(fieldName: String): Timer {
        synchronized(fields) {
            if (DEFAULT_VALUES.containsKey(fieldName)) {
                DEFAULT_VALUES[fieldName]?.let { fields[fieldName] = it }
            } else {
                fields.remove(fieldName)
            }
        }
        return this
    }

    private constructor(`in`: Parcel) {
        start = `in`.readLong()
        interactive = `in`.readLong()
        end = `in`.readLong()
        `in`.readString()?.let {
            performanceSpan = tracker?.getPerformanceSpan(it)
            performanceSpan?.start()
        }
        val size = `in`.readInt()
        fields = HashMap(size)
        for (i in 0 until size) {
            val key = `in`.readString()
            val value = `in`.readString()
            if (key != null && value != null) {
                fields[key] = value
            }
        }
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(start)
        dest.writeLong(interactive)
        dest.writeLong(end)
        performanceSpan?.stop()
        dest.writeString(performanceSpan?.id)
        dest.writeInt(fields.size)
        for ((key, value) in fields) {
            dest.writeString(key)
            dest.writeString(value)
        }
    }

    override fun toString(): String {
        return "BTT Timer: ${getField(FIELD_PAGE_NAME)}"
    }

    fun setError(err: Boolean) {
        fields[FIELD_ERR] = if (err) "1" else "0"
    }

    fun setWCD(wcd: Boolean) {
        fields[FIELD_WCD] = if (wcd) "1" else "0"
    }

    internal fun startSilent(): Timer {
        if(!isTrackingEnabled()) return this

        if (start == 0L) {
            start = System.currentTimeMillis()
            setField(FIELD_UNLOAD_EVENT_START, start)
            setField(FIELD_NST, start)
        } else {
            logger?.error("Timer already started")
        }
        performanceSpan?.start()
        return this
    }

}
