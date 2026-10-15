package com.bluetriangle.analytics

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only trace logger for the SDK's beacon requests (analytics.rcv, err.rcv, wcdv02.rcv).
 *
 * When [BlueTriangleConfiguration.isDebug] is enabled, every request/response pair is appended to
 * `<app cache dir>/bta_logs/btt_beacons.log` and also written to logcat. The log lives outside the
 * payload cache directory so it is never picked up by [PayloadCache] as a cached payload.
 */
internal object BttBeaconLogger {

    private const val LOG_DIRECTORY = "btt_logs"
    private const val LOG_FILE = "btt_beacons.log"
    private const val MAX_LOG_FILE_SIZE = 5L * 1024 * 1024

    @Volatile
    private var configuration: BlueTriangleConfiguration? = null

    @Volatile
    private var logFile: File? = null

    // SimpleDateFormat is not thread-safe, so a new instance is created per entry
    private fun formatTime(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(time))

    fun init(context: Context, configuration: BlueTriangleConfiguration) {
        this.configuration = configuration
        if (!configuration.isDebug) {
            logFile = null
            return
        }
        try {
            val directory = File(context.cacheDir, LOG_DIRECTORY)
            if (!directory.exists() && !directory.mkdirs()) {
                configuration.logger?.error("Error creating network trace log directory: ${directory.absolutePath}")
                return
            }
            logFile = File(directory, LOG_FILE)
            configuration.logger?.debug("Network trace log file: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            configuration.logger?.error("Error initializing network trace log: ${e.message}")
        }
    }

    val isEnabled: Boolean
        get() = configuration?.isDebug == true

    /**
     * Reads the response body. Error responses (status >= 300) are always read from the error stream,
     * since callers log them; success bodies are only read when tracing is enabled.
     */
    fun readResponseBody(connection: HttpURLConnection, statusCode: Int): String? {
        if (statusCode >= 300) {
            return connection.errorStream.bufferedReader().use { it.readText() }
        }
        if (!isEnabled) return null
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    fun trace(
        url: String,
        requestBody: String,
        startTime: Long,
        responseBody: String? = null,
        error: Throwable? = null
    ) {
        if (!isEnabled) return
        try {
            val response = when {
                error != null -> "FAILED: ${error.javaClass.name}: ${error.message}"
                responseBody.isNullOrEmpty() -> "<empty>"
                else -> responseBody
            }
            val entry = buildString {
                val pageName = pageName(url, requestBody)?.let { " - $it" } ?: ""
                appendLine("==================== ${beaconName(url)}$pageName  ${formatTime(startTime)}  ====================")
                appendLine()
                appendLine("${Constants.METHOD_POST} : $url")
                appendLine("Request Body:")
                appendLine(requestBody)
                appendLine()
                appendLine("Response Body: $response")
                appendLine()
            }

            configuration?.logger?.debug("Network Trace\n$entry")
            writeToFile(entry)
        } catch (e: Exception) {
            configuration?.logger?.error("Error writing network trace: ${e.message}")
        }
    }

    private fun beaconName(url: String): String {
        val config = configuration
        return when {
            config != null && url.startsWith(config.trackerUrl) -> "Analytics Beacon"
            config != null && url.startsWith(config.errorReportingUrl) -> "Error Beacon"
            config != null && url.startsWith(config.networkCaptureUrl) -> "Wcd Beacon"
            else -> "Beacon"
        }
    }

    /**
     * err.rcv and wcdv02.rcv carry the page name as a URL query parameter, analytics.rcv carries it in the JSON body.
     */
    private fun pageName(url: String, requestBody: String): String? {
        val fromUrl = try {
            Uri.parse(url).getQueryParameter(Timer.FIELD_PAGE_NAME)
        } catch (e: Exception) {
            null
        }
        if (!fromUrl.isNullOrBlank()) return fromUrl
        return try {
            JSONObject(requestBody).optString(Timer.FIELD_PAGE_NAME).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    private fun writeToFile(entry: String) {
        val file = logFile ?: return
        if (file.exists() && file.length() > MAX_LOG_FILE_SIZE) {
            val rotated = File(file.parentFile, "$LOG_FILE.1")
            rotated.delete()
            file.renameTo(rotated)
        }
        file.appendText(entry)
    }
}
