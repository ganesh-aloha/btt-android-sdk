package com.bluetriangle.analytics.utility

import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import com.bluetriangle.analytics.Tracker
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections
import java.util.IdentityHashMap

object CrashTraceParser {
    // e.g.  "main" prio=5 tid=1 Sleeping
    // e.g.  "ReferenceQueueDaemon" daemon prio=5 tid=7 Waiting
    private val threadHeaderRegex =
        Regex("""^"([^"]+)"\s+(?:daemon\s+)?prio=\d+\s+tid=(\d+)\s+(\S+)""")

    fun exceptionToJson(throwable: Throwable, thread: Thread? = null): JSONObject {
        val threads = JSONArray()

        val appId = Tracker.instance?.appPackageName ?: ""
        val versionName = Tracker.instance?.appVersion ?: ""

        val threadId = thread?.id ?: Looper.getMainLooper().thread.id
        val threadName = thread?.name ?: Looper.getMainLooper().thread.name
        var current: Throwable? = throwable
        var index = 0
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())

        while (current != null && seen.add(current) && index < 20) {
            val stack = JSONArray()
            current.stackTrace.forEachIndexed { i, element ->
                // StackTraceElement.toString() already renders as
                // "com.pkg.Class.method(File.kt:line)" — just prefix "at " to
                // match the fLine convention used elsewhere.
                stack.put(JSONObject().put("i", i).put("fLine", "at $element"))
            }

            val reason = buildString {
                if (index > 0) append("Caused by: ")
                append(current!!.javaClass.name)
                current!!.message?.let { append(": ").append(it) }
            }

            threads.put(
                JSONObject().apply {
                    put("id", if (index == 0) threadId.toString() else "caused-$index")
                    put("name", if (index == 0) threadName else "Caused by")
                    put("crashed", index == 0)
                    put("caused", reason)
                    put("stack", stack)
                }
            )

            current = current.cause
            index++
        }

        val meta = JSONObject().apply {
            put("fVersion", TRACE_FORMAT_VERSION)
//            put("appId", appId)
//            put("version", versionName)
//            put("platform", "Android")
//            put("date", System.currentTimeMillis().toString())
        }

        return JSONObject().apply {
            put("meta", meta)
            put("threads", threads)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun parseApplicationExitInfo(exitInfo: ApplicationExitInfo, errorType: String): AppExitError {
        val rawText = exitInfo.traceInputStream?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        }.orEmpty()

        return parseApplicationExitInfo(errorType, rawText)
    }

    private fun parseApplicationExitInfo(errorType: String, rawTraceText: String): AppExitError {
        val appId = Tracker.instance?.appPackageName ?: ""
        var exitReason = ""

        val lines = normalize(rawTraceText).split("\n").toMutableList()
        if (lines[0].startsWith(TRACE_SUBJECT_PREFIX)) {
            lines[0] = lines[0].removePrefix(TRACE_SUBJECT_PREFIX)
            exitReason = lines[0]
        }

        // Generate Title
        val subLines = arrayListOf(extractAnrLocation(exitReason))
        val appFrame = appFrameOf(lines, appId)
        appFrame?.let {
            subLines.add(it)
        }
        val title = subLines.joinToString("~~")

        var culpritLine: String? = null
        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (exitReason.isNotEmpty() && trimmed.startsWith("at ") && i > 0) {
                culpritLine = trimmed
                break
            }
        }

        // Package name, from "Cmd line: <package>" — falls back to the caller-supplied appId
        // if the trace text doesn't carry it for some reason.
        val resolvedAppId = lines
            .firstOrNull { it.trim().startsWith("Cmd line:") }
            ?.substringAfter("Cmd line:")
            ?.trim()
            ?: appId

        // Stack frames only start appearing after the "DALVIK THREADS (N):" marker.
        var idx = lines.indexOfFirst { it.trim().startsWith("DALVIK THREADS") }
        if (idx == -1) idx = 0 else idx += 1

        val threads = JSONArray()

        while (idx < lines.size) {
            val headerMatch = threadHeaderRegex.find(lines[idx].trim())
            if (headerMatch == null) {
                idx++
                continue
            }

            val threadName = headerMatch.groupValues[1]
            val tid = headerMatch.groupValues[2]
            idx++ // consume header line

            val stack = JSONArray()
            var crashed = false

            var stackIndex = 0
            while (idx < lines.size) {
                val trimmed = lines[idx].trim()

                // End of this thread's block.
                if (trimmed.isEmpty() ||
                    trimmed.startsWith("DumpLatencyMs:") ||
                    trimmed.startsWith("----- end")
                ) {
                    idx++
                    break
                }

                // Next thread starts — stop, let the outer loop re-detect it (don't consume).
                if (threadHeaderRegex.containsMatchIn(trimmed)) {
                    break
                }

                // Skip metadata lines: "| group=...", "| sysTid=...", "| state=...", etc.
                if (trimmed.startsWith("|")) {
                    idx++
                    continue
                }

                // Frame / lock-info lines are what we want to keep, verbatim.
                if (trimmed.startsWith("at ") || trimmed.startsWith("- ")) {
                    stack.put(JSONObject().put("i", stackIndex).put("fLine", trimmed))
                    stackIndex++
                    if (trimmed == culpritLine) crashed = true
                }

                idx++
            }

            if (stack.length() > 0) {
                threads.put(
                    JSONObject().apply {
                        put("id", tid)
                        put("name", threadName)
                        put("crashed", crashed)
                        put("caused", if (crashed) exitReason else "")
                        put("stack", stack)
                    }
                )
            }
        }

        val meta = JSONObject().apply {
            put("fVersion", TRACE_FORMAT_VERSION)
//            put("appId", resolvedAppId)
//            put("version", versionName)
//            put("platform", "Android")
//            put("date", timestampMs.toString())
        }

        val stackData = JSONObject().apply {
            put("meta", meta)
            put("threads", threads)
        }

        return AppExitError("$errorType $title", stackData)
    }

    private fun appFrameOf(traceLines: List<String>, packageName: String): String? {
        val threadsIndex = traceLines.indexOfFirst { it.contains(DALVIK_THREADS_MARKER) }
        if (threadsIndex < 0) return null

        return traceLines.asSequence()
            .drop(threadsIndex + 1)
            .map(String::trim)
            .firstOrNull { it.startsWith(STACK_FRAME_PREFIX) && it.contains(packageName) }
    }

    fun extractAnrLocation(subject: String): String {
        return subject.substringAfter("(")
            .substringBefore(" is not responding")
            .trim()
            .substringAfterLast(" ")
    }
    /**
     * Defensive only: a genuine AppExitInfo.traceInputStream() dump is plain text with
     * real quotes and slashes. Some export/logging pipelines JSON-escape the text before
     * saving it to disk (\" for ", \/ for /) — if that happened upstream, undo it here
     * so the regexes above still match.
     */
    private fun normalize(text: String): String =
        text.replace("\\\"", "\"").replace("\\/", "/")

    private const val TRACE_SUBJECT_PREFIX = "Subject: "

    /**
     * Start of the thread dump section of an ANR trace
     */
    private const val DALVIK_THREADS_MARKER = "DALVIK THREADS"
    private const val STACK_FRAME_PREFIX = "at "

    private const val TRACE_FORMAT_VERSION: String = "1.0.0"
}

data class AppExitError(val title: String?, val stackTrace: JSONObject?)