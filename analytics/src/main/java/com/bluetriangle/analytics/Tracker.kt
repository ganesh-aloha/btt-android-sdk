/*
 * Copyright (c) 2024, Blue Triangle
 * All rights reserved.
 *
 */
package com.bluetriangle.analytics

import android.Manifest.permission.ACCESS_NETWORK_STATE
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.text.TextUtils
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.bluetriangle.analytics.Constants.APP_VERSION
import com.bluetriangle.analytics.Constants.DEFAULT_CONFIG_KEY
import com.bluetriangle.analytics.Constants.MAX_FIELD_CHAR_LENGTH
import com.bluetriangle.analytics.Constants.UNKNOWN
import com.bluetriangle.analytics.Timer.Companion.FIELD_CONTENT_GROUP_NAME
import com.bluetriangle.analytics.Timer.Companion.FIELD_PAGE_NAME
import com.bluetriangle.analytics.Timer.Companion.FIELD_SESSION_ID
import com.bluetriangle.analytics.Timer.Companion.FIELD_TRAFFIC_SEGMENT_NAME
import com.bluetriangle.analytics.anrwatchdog.ANRReporter
import com.bluetriangle.analytics.anrwatchdog.AnrManager
import com.bluetriangle.analytics.applaunch.AppLaunchReporter
import com.bluetriangle.analytics.breadcrumbs.BreadcrumbsManager
import com.bluetriangle.analytics.breadcrumbs.config.BreadcrumbsConfig
import com.bluetriangle.analytics.checkout.config.CheckoutConfig
import com.bluetriangle.analytics.checkout.event.CheckoutEvent
import com.bluetriangle.analytics.checkout.event.CheckoutEventReporter
import com.bluetriangle.analytics.deviceinfo.DeviceInfoProvider
import com.bluetriangle.analytics.deviceinfo.IDeviceInfoProvider
import com.bluetriangle.analytics.dynamicconfig.fetcher.BTTConfigurationFetcher
import com.bluetriangle.analytics.dynamicconfig.model.BTTRemoteConfiguration
import com.bluetriangle.analytics.dynamicconfig.repository.BTTConfigurationRepository
import com.bluetriangle.analytics.dynamicconfig.repository.IBTTConfigurationRepository
import com.bluetriangle.analytics.dynamicconfig.updater.BTTConfigurationUpdater
import com.bluetriangle.analytics.dynamicconfig.updater.IBTTConfigurationUpdater
import com.bluetriangle.analytics.event.BTTEvent
import com.bluetriangle.analytics.eventhub.AppEventHub
import com.bluetriangle.analytics.eventhub.sdkeventhub.SDKEventHub
import com.bluetriangle.analytics.globalproperties.CustomCategory
import com.bluetriangle.analytics.globalproperties.GlobalPropertiesStore
import com.bluetriangle.analytics.hybrid.BTTWebViewTracker
import com.bluetriangle.analytics.launchtime.LaunchMonitor
import com.bluetriangle.analytics.launchtime.LaunchReporter
import com.bluetriangle.analytics.lifecycle.LifecycleRegistry
import com.bluetriangle.analytics.networkcapture.CapturedRequest
import com.bluetriangle.analytics.networkcapture.CapturedRequestCollection
import com.bluetriangle.analytics.networkstate.NetworkStateMonitor
import com.bluetriangle.analytics.networkstate.NetworkTimelineTracker
import com.bluetriangle.analytics.performancemonitor.PerformanceSpan
import com.bluetriangle.analytics.performancemonitor.monitors.MemoryWarningReporter
import com.bluetriangle.analytics.screenTracking.ActivityLifecycleTracker
import com.bluetriangle.analytics.screenTracking.BTTScreenLifecycleTracker
import com.bluetriangle.analytics.screenTracking.FragmentLifecycleTracker
import com.bluetriangle.analytics.sessionmanager.DisabledModeSessionManager
import com.bluetriangle.analytics.sessionmanager.ISessionManager
import com.bluetriangle.analytics.sessionmanager.SessionData
import com.bluetriangle.analytics.sessionmanager.SessionManager
import com.bluetriangle.analytics.thirdpartyintegration.ClaritySessionConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * The tracker is a global object responsible for taking submitted timers and reporting them to the cloud server via a
 * background thread.
 */
