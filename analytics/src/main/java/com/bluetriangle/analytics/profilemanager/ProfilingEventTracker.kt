package com.bluetriangle.analytics.profilemanager

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ProfilingManager
import android.os.ProfilingResult
import android.os.ProfilingTrigger
import android.util.Log
import androidx.annotation.RequiresApi
import com.bluetriangle.analytics.profilemanager.perfetto.PerfettoTraceParser
import java.io.File
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * Capture layer for ANR / OOM signals via:
 * - [ApplicationExitInfo] historical fatal ANRs / low-memory exits (API 30+)
 * - [ProfilingManager] result listening (API 35+) and system triggers (API 36+ ANR, API 37+ OOM)
 * - Live [ActivityManager] AnrWarningListener (API 37+)
 *
 * Listener callbacks are always delivered on the SDK background executor.
 *
 * **OOM note:** System OOM profiling dumps require the default
 * [Thread.UncaughtExceptionHandler] to remain in the chain so the platform can
 * complete its dump before process death. App handlers must call through to the
 * previous/default handler.
 */
object ProfilingEventTracker {
    private const val TAG = "ProfilingEventTracker"
    private const val TRACE_DIR = "exit_traces"
    private const val MAX_HISTORICAL_EXITS = 10
    private const val MAX_BUFFERED_EVENTS = 32
    private const val MAX_STACK_PREVIEW_CHARS = 8_192
    private const val MAX_CACHED_TRACE_FILES = 20
    private const val DEFAULT_RATE_LIMIT_HOURS = 1

    /** Filename marker for ANR system trigger results. */
    private const val ANR_TRIGGER_FILE_MARKER = "trigger-type-2"

    /** Filename marker for OOM system trigger results. */
    private const val OOM_TRIGGER_FILE_MARKER = "trigger-type-7"