class Tracker private constructor(
    application: Application, configuration: BlueTriangleConfiguration
) {
    internal var performanceMonitor: PerformanceMonitor? = null
        @Synchronized set

    private var anrManager: AnrManager? = null
        @Synchronized set

    /**
     * Weak reference to Android application context
     */
    private val context: WeakReference<Context>

    private var timerStack = ArrayDeque<WeakReference<Timer>>()
    /**
     * The tracker's configuration
     */
    val configuration: BlueTriangleConfiguration

    /**
     * A map of fields that should be applied to all timers such as
     */
    val globalFields: MutableMap<String, String> = HashMap(8)

    /**
     * A map of extended custom variables
     */
    private val customVariables: MutableMap<String, String> = HashMap()

    /**
     * Executor service to queue and submit timers
     */
    internal val trackerExecutor: TrackerExecutor

    /**
     * Performance monitoring threads
     */
    private val performanceSpans = HashMap<String, PerformanceSpan>()

    /**
     * Captured requests awaiting to be bulk sent to Blue Triangle API
     */
    private val capturedRequests = ConcurrentHashMap<Long, CapturedRequestCollection>()

    internal var screenTrackMonitor: BTTScreenLifecycleTracker? = null
        @Synchronized set

    private var activityLifecycleTracker: ActivityLifecycleTracker? = null
        @Synchronized set

    internal var networkTimelineTracker: NetworkTimelineTracker? = null
        @Synchronized set

    internal var networkStateMonitor: NetworkStateMonitor? = null
        @Synchronized set

    internal var deviceInfoProvider: IDeviceInfoProvider

    internal var anrReporter: ANRReporter

    internal var memoryWarningReporter: MemoryWarningReporter

    private val claritySessionConnector:ClaritySessionConnector
    internal val appVersion: String
    internal var firstInstallTime: Long? = null
        @Synchronized set

    internal var appLaunchReporter: AppLaunchReporter

    private var launchReporter: LaunchReporter? = null

    private var globalPropertiesStore: GlobalPropertiesStore

    internal var checkoutEventReporter: CheckoutEventReporter? = null

    internal var breadcrumbsManager: BreadcrumbsManager? = null

    private val sharedPreferences: SharedPreferences?
        get() {
            val context = context.get() ?: return null
            return context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
        }

    init {
        this.context = WeakReference(application.applicationContext)
        this.configuration = configuration
        this.deviceInfoProvider = DeviceInfoProvider
        this.anrReporter = ANRReporter(deviceInfoProvider)
        this.appLaunchReporter = AppLaunchReporter(configuration.logger, application.applicationContext, deviceInfoProvider, configuration.forceRestartDuration)
        this.memoryWarningReporter = MemoryWarningReporter(deviceInfoProvider)
        this.globalPropertiesStore = GlobalPropertiesStore(application.applicationContext)

        appVersion = Utils.getAppVersion(application.applicationContext)

        trackerExecutor = TrackerExecutor(configuration)

        claritySessionConnector = ClaritySessionConnector(configuration.logger)

        initializeGlobalFields()

        setGlobalUserId(globalUserId)
        configuration.globalUserId = globalUserId

        enable()
        Log.d("BlueTriangle","BlueTriangleSDK Initialized: $configuration")
    }

    private fun enableLaunchMonitor() {
        LaunchMonitor.init()
        LaunchMonitor.instance?.let {
            AppEventHub.instance.addConsumer(it)
            logLaunchMonitorErrors()
            launchReporter = LaunchReporter(configuration.logger, it)
            launchReporter?.start()
        }
    }

    private fun disableLaunchMonitor() {
        launchReporter?.stop()
        launchReporter = null
        LaunchMonitor.instance?.let {
            AppEventHub.instance.removeConsumer(it)
        }
        LaunchMonitor.clearInstance()
    }

    private fun logLaunchMonitorErrors() {
        val logs = LaunchMonitor.instance?.logs?:return
        for (log in logs) {
            configuration.logger?.log(log.level, log.message)
        }
        LaunchMonitor.instance?.clearLogs()
    }

    @Synchronized
    private fun enable() {
        val sessionData = sessionManager.sessionData
        setSessionId(sessionData.sessionId)
        this.configuration.updateConfiguration(sessionData)

        checkoutEventReporter = CheckoutEventReporter(sessionData.checkoutConfig)

        if(configuration.isScreenTrackingEnabled) {
            initializeScreenTracker()
        }

        if (configuration.isTrackAnrEnabled) {
            initializeANRMonitor()
        }

        if (configuration.isTrackCrashesEnabled) {
            trackCrashes()
        }
        if(configuration.isPerformanceMonitorEnabled) {
            startPerformanceMonitoring()
        }
        if(configuration.isLaunchTimeEnabled) {
            enableLaunchMonitor()
        }
        initializeNetworkStateTracking()

        (context.get()?.applicationContext as? Application)?.let {
            LifecycleRegistry.install(it)
        }
        if(sessionData.breadcrumbsConfig.isEnabled) {
            enableBreadcrumbs(sessionData.breadcrumbsConfig, configuration.shouldDetectTap)
        }

        if (configuration.isForceRestartEnable) {
            appLaunchReporter.setForceRestartDuration(sessionData.forceRestartDuration)
            startAppLaunchReporter()
        }

        checkAppVersion()
        configuration.logger?.debug("SDK is enabled")
    }

    private fun enableBreadcrumbs(breadcrumbsConfig: BreadcrumbsConfig, shouldDetectTap: Boolean) {
        breadcrumbsManager = BreadcrumbsManager(breadcrumbsConfig, shouldDetectTap, context)
        breadcrumbsManager?.install()
    }

    private fun disableBreadcrumbs() {
        breadcrumbsManager?.uninstall()
        breadcrumbsManager = null
    }

    private fun checkAppVersion() {
        sharedPreferences?.let {
            val lastAppVersion = it.getString(APP_VERSION, null)
            if(lastAppVersion == null) {
                context.get()?.let { ct ->
                    val installTime: Long =
                        ct.packageManager.getPackageInfo(ct.packageName, 0).firstInstallTime
                    val updateTime: Long =
                        ct.packageManager.getPackageInfo(ct.packageName, 0).lastUpdateTime
                    if (installTime == updateTime) {
                        firstInstallTime = installTime
                        SDKEventHub.instance.onAppInstall(appVersion)
                    }
                }
            } else if(lastAppVersion != appVersion) {
                SDKEventHub.instance.onAppUpdate(lastAppVersion, appVersion)
            }
            it.edit {
                putString(APP_VERSION, appVersion)
            }
        }
    }

    @Synchronized
    private fun disable() {
        performanceSpans.forEach {
            it.value.stop()
        }

        checkoutEventReporter = null

        stopPerformanceMonitoring()
        deInitializeScreenTracker()
        deInitializeANRMonitor()
        stopTrackCrashes()
        deInitializeNetworkStateTracking()
        disableLaunchMonitor()
        LifecycleRegistry.uninstall()
        disableBreadcrumbs()
        stopAppLaunchReporter()
        configuration.logger?.debug("SDK is disabled.")
    }

    private fun startPerformanceMonitoring() {
        performanceMonitor = PerformanceMonitor(configuration)
        performanceMonitor?.start()
    }

    private fun stopPerformanceMonitoring() {
        performanceMonitor?.stopRunning()
        performanceMonitor = null
    }

    private fun startAppLaunchReporter() {
        appLaunchReporter.start()
    }

    private fun stopAppLaunchReporter() {
        appLaunchReporter.stop()
    }

    fun trackCrashes() {
        if (Thread.getDefaultUncaughtExceptionHandler() !is BtCrashHandler) {
            Thread.setDefaultUncaughtExceptionHandler(
                BtCrashHandler(
                    configuration, deviceInfoProvider
                )
            )
        }
    }

    private fun stopTrackCrashes() {
        (Thread.getDefaultUncaughtExceptionHandler() as? BtCrashHandler)?.let {
            Thread.setDefaultUncaughtExceptionHandler(it.defaultUEH)
        }
    }

    private fun initializeGlobalFields() {
        val appContext = context.get()?.applicationContext ?: return
        val isTablet = Utils.isTablet(appContext)

        globalFields.apply {
            configuration.siteId?.let { put(Timer.FIELD_SITE_ID, it) }
            put(
                Timer.FIELD_DEVICE,
                if (isTablet) Constants.DEVICE_TABLET else Constants.DEVICE_MOBILE
            )
            put(Timer.FIELD_BROWSER_VERSION, "${Constants.BROWSER}-$appVersion-${Utils.os}")
            putAll(DEFAULT_VALUES)

            val globalProperties = globalPropertiesStore.loadGlobalProperties()
            put(Timer.FIELD_CAMPAIGN_MEDIUM, globalProperties.campaignMedium)
            put(Timer.FIELD_CAMPAIGN_NAME, globalProperties.campaignName)
            put(Timer.FIELD_CAMPAIGN_SOURCE, globalProperties.campaignSource)

            put(Timer.FIELD_AB_TEST_ID, globalProperties.abTestIdentifier)
            put(Timer.FIELD_DATACENTER, globalProperties.dataCenter)

            putAll(globalProperties.customCategories.mapKeys { it.key.fieldName })
        }
    }

    private fun initializeANRMonitor() {
        anrManager = AnrManager(configuration)
        anrManager?.start()
    }

    private fun deInitializeANRMonitor() {
        anrManager?.stop()
        anrManager = null
    }

    private fun initializeScreenTracker() {
        if(screenTrackMonitor != null) return

        screenTrackMonitor = BTTScreenLifecycleTracker(
            configuration.isScreenTrackingEnabled,
            configuration.isGroupingEnabled,
            configuration.groupingIdleTime,
            sessionManager.sessionData.ignoreScreens
        ).also {
            val fragmentLifecycleTracker = FragmentLifecycleTracker(it)
            activityLifecycleTracker = ActivityLifecycleTracker(
                configuration, it, fragmentLifecycleTracker
            )
            (context.get()?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
                activityLifecycleTracker
            )
        }
    }

    fun setGroupName(groupName: String) {
        screenTrackMonitor?.setGroupName(groupName)
    }

    fun setNewGroup(groupName: String) {
        screenTrackMonitor?.setNewGroup(groupName)
    }

    private fun deInitializeScreenTracker() {
        activityLifecycleTracker?.let {
            (context.get()?.applicationContext as? Application)?.unregisterActivityLifecycleCallbacks(
                it
            )
            it.unregister()
        }
        screenTrackMonitor?.destroy()
        screenTrackMonitor = null
        activityLifecycleTracker = null
    }

    private fun initializeNetworkStateTracking() {
        if (!configuration.isTrackNetworkStateEnabled) return
        if (networkStateMonitor != null) {
            configuration.logger?.error("Network state tracking is already enabled.")
            return
        }

        val appContext = context.get()

        if (appContext == null) {
            configuration.logger?.error("Unable to start network state tracking: Context is null")
            return
        }

        val hasNetworkStatePermission = ContextCompat.checkSelfPermission(
            appContext, ACCESS_NETWORK_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasNetworkStatePermission) {
            configuration.logger?.error("Unable to start network state tracking: Missing permission (ACCESS_NETWORK_STATE)")
            return
        }

        try {
            networkStateMonitor = NetworkStateMonitor(configuration.logger, appContext)
            networkTimelineTracker = NetworkTimelineTracker(networkStateMonitor!!)
            configuration.logger?.debug("Network state tracking started.")
        } catch (e: Exception) {
            configuration.logger?.error("Unable to start network state tracking: ${e.message}")
        }
    }

    private fun deInitializeNetworkStateTracking() {
        networkStateMonitor?.stop()
        networkTimelineTracker?.stop()
        networkStateMonitor = null
        networkTimelineTracker = null
    }

    fun setMostRecentTimer(timer: Timer) {
        synchronized(timerStack) {
            timerStack.addLast(WeakReference(timer))
        }
    }

    fun removeFromTimerStack(timer: Timer) {
        synchronized(timerStack) {
            timerStack.removeAll { it.get() == timer || it.get() == null }
        }
    }

    fun getMostRecentTimer(): Timer? {
        return synchronized(timerStack) {
            runCatching { timerStack.lastOrNull()?.get() }.getOrNull()
        }
    }

    /**
     * Get the stored global user ID or generate and persist if not found
     *
     * @return the global user ID
     */
    private val globalUserId: String
        get() {
            var globalUserId: String? = null
            val prefs = sharedPreferences ?: return Utils.generateRandomId()

            if (prefs.contains(Timer.FIELD_GLOBAL_USER_ID)) {
                globalUserId = prefs.getString(Timer.FIELD_GLOBAL_USER_ID, null)
            }
            if (globalUserId.isNullOrBlank()) {
                globalUserId = Utils.generateRandomId()
                prefs.edit { putString(Timer.FIELD_GLOBAL_USER_ID, globalUserId) }
            }
            return globalUserId
        }

    internal fun createPerformanceSpan(): PerformanceSpan {
        val performanceSpan = PerformanceSpan(configuration)
        performanceSpans[performanceSpan.id] = performanceSpan
        return performanceSpan
    }

    internal fun getPerformanceSpan(id: String): PerformanceSpan? {
        return performanceSpans[id]
    }

    internal fun clearPerformanceSpan(id: String) {
        performanceSpans.remove(id)
    }

    /**
     * Submit a timer to the tracker to be sent to the cloud server.
     *
     *
     * If the timer has not been ended, it will be ended on submit.
     *
     * @param timer The timer to submit
     */
    fun submitTimer(timer: Timer) {
        val timerRunnable = prepareTimerRunnable(timer)
        trackerExecutor.submit(timerRunnable)
    }

    internal fun prepareTimerRunnable(timer: Timer, shouldSendCapturedRequests: Boolean = true): TimerRunnable {
        if (!timer.hasEnded()) {
            timer.end()
        }
        if (timer.nativeAppProperties.loadTime == null) {
            timer.generateNativeAppProperties()
        }
        timer.setWCD(configuration.shouldSampleNetwork)
        claritySessionConnector.refreshClaritySessionUrlCustomVariable()

        applyGlobalFields(timer)
        loadCustomVariables(timer)
        timer.nativeAppProperties.add(deviceInfoProvider.getDeviceInfo())
        timer.setField(FIELD_SESSION_ID, sessionManager.sessionData.sessionId)
        return TimerRunnable(configuration, timer, shouldSendCapturedRequests)
    }

    internal fun applyGlobalFields(timer: Timer) {
        synchronized(globalFields) {
            globalFields.forEach {
                timer.setFieldIfNotSet(it.key, it.value)
            }
        }
    }

    internal fun loadCustomVariables(timer: Timer) {
        synchronized(customVariables) {
            if (customVariables.isNotEmpty()) {
                kotlin.runCatching {
                    val extendedCustomVariables = JSONObject(customVariables as Map<*, *>?).toString()
                    if (extendedCustomVariables.length > Constants.EXTENDED_CUSTOM_VARIABLE_MAX_PAYLOAD) {
                        configuration.logger?.warn("Dropping extended custom variables for $timer. Payload ${extendedCustomVariables.length} exceeds max size of ${Constants.EXTENDED_CUSTOM_VARIABLE_MAX_PAYLOAD}")
                    } else {
                        timer.setField(Timer.FIELD_EXTENDED_CUSTOM_VARIABLES, extendedCustomVariables)
                    }
                }
            }
        }
    }

    internal var lastTouchEventTimestamp = 0L

    internal fun registerTouchEvent() {
        lastTouchEventTimestamp = System.currentTimeMillis()
    }
    /**
     * Submit a captured network request to the tracker to send to
     *
     * @param capturedRequest
     */
    @Synchronized
    fun submitCapturedRequest(capturedRequest: CapturedRequest?) {
        if (capturedRequest == null) return

        SDKEventHub.instance.onNetworkRequestCaptured(capturedRequest)
        checkoutEventReporter?.onCheckoutEvent(CheckoutEvent.NetworkEvent(capturedRequest.url, capturedRequest.responseStatusCode))

        if (configuration.shouldSampleNetwork) {
            getMostRecentTimer()?.let { timer ->
                configuration.logger?.debug("Network Request Captured: $capturedRequest for $timer")
                capturedRequest.setNavigationStart(timer.start)
                if (capturedRequests.containsKey(timer.start)) {
                    capturedRequests[timer.start]!!.add(capturedRequest)
                } else {
                    val capturedRequestCollection = CapturedRequestCollection(
                        configuration.siteId.toString(),
                        timer.start.toString(),
                        getTimerValue(Timer.FIELD_PAGE_NAME, timer),
                        getTimerValue(Timer.FIELD_CONTENT_GROUP_NAME, timer),
                        getTimerValue(Timer.FIELD_TRAFFIC_SEGMENT_NAME, timer),
                        configuration.sessionId.toString(),
                        globalFields[Timer.FIELD_BROWSER_VERSION]!!,
                        globalFields[Timer.FIELD_DEVICE]!!,
                        deviceInfoProvider = deviceInfoProvider,
                        capturedRequest
                    )
                    capturedRequests[timer.start] = capturedRequestCollection
                }
            }
        }
    }

    /**
     * Returns a list of captured request collections for the current timer as well as all past timers to send
     */
    @Synchronized
    fun getCapturedRequestCollectionsForTimer(timer: Timer): List<CapturedRequestCollection> {
        val keysToSend = capturedRequests.keys().toList().filter { it <= timer.start }
        val capturedRequestCollections = mutableListOf<CapturedRequestCollection>()
        keysToSend.forEach {
            capturedRequests.remove(it)
                ?.let { collection -> capturedRequestCollections.add(collection) }
        }
        return capturedRequestCollections.toList()
    }

    internal fun getTimerValue(fieldName: String, timer: Timer?): String {
        if (timer != null) {
            val value = timer.getField(fieldName)
            if (value != null && !TextUtils.isEmpty(value)) {
                return value
            }
        }
        val value = globalFields[fieldName]
        return if (value != null && !TextUtils.isEmpty(value)) {
            value
        } else ""
    }

    /**
     * Submit a cached payload to the executor
     *
     * @param payload cached payload to retry sending
     */
    fun submitPayload(payload: Payload) {
        trackerExecutor.submit(PayloadRunnable(configuration, payload))
    }

    /**
     * set the current session ID for this tracker
     *
     * @param sessionId session ID to send with all timers submitted
     */
    fun setSessionId(sessionId: String) {
        setGlobalField(FIELD_SESSION_ID, sessionId)
    }

    /**
     * set the global user ID for this tracker
     *
     * @param globalUserId the global user ID to send with all timers submitted
     */
    fun setGlobalUserId(globalUserId: String) {
        setGlobalField(Timer.FIELD_GLOBAL_USER_ID, globalUserId)
    }

    /**
     * Set the value for AB test identifier
     * This will be applied to all timers that are submitted after calling this method.
     * This value will remain set until changed explicitly.
     * Setting null value clears the AB test identifier value.
     *
     * @param abTestIdentifier the AB test identifier
     */
    fun setSessionAbTestIdentifier(abTestIdentifier: String?) {
        if(abTestIdentifier == null) {
            clearGlobalField(Timer.FIELD_AB_TEST_ID)
            configuration.logger?.debug("Cleared AB test identifier value")
        } else {
            setGlobalField(Timer.FIELD_AB_TEST_ID, abTestIdentifier.take(MAX_FIELD_CHAR_LENGTH))
            configuration.logger?.debug("Updated AB test identifier value to $abTestIdentifier")
        }
        globalPropertiesStore.setAbTestIdentifier(abTestIdentifier)
    }

    /**
     * Set the value for data center
     * This will be applied to all timers that are submitted after calling this method.
     * This value will remain set until changed explicitly.
     * Setting null value clears the data center value.
     *
     * @param dataCenter the value for the data center
     */
    fun setSessionDataCenter(dataCenter: String?) {
        if(dataCenter == null) {
            clearGlobalField(Timer.FIELD_DATACENTER)
            configuration.logger?.debug("Cleared data center value")
        } else {
            setGlobalField(Timer.FIELD_DATACENTER, dataCenter.take(MAX_FIELD_CHAR_LENGTH))
            configuration.logger?.debug("Updated data center value to $dataCenter")
        }
        globalPropertiesStore.setDataCenter(dataCenter)
    }

    /**
     * Set this session's traffic segment name
     *
     * @param trafficSegmentName name of the traffic segment for this session
     */
    fun setSessionTrafficSegmentName(trafficSegmentName: String) {
        setGlobalField(Timer.FIELD_TRAFFIC_SEGMENT_NAME, trafficSegmentName.take(MAX_FIELD_CHAR_LENGTH))
    }

    /**
     * Set the value for campaign name
     * This will be applied to all timers that are submitted after calling this method.
     * This value will remain set until changed explicitly.
     * Setting null value clears the campaign name value.
     *
     * @param campaignName name of campaign
     */
    fun setSessionCampaignName(campaignName: String?) {
        if(campaignName == null) {
            clearGlobalField(Timer.FIELD_CAMPAIGN_NAME)
            configuration.logger?.debug("Cleared campaign name value")
        } else {
            setGlobalField(Timer.FIELD_CAMPAIGN_NAME, campaignName.take(MAX_FIELD_CHAR_LENGTH))
            configuration.logger?.debug("Updated campaign name value to $campaignName")
        }
        globalPropertiesStore.setCampaignName(campaignName)
    }

    /**
     * Set the value for campaign source
     * This will be applied to all timers that are submitted after calling this method.
     * This value will remain set until changed explicitly.
     * Setting null value clears the campaign source value.
     *
     * @param campaignSource source of campaign
     */
    fun setSessionCampaignSource(campaignSource: String?) {
        if(campaignSource == null) {
            clearGlobalField(Timer.FIELD_CAMPAIGN_SOURCE)
            configuration.logger?.debug("Cleared campaign source value")
        } else {
            setGlobalField(Timer.FIELD_CAMPAIGN_SOURCE, campaignSource.take(MAX_FIELD_CHAR_LENGTH))
            configuration.logger?.debug("Updated campaign source value to $campaignSource")
        }
        globalPropertiesStore.setCampaignSource(campaignSource)
    }

    /**
     * Set the value for campaign medium
     * This will be applied to all timers that are submitted after calling this method.
     * This value will remain set until changed explicitly.
     * Setting null value clears the campaign medium value.
     *
     * @param campaignMedium medium of campaign
     */
    fun setSessionCampaignMedium(campaignMedium: String?) {
        if(campaignMedium == null) {
            clearGlobalField(Timer.FIELD_CAMPAIGN_MEDIUM)
            configuration.logger?.debug("Cleared campaign medium value")
        } else {
            setGlobalField(Timer.FIELD_CAMPAIGN_MEDIUM, campaignMedium.take(MAX_FIELD_CHAR_LENGTH))
            configuration.logger?.debug("Updated campaign medium value to $campaignMedium")
        }
        globalPropertiesStore.setCampaignMedium(campaignMedium)
    }

    /**
     * Set a global field to be applied to all trackers
     *
     * @param fieldName the name of the field
     * @param value     the value to set for the given field
     */
    fun setGlobalField(fieldName: String, value: String) {
        synchronized(globalFields) { globalFields.put(fieldName, value) }
    }

    /**
     * Checks if a field is set or not. i.e. it still has the default value or doesn't exist at all
     *
     * @param fieldName name of field to remove
     * @return true if the field exists or false otherwise
     */
    internal fun isGlobalFieldSet(fieldName: String): Boolean {
        return synchronized(globalFields) {
            val exists = globalFields.containsKey(fieldName)
            val hasDefaultValue = DEFAULT_VALUES.containsKey(fieldName) && globalFields[fieldName] == DEFAULT_VALUES[fieldName]

            exists && !hasDefaultValue
        }
    }

    fun onScreenPause(timer: Timer?) {
        // Save breadcrumbs on screen pause
        breadcrumbsManager?.dump()

        val context = context.get() ?: return
        val prefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)

        // Save current time as last foreground time
        prefs?.edit {
            putLong(Constants.APP_LAST_FOREGROUND_TIME, System.currentTimeMillis())
        }

        // Save pageName/PageType/txnName of most recent timer
        timer?.let { timer ->
            prefs?.edit {
                putString(FIELD_PAGE_NAME, timer.getField(FIELD_PAGE_NAME))
                putString(FIELD_CONTENT_GROUP_NAME, timer.getField(FIELD_CONTENT_GROUP_NAME))
                putString(FIELD_TRAFFIC_SEGMENT_NAME, timer.getField(FIELD_TRAFFIC_SEGMENT_NAME))
            }
        }
    }

    /**
     * Set a global field to be applied to all trackers
     *
     * @param fieldName the name of the field
     * @param value     the value to set for the given field
     */
    fun setGlobalField(fieldName: String, value: Int) {
        setGlobalField(fieldName, value.toString())
    }

    /**
     * Set a global field to be applied to all trackers
     *
     * @param fieldName the name of the field
     * @param value     the value to set for the given field
     */
    fun setGlobalField(fieldName: String, value: Double) {
        setGlobalField(fieldName, value.toString())
    }

    /**
     * Set a global field to be applied to all trackers
     *
     * @param fieldName the name of the field
     * @param value     the value to set for the given field
     */
    fun setGlobalField(fieldName: String, value: Float) {
        setGlobalField(fieldName, value.toString())
    }

    /**
     * Set a global field to be applied to all trackers
     *
     * @param fieldName the name of the field
     * @param value     the value to set for the given field
     */
    fun setGlobalField(fieldName: String, value: Boolean) {
        setGlobalField(fieldName, value.toString())
    }

    /**
     * Set a global field to be applied to all trackers
     *
     * @param fieldName the name of the field
     * @param value     the value to set for the given field
     */
    fun setGlobalField(fieldName: String, value: Long) {
        setGlobalField(fieldName, value.toString())
    }

    /**
     * Get the current value of a global field
     *
     * @param fieldName the name of the field to get
     * @return the value of the given field name or null if doesn't exist
     */
    fun getGlobalField(fieldName: String): String? {
        return globalFields[fieldName]
    }

    /**
     * Clear a global field from being set on all trackers
     *
     * @param fieldName the name of the field to remove
     */
    fun clearGlobalField(fieldName: String) {
        synchronized(globalFields) { globalFields.remove(fieldName) }
    }

    @Synchronized
    internal fun updateSession(sessionData: SessionData) {
        val changes = StringBuilder()

        if(configuration.sessionId != sessionData.sessionId) {
            changes.append("\nsessionId: ${configuration.sessionId} -> ${sessionData.sessionId}")
            configuration.sessionId = sessionData.sessionId
        }

        if(configuration.networkSampleRate != sessionData.networkSampleRate) {
            changes.append("\nnetworkSampleRate: ${configuration.networkSampleRate} -> ${sessionData.networkSampleRate}")
            configuration.networkSampleRate = sessionData.networkSampleRate
        }

        if(configuration.shouldSampleNetwork != sessionData.shouldSampleNetwork) {
            changes.append("\nshouldSampleNetwork: ${configuration.shouldSampleNetwork} -> ${sessionData.shouldSampleNetwork}")
            configuration.shouldSampleNetwork = sessionData.shouldSampleNetwork
        }

        if(configuration.isGroupingEnabled != sessionData.enableGrouping) {
            changes.append("\nisGroupingEnabled: ${configuration.isGroupingEnabled} -> ${sessionData.enableGrouping}")
            configuration.isGroupingEnabled = sessionData.enableGrouping
            screenTrackMonitor?.groupingEnabled = configuration.isGroupingEnabled
            if(configuration.shouldDetectTap) {
                activityLifecycleTracker?.enableTapDetection()
            } else {
                activityLifecycleTracker?.disableTapDetection()
            }
        }

        if(configuration.groupingIdleTime != sessionData.groupingIdleTime) {
            changes.append("\ngroupingIdleTime: ${configuration.groupingIdleTime} -> ${sessionData.groupingIdleTime}")
            configuration.groupingIdleTime = sessionData.groupingIdleTime
            screenTrackMonitor?.groupIdleTime = configuration.groupingIdleTime
        }

        if(configuration.isGroupingTapDetectionEnabled != sessionData.enableGroupingTapDetection) {
            changes.append("\nenableGroupingTapDetection: ${configuration.isGroupingTapDetectionEnabled} -> ${sessionData.enableGroupingTapDetection}")
            configuration.isGroupingTapDetectionEnabled = sessionData.enableGroupingTapDetection
            if(configuration.shouldDetectTap) {
                activityLifecycleTracker?.enableTapDetection()
            } else {
                activityLifecycleTracker?.disableTapDetection()
            }
        }

        if(configuration.isTrackNetworkStateEnabled != sessionData.enableNetworkStateTracking) {
            changes.append("\nenableNetworkStateTracking: ${configuration.isTrackNetworkStateEnabled} -> ${sessionData.enableNetworkStateTracking}")
            configuration.isTrackNetworkStateEnabled = sessionData.enableNetworkStateTracking
            if(configuration.isTrackNetworkStateEnabled) {
                initializeNetworkStateTracking()
            } else {
                deInitializeNetworkStateTracking()
            }
        }

        if(configuration.isTrackCrashesEnabled != sessionData.enableCrashTracking) {
            changes.append("\nenableCrashTracking: ${configuration.isTrackCrashesEnabled} -> ${sessionData.enableCrashTracking}")
            configuration.isTrackCrashesEnabled = sessionData.enableCrashTracking
            if(configuration.isTrackCrashesEnabled) {
                trackCrashes()
            } else {
                stopTrackCrashes()
            }
        }

        if(configuration.isTrackAnrEnabled != sessionData.enableANRTracking) {
            changes.append("\nenableANRTracking: ${configuration.isTrackAnrEnabled} -> ${sessionData.enableANRTracking}")
            configuration.isTrackAnrEnabled = sessionData.enableANRTracking
            if(configuration.isTrackAnrEnabled) {
                initializeANRMonitor()
            } else {
                deInitializeANRMonitor()
            }
        }

        if(configuration.isMemoryWarningEnabled != sessionData.enableMemoryWarning) {
            changes.append("\nenableMemoryWarning: ${configuration.isMemoryWarningEnabled} -> ${sessionData.enableMemoryWarning}")
            configuration.isMemoryWarningEnabled = sessionData.enableMemoryWarning
        }

        if(configuration.isLaunchTimeEnabled != sessionData.enableLaunchTime) {
            changes.append("\nenableLaunchTime: ${configuration.isLaunchTimeEnabled} -> ${sessionData.enableLaunchTime}")
            configuration.isLaunchTimeEnabled = sessionData.enableLaunchTime
            if(configuration.isLaunchTimeEnabled) {
                enableLaunchMonitor()
            } else {
                disableLaunchMonitor()
            }
        }

        if (configuration.forceRestartDuration != sessionData.forceRestartDuration) {
            changes.append("\nforceRestartDuration: ${configuration.forceRestartDuration} -> ${sessionData.forceRestartDuration}")
            configuration.forceRestartDuration = sessionData.forceRestartDuration
            appLaunchReporter.setForceRestartDuration(sessionData.forceRestartDuration)
        }

        if (configuration.isForceRestartEnable != sessionData.enableForceRestart) {
            changes.append("\nenableForceRestart: ${configuration.isForceRestartEnable} -> ${sessionData.enableForceRestart}")
            configuration.isForceRestartEnable = sessionData.enableForceRestart
            if (configuration.isForceRestartEnable) {
                startAppLaunchReporter()
            } else {
                stopAppLaunchReporter()
            }
        }

        if(configuration.isWebViewStitchingEnabled != sessionData.enableWebViewStitching) {
            changes.append("\nenableWebViewStitching: ${configuration.isWebViewStitchingEnabled} -> ${sessionData.enableWebViewStitching}")
            configuration.isWebViewStitchingEnabled = sessionData.enableWebViewStitching
        }

        if(configuration.isScreenTrackingEnabled != sessionData.enableScreenTracking) {
            changes.append("\nisScreenTrackingEnabled: ${configuration.isScreenTrackingEnabled} -> ${sessionData.enableScreenTracking}")
            configuration.isScreenTrackingEnabled = sessionData.enableScreenTracking

            if(configuration.isScreenTrackingEnabled) {
                initializeScreenTracker()
            } else {
                deInitializeScreenTracker()
            }
        } else if(screenTrackMonitor?.ignoreScreens != sessionData.ignoreScreens) {
            screenTrackMonitor?.ignoreScreens = sessionData.ignoreScreens
        }

        if(checkoutEventReporter?.config != sessionData.checkoutConfig) {
            changes.append("\ncheckoutConfig: ${checkoutEventReporter?.config} -> ${sessionData.checkoutConfig}")
            checkoutEventReporter?.updateConfig(sessionData.checkoutConfig)
        }

        val isBreadcrumbsEnabled = breadcrumbsManager != null
        if(sessionData.breadcrumbsConfig.isEnabled != isBreadcrumbsEnabled) {
            changes.append("\nenableBreadcrumbs: $isBreadcrumbsEnabled -> ${sessionData.breadcrumbsConfig.isEnabled}")
            if(sessionData.breadcrumbsConfig.isEnabled) {
                enableBreadcrumbs(sessionData.breadcrumbsConfig, configuration.shouldDetectTap)
            } else {
                disableBreadcrumbs()
            }
        }

        if(sessionData.breadcrumbsConfig.ignoredFeatures != breadcrumbsManager?.config?.ignoredFeatures || configuration.isGroupingTapDetectionEnabled != sessionData.enableGroupingTapDetection) {
            changes.append("\nignoreBreadcrumbs: ${breadcrumbsManager?.config?.ignoredFeatures} -> ${sessionData.breadcrumbsConfig.ignoredFeatures}")
            breadcrumbsManager?.updateConfig(sessionData.breadcrumbsConfig, configuration.shouldDetectTap)
        }
        val changesString = changes.toString()
        if(changesString.isNotEmpty()) {
            configuration.logger?.debug("Updated configuration $changesString")
            setSessionId(sessionData.sessionId)
            BTTWebViewTracker.updateSession()
        }
    }

    /**
     * Set custom variable for the given name to the given value
     * @param name name of the custom variable to set
     * @param value value of the custom variable to set
     */
    fun setCustomVariable(name: String, value: String) {
        if (value.length > Constants.EXTENDED_CUSTOM_VARIABLE_MAX_LENGTH) {
            configuration.logger?.warn("Extended Custom Variable \"$name\" exceeds max length of ${Constants.EXTENDED_CUSTOM_VARIABLE_MAX_LENGTH}")
        }
        synchronized(customVariables) {
            customVariables.put(name, value)
        }
    }

    /**
     * Set custom variable for the given name to the given value
     * @param name name of the custom variable to set
     * @param value value of the custom variable to set
     */
    fun setCustomVariable(name: String, value: Number) {
        setCustomVariable(name, value.toString())
    }

    /**
     * Set custom variable for the given name to the given value
     * @param name name of the custom variable to set
     * @param value value of the custom variable to set
     */
    fun setCustomVariable(name: String, value: Boolean) {
        setCustomVariable(name, value.toString())
    }

    /**
     * Set all of the variables in the given flat map.  Values must be a String, Number, or Boolean.
     * Does not clear any existing variables.
     */
    fun setCustomVariables(variables: Map<String, String>) {
        for (entry in variables.entries.iterator()) {
            if (entry.value.length > Constants.EXTENDED_CUSTOM_VARIABLE_MAX_LENGTH) {
                configuration.logger?.warn("Extended Custom Variable \"${entry.key}\" exceeds max length of ${Constants.EXTENDED_CUSTOM_VARIABLE_MAX_LENGTH}")
            }
        }
        synchronized(customVariables) {
            customVariables.putAll(variables)
        }
    }

    /**
     * Return the named custom variable, or null
     *
     * @param name of the custom variable to return
     * @return the value of the custom variable or null
     */
    fun getCustomVariable(name: String): String? {
        return kotlin.runCatching { customVariables[name] }.getOrNull()
    }

    /**
     * Get the current custom variables
     * @return a copy of the current custom variables
     */
    fun getCustomVariables(): Map<String, String> {
        return customVariables.toMap()
    }

    /**
     * Clears the given custom variable, if set
     * @param name key of the custom variable to remove
     */
    fun clearCustomVariable(name: String) {
        synchronized(customVariables) {
            customVariables.remove(name)
        }
    }

    /**
     * Removes all custom variables
     */
    fun clearAllCustomVariables() {
        synchronized(customVariables) {
            customVariables.clear()
        }
    }

    /**
     * Set the value for custom category 1
     * Custom category will be applied to all timers that are submitted after calling this method.
     * This value will remain set until changed explicitly.
     * Setting null value clears the custom category value.
     *
     * @param value value of the custom category
     */
    fun setCustomCategory1(value: String?) {
        synchronized(this) {
            if(value == null) {
                clearGlobalField(CustomCategory.Category1.fieldName)
                configuration.logger?.debug("Cleared custom category 1 value")
            } else {
                setGlobalField(CustomCategory.Category1.fieldName, value)
                configuration.logger?.debug("Updated custom category 1 value to $value")
            }
            globalPropertiesStore.setCustomCategory(CustomCategory.Category1, value)
        }
    }

    /**
     * Set the values for custom category 2
     * Custom category will be applied to timers that are submitted after calling this method.
     * These values will remain set until changed explicitly.
     * Setting null value clears the custom category value.
     *
     * @param value value of the custom category
     */
    fun setCustomCategory2(value: String?) {
        if(value == null) {
            clearGlobalField(CustomCategory.Category2.fieldName)
            configuration.logger?.debug("Cleared custom category 2 value")
        } else {
            setGlobalField(CustomCategory.Category2.fieldName, value)
            configuration.logger?.debug("Updated custom category 2 value to $value")
        }
        globalPropertiesStore.setCustomCategory(CustomCategory.Category2, value)
    }

    /**
     * Set the values for custom category 3
     * Custom category will be applied to timers that are submitted after calling this method.
     * These values will remain set until changed explicitly.
     * Setting null value clears the custom category value.
     *
     * @param value value of the custom category
     */
    fun setCustomCategory3(value: String?) {
        if(value == null) {
            clearGlobalField(CustomCategory.Category3.fieldName)
            configuration.logger?.debug("Cleared custom category 3 value")
        } else {
            setGlobalField(CustomCategory.Category3.fieldName, value)
            configuration.logger?.debug("Updated custom category 3 value to $value")
        }
        globalPropertiesStore.setCustomCategory(CustomCategory.Category3, value)
    }

    /**
     * Set the values for custom category 4
     * Custom category will be applied to timers that are submitted after calling this method.
     * These values will remain set until changed explicitly.
     * Setting null value clears the custom category value.
     *
     * @param value value of the custom category
     */
    fun setCustomCategory4(value: String?) {
        if(value == null) {
            clearGlobalField(CustomCategory.Category4.fieldName)
            configuration.logger?.debug("Cleared custom category 4 value")
        } else {
            setGlobalField(CustomCategory.Category4.fieldName, value)
            configuration.logger?.debug("Updated custom category 4 value to $value")
        }
        globalPropertiesStore.setCustomCategory(CustomCategory.Category4, value)
    }

    /**
     * Set the values for custom category 5
     * Custom category will be applied to timers that are submitted after calling this method.
     * These values will remain set until changed explicitly.
     * Setting null value clears the custom category value.
     *
     * @param value value of the custom category
     */
    fun setCustomCategory5(value: String?) {
        if(value == null) {
            clearGlobalField(CustomCategory.Category5.fieldName)
            configuration.logger?.debug("Cleared custom category 5 value")
        } else {
            setGlobalField(CustomCategory.Category5.fieldName, value)
            configuration.logger?.debug("Updated custom category 5 value to $value")
        }
        globalPropertiesStore.setCustomCategory(CustomCategory.Category5, value)
    }

    fun trackError(message: String) {
        trackException(message, object : Throwable() {
            override fun fillInStackTrace(): Throwable {
                stackTrace = arrayOf()
                return this
            }
        })
    }

    /**
     * Manually track an error exception caught by your code
     *
     * @param message   optional message included with the stack trace
     * @param exception the exception to track
     */
    fun trackException(
        message: String?, exception: Throwable, errorType: BTErrorType = BTErrorType.NativeAppCrash
    ) {
        val timeStamp = System.currentTimeMillis().toString()
        val mostRecentTimer = getMostRecentTimer()
        mostRecentTimer?.generateNativeAppProperties()

        val stacktrace = Utils.exceptionToStacktrace(message, exception)
        trackerExecutor.submit(
            CrashRunnable(
                configuration,
                stacktrace,
                timeStamp,
                errorType,
                mostRecentTimer,
                deviceInfoProvider = deviceInfoProvider,
                breadcrumbs = breadcrumbsManager?.snapshot()
            )
        )
    }

    enum class BTErrorType(val event: BTTEvent? = null) {
        NativeAppCrash(BTTEvent.Crash),
        ANRWarning(BTTEvent.ANRWarning),
        MemoryWarning(BTTEvent.MemoryWarning),
        ForceRestart(BTTEvent.ForceRestart),
        BTTConfigUpdateError;

        val errorName: String
            get() = when(this) {
                BTTConfigUpdateError -> "BTTConfigUpdate"
                else -> event?.defaultPageName?:"Unknown"
            }
    }

    fun raiseTestException() {
        val a = 10
        val b = 0
        println(a / b)
    }

    companion object {
        internal const val SHARED_PREFERENCES_NAME = "BTT_SHARED_PREFERENCES"

        /**
         * String resource name for the site ID
         */
        private const val SITE_ID_RESOURCE_KEY = "btt_site_id"

        /**
         * Gets the singleton tracker instance to submit timers to. If not setup via the builder, it will be null.
         *
         * @return Singleton tracker instance or null if not built yet.
         */
        /**
         * Singleton instance of the tracker
         */
        @JvmStatic
        var instance: Tracker? = null
            private set

        /**
         * Initialize the tracker with default tracker URL and Site ID from string resources.
         *
         * @param application host application instance
         * @return the initialized tracker or null if no site ID
         */
        @JvmStatic
        fun init(application: Application): Tracker? {
            return init(application, BlueTriangleConfiguration())
        }

        /**
         * Initialize the tracker with default tracker URL and given Site ID
         *
         * @param application host application instance
         * @param siteId  Site ID to send with all timers
         * @return the initialized tracker or null if no site ID
         */
        @JvmStatic
        fun init(application: Application, siteId: String?): Tracker? {
            val configuration = BlueTriangleConfiguration()
            configuration.siteId = siteId
            return init(application, configuration)
        }

        /**
         * Initialize the tracker with default tracker URL and given Site ID
         *
         * @param application host application instance
         * @param siteId  Site ID to send with all timers
         * @param trackerUrl the URL to submit timer data
         * @return the initialized tracker or null if no site ID
         */
        @JvmStatic
        fun init(application: Application, siteId: String?, trackerUrl: String?): Tracker? {
            val configuration = BlueTriangleConfiguration()
            configuration.siteId = siteId
            if (!trackerUrl.isNullOrBlank()) {
                configuration.trackerUrl = trackerUrl
            }
            return init(application, configuration)
        }

        /**
         * Initialize the tracker with the given configuration
         *
         * @param application host application instance
         * @param configuration Blue Triangle Configuration
         * @return the initialized tracker or null if no site ID
         */
        @JvmStatic
        @Synchronized
        fun init(application: Application, configuration: BlueTriangleConfiguration): Tracker? {
            if (instance != null) {
                return instance
            }

            if (!validateAndInitializeConfiguration(application, configuration)) {
                return null
            }

            val defaultConfig = BTTRemoteConfiguration(
                networkSampleRate = configuration.networkSampleRate,
                ignoreScreens = listOf(),
                enableAllTracking = true,
                enableScreenTracking = configuration.isScreenTrackingEnabled,
                enableGrouping = configuration.isGroupingEnabled,
                groupingIdleTime = configuration.groupingIdleTime,
                enableGroupingTapDetection = configuration.isGroupingTapDetectionEnabled,
                enableNetworkStateTracking = configuration.isTrackNetworkStateEnabled,
                enableCrashTracking = configuration.isTrackCrashesEnabled,
                enableANRTracking = configuration.isTrackAnrEnabled,
                enableMemoryWarning = configuration.isMemoryWarningEnabled,
                enableLaunchTime = configuration.isLaunchTimeEnabled,
                enableWebViewStitching = configuration.isWebViewStitchingEnabled,
                checkoutConfig = CheckoutConfig.DEFAULT,
                breadcrumbsConfig = BreadcrumbsConfig.DEFAULT,
                configKey = DEFAULT_CONFIG_KEY,
                enableAppInstall = configuration.isAppInstallEnabled,
                enableForceRestart = configuration.isForceRestartEnable,
                forceRestartDuration = configuration.forceRestartDuration
            )

            initializeConfigurationUpdater(application, configuration, defaultConfig)

            if (configurationRepository.get().enableAllTracking) {
                initializeSessionManager(application, configuration, defaultConfig)
                instance = Tracker(application, configuration)
            } else {
                deInitializeSessionManager()
                configuration.logger?.debug("enableAllTracking is false, no need to initialize SDK")
            }

            observeEnableDisableSDK(application, configuration, defaultConfig)
            return instance
        }

        private fun validateAndInitializeConfiguration(
            application: Application, configuration: BlueTriangleConfiguration
        ): Boolean {
            initializeLogger(configuration)

            MetadataReader.applyMetadata(application, configuration)

            initializeAppName(application, configuration)
            initializeUserAgent(application, configuration)
            initializeCacheDirectory(application, configuration)
            checkAndInitializeSiteIDFromResources(application, configuration)

            // if still no site ID, log error
            if (configuration.siteId.isNullOrBlank()) {
                configuration.logger?.error("Site ID is required.")
                return false
            }
            return true
        }

        private fun initializeLogger(
            configuration: BlueTriangleConfiguration
        ) {
            if (configuration.isDebug) {
                configuration.logger = AndroidLogger(configuration.debugLevel)
            }
        }

        private fun initializeUserAgent(
            application: Application, configuration: BlueTriangleConfiguration
        ) {
            if (configuration.userAgent.isNullOrBlank()) {
                configuration.userAgent = Utils.buildUserAgent(application)
            }
        }

        private fun initializeAppName(
            application: Application, configuration: BlueTriangleConfiguration
        ) {
            if (configuration.applicationName.isNullOrBlank()) {
                configuration.applicationName = Utils.getAppNameAndOs(application)
            }
        }

        private fun initializeCacheDirectory(
            application: Application, configuration: BlueTriangleConfiguration
        ) {
            if (configuration.cacheDirectory.isNullOrBlank()) {
                val cacheDir = File(application.cacheDir, "bta")
                if (!cacheDir.exists()) {
                    if (!cacheDir.mkdir()) {
                        configuration.logger?.error("Error creating cache directory: ${cacheDir.absolutePath}")
                    }
                }
                configuration.cacheDirectory = cacheDir.absolutePath
            }
        }

        private fun checkAndInitializeSiteIDFromResources(
            application: Application, configuration: BlueTriangleConfiguration
        ) {
            // if site id is still not configured, try legacy resource string method
            if (configuration.siteId.isNullOrBlank()) {
                val resourceSiteID = Utils.getResourceString(application, SITE_ID_RESOURCE_KEY)
                if (!resourceSiteID.isNullOrBlank()) {
                    configuration.siteId = resourceSiteID
                }
            }
        }

        private var repositoryUpdatesJob: Job? = null

        @Suppress("OPT_IN_USAGE")
        private fun observeEnableDisableSDK(
            application: Application,
            configuration: BlueTriangleConfiguration,
            defaultConfig: BTTRemoteConfiguration
        ) {
            repositoryUpdatesJob?.cancel()
            repositoryUpdatesJob = GlobalScope.launch(Dispatchers.IO) {
                configurationRepository.getLiveUpdates(notifyCurrent = false).collect {
                    if (it?.enableAllTracking == true) {
                        if(instance == null) {
                            initializeSessionManager(application, configuration, defaultConfig = defaultConfig)
                            instance = init(application, configuration)
                        }
                    } else {
                        if(instance != null) {
                            instance?.disable()
                            deInitializeSessionManager()
                            instance = null
                        }
                    }

                    instance?.firstInstallTime?.let { installTime ->
                        if (it?.enableAppInstall == true) {
                            instance?.firstInstallTime = null
                            instance?.appLaunchReporter?.reportAppInstall(installTime)
                        }
                    }
                }
            }
        }

        private fun BlueTriangleConfiguration.updateConfiguration(sessionData: SessionData) {
            sessionId = sessionData.sessionId
            networkSampleRate = sessionData.networkSampleRate
            shouldSampleNetwork = sessionData.shouldSampleNetwork
            isGroupingEnabled = sessionData.enableGrouping
            groupingIdleTime = sessionData.groupingIdleTime
            isGroupingTapDetectionEnabled = sessionData.enableGroupingTapDetection
            isTrackNetworkStateEnabled = sessionData.enableNetworkStateTracking
            isTrackCrashesEnabled = sessionData.enableCrashTracking
            isTrackAnrEnabled = sessionData.enableANRTracking
            isMemoryWarningEnabled = sessionData.enableMemoryWarning
            isLaunchTimeEnabled = sessionData.enableLaunchTime
            isWebViewStitchingEnabled = sessionData.enableWebViewStitching
            isScreenTrackingEnabled = sessionData.enableScreenTracking
            isAppInstallEnabled = sessionData.enableAppInstall
            isForceRestartEnable = sessionData.enableForceRestart
            forceRestartDuration = sessionData.forceRestartDuration
        }

        private lateinit var configurationRepository: IBTTConfigurationRepository
        private lateinit var configurationUpdater: IBTTConfigurationUpdater
        private lateinit var sessionManager: ISessionManager

        private fun initializeConfigurationUpdater(
            application: Application, configuration: BlueTriangleConfiguration, defaultConfig: BTTRemoteConfiguration
        ) {
            configurationRepository = BTTConfigurationRepository(
                configuration.logger,
                application,
                configuration.siteId ?: "",
                defaultConfig = defaultConfig
            )

            val configUrl = "https://${configuration.siteId}.btttag.com/config.php?siteID=${configuration.siteId}&os=${Constants.OS}&osver=${Build.VERSION.RELEASE}&app=${Utils.getAppVersion(application)}&sdk=${BuildConfig.SDK_VERSION}"
            configurationUpdater = BTTConfigurationUpdater(
                logger = configuration.logger,
                repository = this.configurationRepository,
                fetcher = BTTConfigurationFetcher(configuration.logger, configUrl),
                60 * 60 * 1000
            )
        }

        private fun initializeSessionManager(
            application: Application,
            configuration: BlueTriangleConfiguration,
            defaultConfig: BTTRemoteConfiguration
        ) {
            if (::sessionManager.isInitialized) {
                AppEventHub.instance.removeConsumer(sessionManager)
            }
            this.sessionManager = SessionManager(
                application,
                configuration.siteId ?: "",
                configuration.sessionExpiryDuration,
                configurationRepository,
                configurationUpdater,
                defaultConfig = defaultConfig
            )
            AppEventHub.instance.addConsumer(this.sessionManager)
        }

        private fun deInitializeSessionManager() {
            if(::sessionManager.isInitialized) {
                sessionManager.endSession()
                AppEventHub.instance.removeConsumer(sessionManager)
            }
            sessionManager = DisabledModeSessionManager(configurationUpdater)
            AppEventHub.instance.addConsumer(sessionManager)
        }

        private val DEFAULT_VALUES: Map<String, String> = mapOf(
            Timer.FIELD_BROWSER to Constants.BROWSER,
            Timer.FIELD_NA_FLG to "1",
            Timer.FIELD_SDK_VERSION to BuildConfig.SDK_VERSION,
            Timer.FIELD_TRAFFIC_SEGMENT_NAME to Constants.DEFAULT_TRAFFIC_SEGMENT_NAME,
            Timer.FIELD_CONTENT_GROUP_NAME to Constants.DEFAULT_CONTENT_GROUP_NAME
        )

        internal fun getConfigKey(): String? {
            return sessionManager.sessionData.configKey.takeIf { it != UNKNOWN }
        }
    }

}