    /** Tag prefix for app-requested near-ANR stack samples (not system ANR triggers). */
    private const val NEAR_ANR_STACK_TAG_PREFIX = "near-anr-stack-"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "profiler-sdk").apply { isDaemon = true }
    }

    private val listeners = CopyOnWriteArrayList<ProfilingEventListener>()
    private val eventBuffer = ArrayDeque<ProfilingEvent>(MAX_BUFFERED_EVENTS)
    private val bufferLock = Any()
    private val reportedExitKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val initialized = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var anrWarningRegistered = false

    @Volatile
    private var profilingResultsRegistered = false

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private val profilingResultConsumer = Consumer<ProfilingResult> { result ->
        onProfilingResultReceived(result)
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Initializes capture once per process. Subsequent calls are no-ops.
     *
     * @param rateLimitHours app-side rate limit for ProfilingManager triggers;
     *   use `0` only for lab/debug (disables app rate limiting; system limits still apply).
     *   Default is [DEFAULT_RATE_LIMIT_HOURS].
     */
    fun init(context: Context, rateLimitHours: Int = DEFAULT_RATE_LIMIT_HOURS) {
        appContext = context.applicationContext
        if (!initialized.compareAndSet(false, true)) {
            Log.d(TAG, "init() already called; ignoring")
            return
        }

        Log.i(TAG, "init() sdk=${Build.VERSION.SDK_INT} rateLimitHours=$rateLimitHours\n${capabilitySummary()}")

        // 1) Historical exits from prior process deaths (buffered for late UI listeners)
        checkLastExitReasons(context)

        // 2) ProfilingManager global listener + ANR / OOM triggers
        registerForAllProfilingResults(context)
        registerProfilingTriggers(context, rateLimitHours)

        // 3) Live pre-ANR warning (API 37+) — fires while the hang is ongoing
        registerAnrWarningListener(context)
    }

    /**
     * Unregisters ProfilingManager consumers/triggers and clears in-memory state.
     * Safe to call when not initialized. After [shutdown], [init] may be called again.
     *
     * Note: AnrWarningListener may remain registered if the platform provides no
     * unregister API; re-init will not double-register that listener.
     */
    fun shutdown(context: Context? = appContext) {
        if (!initialized.compareAndSet(true, false)) {
            Log.d(TAG, "shutdown() not initialized; ignoring")
            return
        }

        val ctx = context ?: appContext
        if (ctx != null) {
            unregisterForAllProfilingResults(ctx)
            clearProfilingTriggers(ctx)
        }
        profilingResultsRegistered = false
        listeners.clear()
        synchronized(bufferLock) {
            eventBuffer.clear()
        }
        reportedExitKeys.clear()
        appContext = null
        Log.i(TAG, "shutdown() complete")
    }

    fun capabilitySummary(): String = buildString {
        appendLine("SDK_INT=${Build.VERSION.SDK_INT}")
        appendLine(
            "ApplicationExitInfo (historical ANR/low-memory): " +
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "YES (API 30+)" else "NO",
        )
        appendLine(
            "ProfilingManager listener: " +
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        "YES (API 35+)"
                    } else {
                        "NO"
                    },
        )
        appendLine(
            "TRIGGER_TYPE_ANR: " +
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) "YES (API 36+)" else "NO",
        )
        appendLine(
            "TRIGGER_TYPE_OOM: " +
                    if (Build.VERSION.SDK_INT >= 37) "YES (API 37+)" else "NO",
        )
        appendLine(
            "AnrWarningListener (live): " +
                    if (Build.VERSION.SDK_INT >= 37) "YES (API 37+)" else "NO",
        )
        appendLine("bufferedEvents=${synchronized(bufferLock) { eventBuffer.size }}")
        appendLine("reportedHistoricalExits=${reportedExitKeys.size}")
    }

    /**
     * Subscribe to [ProfilingEvent]s. By default **replays** buffered events so UI that attaches
     * after [init] still receives historical / queued results.
     *
     * Callbacks (including replay) run on the SDK background executor.
     */
    fun addProfilingEventListener(listener: ProfilingEventListener, replayBuffered: Boolean = true) {
        listeners.add(listener)
        if (replayBuffered) {
            val snapshot = synchronized(bufferLock) { eventBuffer.toList() }
            Log.i(TAG, "addProfilingEventListener: replaying ${snapshot.size} buffered event(s) on executor")
            executor.execute {
                for (event in snapshot) {
                    notifyListener(listener, event, duringReplay = true)
                }
            }
        }
    }

    fun removeProfilingEventListener(listener: ProfilingEventListener) {
        listeners.remove(listener)
    }

    fun bufferedEvents(): List<ProfilingEvent> = synchronized(bufferLock) { eventBuffer.toList() }

    /**
     * Scans historical process exit reasons for ANR and low-memory deaths.
     *
     * @param force when false (default), already-dispatched exits are returned but not re-emitted.
     *   When true, matching exits are dispatched again to listeners.
     */
    fun checkLastExitReasons(context: Context, force: Boolean = false): List<ProfilingEvent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "getHistoricalProcessExitReasons not available (requires API 30+)")
            return emptyList()
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exitInfos = activityManager.getHistoricalProcessExitReasons(
            context.packageName, 0, MAX_HISTORICAL_EXITS,
        )

        val events = mutableListOf<ProfilingEvent>()
        for (exitInfo in exitInfos) {
            val reason = exitInfo.reason.toExitReason()
            Log.d(TAG, "ExitReason: $reason pid=${exitInfo.pid} ts=${exitInfo.timestamp} desc=${exitInfo.description}")

            val eventType = when (exitInfo.reason) {
                ApplicationExitInfo.REASON_ANR -> ProfilingEvent.EventType.FATAL_ANR
                ApplicationExitInfo.REASON_LOW_MEMORY -> ProfilingEvent.EventType.LOW_MEMORY_EXIT
                else -> null
            } ?: continue

            val key = exitIdentityKey(exitInfo)
            val event = toProfilingEventFromExitInfo(context, exitInfo, eventType)
            events += event

            val shouldDispatch = force || reportedExitKeys.add(key)
            if (shouldDispatch) {
                if (force) {
                    reportedExitKeys.add(key)
                }
                dispatchEvent(event)
            } else {
                Log.d(TAG, "Skipping already-reported exit: $key")
            }
        }

        if (events.isEmpty()) {
            Log.d(TAG, "No historical ANR/low-memory exit infos found")
        } else {
            Log.i(TAG, "Collected ${events.size} historical exit info(s) (force=$force)")
        }

        return events
    }

    @Deprecated(
        message = "Renamed for clarity",
        replaceWith = ReplaceWith("checkLastExitReasons(context)"),
    )
    fun checkLastExistReason(context: Context): List<ProfilingEvent> = checkLastExitReasons(context)

    fun registerForAllProfilingResults(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Log.w(TAG, "ProfilingManager not available (requires API 35+)")
            return
        }

        if (profilingResultsRegistered) {
            Log.d(TAG, "registerForAllProfilingResults already registered")
            return
        }

        val profilingManager = context.getSystemService(ProfilingManager::class.java)
        if (profilingManager == null) {
            Log.e(TAG, "ProfilingManager system service is null")
            return
        }

        Log.d(TAG, "registerForAllProfilingResults()")
        profilingManager.registerForAllProfilingResults(executor, profilingResultConsumer)
        profilingResultsRegistered = true
    }

    fun unregisterForAllProfilingResults(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        if (!profilingResultsRegistered) return

        val profilingManager = context.getSystemService(ProfilingManager::class.java) ?: return
        profilingManager.unregisterForAllProfilingResults(profilingResultConsumer)
        profilingResultsRegistered = false
        Log.d(TAG, "unregisterForAllProfilingResults()")
    }

    /**
     * Registers system profiling triggers for ANR (API 36+) and OOM (API 37+).
     *
     * @param rateLimitHours hours between trigger firings for this app; 0 disables app rate limiting.
     */
    fun registerProfilingTriggers(context: Context, rateLimitHours: Int = DEFAULT_RATE_LIMIT_HOURS) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            Log.w(TAG, "ProfilingTrigger not available (requires API 36+ / BAKLAVA)")
            return
        }

        val profilingManager = context.getSystemService(ProfilingManager::class.java)
        if (profilingManager == null) {
            Log.e(TAG, "registerProfilingTriggers - ProfilingManager system service is null")
            return
        }

        val triggers = mutableListOf<ProfilingTrigger>()

        val anrTrigger = ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_ANR)
            .setRateLimitingPeriodHours(rateLimitHours)
            .build()
        triggers.add(anrTrigger)

        if (Build.VERSION.SDK_INT >= 37) {
            val oomTrigger = ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_OOM)
                .setRateLimitingPeriodHours(rateLimitHours)
                .build()
            triggers.add(oomTrigger)
        }

        profilingManager.addProfilingTriggers(triggers)
        Log.i(
            TAG,
            "Registered ProfilingTriggers count=${triggers.size} rateLimitHours=$rateLimitHours " +
                    "(ANR=API36+, OOM=API37+)",
        )
    }

    fun clearProfilingTriggers(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        val profilingManager = context.getSystemService(ProfilingManager::class.java) ?: return
        profilingManager.clearProfilingTriggers()
        Log.d(TAG, "clearProfilingTriggers()")
    }

    /** Deletes all app-private copies under [TRACE_DIR]. */
    fun clearCachedTraces(context: Context? = null): Int {
        val ctx = context ?: appContext ?: return 0
        val dir = File(ctx.filesDir, TRACE_DIR)
        if (!dir.isDirectory) return 0
        var removed = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.delete()) removed++
        }
        Log.i(TAG, "clearCachedTraces removed=$removed")
        return removed
    }

    fun requestManualSystemTrace(context: Context, tag: String = "manual-trace") {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Log.w(TAG, "requestManualSystemTrace requires API 35+")
            return
        }

        val profilingManager = context.getSystemService(ProfilingManager::class.java) ?: return
        val cancel = CancellationSignal()

        Log.d(TAG, "requestManualSystemTrace SYSTEM_TRACE tag=$tag")

        profilingManager.requestProfiling(
            ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE, Bundle(), tag, cancel, executor,
        ) { result ->
            Log.d(
                TAG,
                "Manual SYSTEM_TRACE result: error=${result.errorCode.toErrorName()} " +
                        "path=${result.resultFilePath} trigger=${result.triggerTypeOrNone()}",
            )

            val path = result.resultFilePath
            val persistedPath =
                if (result.errorCode == ProfilingResult.ERROR_NONE && !path.isNullOrBlank()) {
                    copyProfilingResultToAppFiles(path, result.tag, "manual")
                } else {
                    null
                }

            dispatchEvent(
                ProfilingEvent(
                    eventType = ProfilingEvent.EventType.MANUAL_TRACE,
                    timestampMs = System.currentTimeMillis(),
                    description = if (result.errorCode == ProfilingResult.ERROR_NONE) {
                        "Manual SYSTEM_TRACE completed"
                    } else {
                        "Manual SYSTEM_TRACE failed"
                    },
                    traceFilePath = persistedPath ?: path,
                    profilingTag = result.tag,
                    errorCode = result.errorCode,
                    errorMessage = result.errorMessage,
                    profilingTriggerType = result.triggerTypeOrNone(),
                ),
            )
        }
    }

    /**
     * Live ANR path (API 37+): fires when the system is about to ANR,
     * **while the process is still alive**. This is the reliable "during hang" signal.
     */
    fun registerAnrWarningListener(context: Context) {
        if (Build.VERSION.SDK_INT < 37) {
            Log.w(TAG, "registerAnrWarningListener requires API 37+ (current=${Build.VERSION.SDK_INT})")
            return
        }

        if (anrWarningRegistered) {
            Log.d(TAG, "registerAnrWarningListener already registered")
            return
        }

        try {
            registerAnrWarningListenerApi37(context)
            anrWarningRegistered = true
            Log.i(TAG, "Registered ActivityManager registerAnrWarningListener (live ANR path)")
        } catch (t: Throwable) {
            // Flag may be disabled on some builds
            Log.e(TAG, "Failed to register registerAnrWarningListener (flag off or unsupported)", t)
        }
    }

    @RequiresApi(37)
    private fun registerAnrWarningListenerApi37(context: Context) {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val consumer = Consumer<android.app.AnrWarningResult> { warning ->
            Log.w(
                TAG,
                "ANR WARNING live: id=${warning.anrId} type=${warning.anrType} " +
                        "consumed=${warning.consumedMillis}ms timeout=${warning.timeoutMillis}ms " +
                        "desc=${warning.description}",
            )

            val anrEvent = ProfilingEvent(
                eventType = ProfilingEvent.EventType.ANR_WARNING,
                timestampMs = System.currentTimeMillis(),
                description = warning.description,
                anrId = warning.anrId,
                anrType = warning.anrType,
                consumedMillis = warning.consumedMillis,
                timeoutMillis = warning.timeoutMillis,
            )

            dispatchEvent(anrEvent)

            // Best-effort: also request a stack sample while we still can.
            requestStackSampleNearAnr(context, tag = "$NEAR_ANR_STACK_TAG_PREFIX${warning.anrId}")
        }

        activityManager.registerAnrWarningListener(executor, consumer)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun requestStackSampleNearAnr(context: Context, tag: String) {
        val profilingManager = context.getSystemService(ProfilingManager::class.java) ?: return

        try {
            profilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING, Bundle(), tag, null, executor,
            ) { result ->
                Log.i(
                    TAG,
                    "Near-ANR stack sample: error=${result.errorCode.toErrorName()} path=${result.resultFilePath}",
                )
                val path = result.resultFilePath
                val persistedPath =
                    if (result.errorCode == ProfilingResult.ERROR_NONE && !path.isNullOrBlank()) {
                        copyProfilingResultToAppFiles(path, result.tag, "stack")
                    } else {
                        null
                    }
                val stackFromTrace = extractStackTraceFromPerfetto(path ?: persistedPath)
                dispatchEvent(
                    ProfilingEvent(
                        eventType = ProfilingEvent.EventType.STACK_SAMPLE,
                        timestampMs = System.currentTimeMillis(),
                        description = "Near-ANR stack sample (app-requested)",
                        stackTrace = stackFromTrace,
                        traceFilePath = persistedPath ?: path,
                        profilingTag = result.tag,
                        errorCode = result.errorCode,
                        errorMessage = result.errorMessage,
                        profilingTriggerType = result.triggerTypeOrNone(),
                    ),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "requestStackSampleNearAnr failed", t)
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun onProfilingResultReceived(result: ProfilingResult) {
        val trigger = result.triggerTypeOrNone()
        val path = result.resultFilePath
        val triggerName = trigger.toTriggerName()
        val tag = result.tag

        Log.i(
            TAG,
            "ProfilingResult: trigger=$triggerName($trigger) error=${result.errorCode.toErrorName()} " +
                    "tag=$tag path=$path msg=${result.errorMessage}",
        )

        // App-requested stack samples are handled in their own requestProfiling callback.
        // Ignore them in the global consumer to avoid misclassification via tags.
        if (tag?.startsWith(NEAR_ANR_STACK_TAG_PREFIX) == true) {
            Log.d(TAG, "Ignoring near-ANR stack sample in global consumer (handled in request callback)")
            return
        }

        val eventType = resolveAnrOomEventType(trigger, path, tag)
        if (eventType == null) {
            Log.d(TAG, "Ignoring non-ANR/OOM profiling result (trigger=$triggerName tag=$tag)")
            return
        }

        val persistedPath =
            if (result.errorCode == ProfilingResult.ERROR_NONE && !path.isNullOrBlank()) {
                copyProfilingResultToAppFiles(path, tag, triggerName.lowercase())
            } else {
                null
            }

        // Prefer system path for parse (original), fall back to app-private copy.
        val stackFromTrace = extractStackTraceFromPerfetto(path ?: persistedPath)

        val event = ProfilingEvent(
            eventType = eventType,
            timestampMs = System.currentTimeMillis(),
            description = "ProfilingTrigger $triggerName ($eventType)",
            stackTrace = stackFromTrace,
            traceFilePath = persistedPath ?: path,
            profilingTag = tag,
            errorCode = result.errorCode,
            errorMessage = result.errorMessage,
            profilingTriggerType = trigger,
        )

        dispatchEvent(event)
    }

    /**
     * Reads a ProfilingManager result file (typically `*.perfetto-trace`) and extracts the
     * latest main-thread call stack when the profile includes stack-sampling data.
     *
     * System-triggered ANR traces often have no call stacks (ftrace-only); returns null then.
     */
    private fun extractStackTraceFromPerfetto(filePath: String?): String? {
        if (filePath.isNullOrBlank()) return null
        val file = File(filePath)
        if (!file.isFile) {
            Log.w(TAG, "Perfetto extract: file missing $filePath")
            return null
        }
        // Accept .perfetto-trace and any binary result the parser can walk as Trace packets.
        return try {
            val parser = PerfettoTraceParser()
            val all = parser.parse(file)
            val mainStacks = parser.extractMainThreadStacks(all)
            val last = mainStacks.maxByOrNull { it.timestampNs }
            if (last == null) {
                Log.i(
                    TAG,
                    "Perfetto extract: no main-thread stack samples in $filePath " +
                            "(totalSamples=${all.size}; system ANR traces often omit stacks)",
                )
                null
            } else {
                val text = last.format()
                Log.i(
                    TAG,
                    "Perfetto extract: main-thread stack frames=${last.frames.size} " +
                            "samples=${mainStacks.size}/${all.size} path=$filePath\n$text",
                )
                if (text.length <= MAX_STACK_PREVIEW_CHARS) text
                else text.take(MAX_STACK_PREVIEW_CHARS) + "\n…(truncated)"
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Perfetto extract failed for $filePath", t)
            null
        }
    }

    /**
     * Resolves ANR vs OOM from trigger first, then path markers.
     * Does **not** use loose tag substring matching (avoids false positives from app tags).
     * App-requested / [ProfilingTrigger.TRIGGER_TYPE_NONE] results are ignored unless
     * the path contains a system trigger marker.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun resolveAnrOomEventType(trigger: Int, path: String?, @Suppress("UNUSED_PARAMETER") tag: String?): ProfilingEvent.EventType? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            when (trigger) {
                ProfilingTrigger.TRIGGER_TYPE_ANR -> return ProfilingEvent.EventType.ANR_WARNING
                ProfilingTrigger.TRIGGER_TYPE_OOM -> return ProfilingEvent.EventType.OOM_WARNING
                ProfilingTrigger.TRIGGER_TYPE_NONE -> {
                    // App-requested profiling: only accept if path still has a system marker.
                }
                else -> {
                    // Other system triggers (cold start, kill, etc.) — not ANR/OOM.
                    return null
                }
            }
        }

        if (path?.contains(ANR_TRIGGER_FILE_MARKER) == true) {
            return ProfilingEvent.EventType.ANR_WARNING
        }
        if (path?.contains(OOM_TRIGGER_FILE_MARKER) == true) {
            return ProfilingEvent.EventType.OOM_WARNING
        }

        // Tag is intentionally not used for classification (see NEAR_ANR_STACK_TAG_PREFIX collision).
        return null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun toProfilingEventFromExitInfo(
        context: Context,
        exitInfo: ApplicationExitInfo,
        eventType: ProfilingEvent.EventType,
    ): ProfilingEvent {
        val reason = exitInfo.reason.toExitReason()
        Log.w(TAG, "APP PREVIOUSLY DIED DUE TO $reason")
        Log.w(TAG, "  pid=${exitInfo.pid} process=${exitInfo.processName}")
        Log.w(TAG, "  ts=${exitInfo.timestamp} desc=${exitInfo.description}")
        Log.w(TAG, "  importance=${exitInfo.importance} pss=${exitInfo.pss}KB rss=${exitInfo.rss}KB")

        val fullTrace = readLastExitTraceFull(exitInfo)
        val preview = fullTrace?.let { stackPreview(it) }
        val savedPath = fullTrace?.let {
            persistLastExitTraceText(context, exitInfo.timestamp, reason, it)
        }

        return ProfilingEvent(
            eventType = eventType,
            timestampMs = exitInfo.timestamp,
            processName = exitInfo.processName,
            pid = exitInfo.pid,
            description = exitInfo.description,
            importance = exitInfo.importance,
            pssKb = exitInfo.pss,
            rssKb = exitInfo.rss,
            stackTrace = preview,
            traceFilePath = savedPath,
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readLastExitTraceFull(info: ApplicationExitInfo): String? {
        return try {
            info.traceInputStream?.use { stream ->
                stream.bufferedReader().readText()
            } ?: run {
                Log.w(TAG, "  No traceInputStream for this exit info")
                null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "  Failed to read exit trace", t)
            null
        }
    }

    private fun stackPreview(fullText: String): String {
        if (fullText.length <= MAX_STACK_PREVIEW_CHARS) return fullText
        return fullText.take(MAX_STACK_PREVIEW_CHARS) + "\n…(truncated; full trace on disk)"
    }

    private fun persistLastExitTraceText(
        context: Context,
        timestampMs: Long,
        reason: String,
        text: String,
    ): String? {
        return try {
            val dir = File(context.filesDir, TRACE_DIR).apply { mkdirs() }
            val out = File(dir, "${reason}_exit_$timestampMs.txt")
            out.writeText(text)
            pruneTraceDir(dir)
            Log.i(TAG, "Saved Exit Trace at → ${out.absolutePath}")
            out.absolutePath
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to persist exit trace", t)
            null
        }
    }

    private fun copyProfilingResultToAppFiles(srcPath: String, tag: String?, triggerName: String): String? {
        val ctx = appContext ?: return srcPath
        return try {
            val src = File(srcPath)
            if (!src.exists()) {
                Log.w(TAG, "Profiling result file missing: $srcPath")
                return srcPath
            }
            val dir = File(ctx.filesDir, TRACE_DIR).apply { mkdirs() }
            val safeTag = (tag ?: triggerName).replace(Regex("[^A-Za-z0-9_-]"), "_").take(20)
            val out = File(dir, "profiling_${safeTag}_${System.currentTimeMillis()}_${src.name}")
            src.copyTo(out, overwrite = true)
            // Do not delete system-provided path; ownership/lifecycle is platform-managed.
            pruneTraceDir(dir)
            Log.i(TAG, "Copied profiling result at → ${out.absolutePath}")
            out.absolutePath
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to copy profiling result", t)
            srcPath
        }
    }

    /** Keeps at most [MAX_CACHED_TRACE_FILES] files, deleting oldest by lastModified. */
    private fun pruneTraceDir(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_CACHED_TRACE_FILES) return
        val ordered = files.sortedBy { it.lastModified() }
        val toRemove = ordered.size - MAX_CACHED_TRACE_FILES
        ordered.take(toRemove).forEach { file ->
            if (file.delete()) {
                Log.d(TAG, "Pruned old trace ${file.name}")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitIdentityKey(exitInfo: ApplicationExitInfo): String =
        "${exitInfo.pid}:${exitInfo.timestamp}:${exitInfo.reason}"

    /**
     * Buffers the event and delivers to all listeners on [executor]
     * (never on the calling thread).
     */
    private fun dispatchEvent(event: ProfilingEvent) {
        synchronized(bufferLock) {
            if (eventBuffer.size >= MAX_BUFFERED_EVENTS) {
                eventBuffer.removeFirst()
            }
            eventBuffer.addLast(event)
        }

        Log.i(
            TAG,
            "Dispatch ProfilingEvent source=${event.eventType} ts=${event.timestampMs} " +
                    "desc=${event.description} path=${event.traceFilePath} listeners=${listeners.size}",
        )

        val snapshot = listeners.toList()
        executor.execute {
            for (listener in snapshot) {
                notifyListener(listener, event, duringReplay = false)
            }
        }
    }

    private fun notifyListener(
        listener: ProfilingEventListener,
        event: ProfilingEvent,
        duringReplay: Boolean,
    ) {
        try {
            listener.onProfilingEvent(event)
        } catch (t: Throwable) {
            val phase = if (duringReplay) "during replay" else ""
            Log.e(TAG, "ProfilingEventListener threw $phase", t)
        }
    }
}

private fun Int.toTriggerName(): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return "N/A"

    return when (this) {
        ProfilingTrigger.TRIGGER_TYPE_NONE -> "NONE"
        ProfilingTrigger.TRIGGER_TYPE_APP_FULLY_DRAWN -> "APP_FULLY_DRAWN"
        ProfilingTrigger.TRIGGER_TYPE_ANR -> "ANR"
        ProfilingTrigger.TRIGGER_TYPE_APP_REQUEST_RUNNING_TRACE -> "APP_REQUEST_RUNNING_TRACE"
        ProfilingTrigger.TRIGGER_TYPE_KILL_FORCE_STOP -> "KILL_FORCE_STOP"
        ProfilingTrigger.TRIGGER_TYPE_KILL_RECENTS -> "KILL_RECENTS"
        ProfilingTrigger.TRIGGER_TYPE_KILL_TASK_MANAGER -> "KILL_TASK_MANAGER"
        ProfilingTrigger.TRIGGER_TYPE_OOM -> "OOM"
        ProfilingTrigger.TRIGGER_TYPE_ANOMALY -> "ANOMALY"
        ProfilingTrigger.TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE -> "KILL_EXCESSIVE_CPU"
        ProfilingTrigger.TRIGGER_TYPE_COLD_START -> "COLD_START"
        ProfilingTrigger.TRIGGER_TYPE_APP_COMPAT -> "APP_COMPAT"
        else -> "UNKNOWN"
    }
}

private fun Int.toErrorName(): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return "UNKNOWN($this)"

    return when (this) {
        ProfilingResult.ERROR_NONE -> "ERROR_NONE"
        ProfilingResult.ERROR_FAILED_RATE_LIMIT_SYSTEM -> "RATE_LIMIT_SYSTEM"
        ProfilingResult.ERROR_FAILED_RATE_LIMIT_PROCESS -> "RATE_LIMIT_PROCESS"
        ProfilingResult.ERROR_FAILED_PROFILING_IN_PROGRESS -> "PROFILING_IN_PROGRESS"
        ProfilingResult.ERROR_FAILED_EXECUTING -> "FAILED_EXECUTING"
        ProfilingResult.ERROR_FAILED_POST_PROCESSING -> "FAILED_POST_PROCESSING"
        ProfilingResult.ERROR_FAILED_NO_DISK_SPACE -> "NO_DISK_SPACE"
        ProfilingResult.ERROR_FAILED_INVALID_REQUEST -> "INVALID_REQUEST"
        ProfilingResult.ERROR_UNKNOWN -> "ERROR_UNKNOWN"
        else -> "UNKNOWN($this)"
    }
}

private fun Int.toExitReason(): String {
    return when (this) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        else -> "UNKNOWN($this)"
    }
}

private fun ProfilingResult.triggerTypeOrNone(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        triggerType
    } else {
        0
    }
}
