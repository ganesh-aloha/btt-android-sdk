package com.bluetriangle.analytics.hybrid

import android.os.Looper
import android.webkit.WebView
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.Utils
import com.bluetriangle.analytics.utility.WebViewHelper
import org.json.JSONObject
import java.lang.ref.WeakReference

object BTTWebViewTracker {

    private var webViews = arrayListOf<WeakReference<WebView>>()

    @JvmStatic
    public fun onLoadResource(view: WebView?, url: String?) {
        try {
            if (view == null || url == null) return
            val fileName = url.split("/").lastOrNull { segment -> segment.isNotEmpty() }
            if (fileName != "btt.js") return

            addWebView(view)

            view.stitchWebViewSession()
        } catch (e: Exception) {
            Tracker.instance?.configuration?.logger?.error("Error while stitching WebView session: ${e.message}")
        }
    }

    private fun WebView.stitchWebViewSession() {
        val values = sessionStorageValues() ?: return
        values.forEach { (key, value) -> setLocalStorage(key, value) }
        Tracker.instance?.configuration?.logger?.info("Injected session information in WebView localStorage: $values")
    }

    @JvmStatic
    private fun WebView.hasBTTJs(task: () -> Unit) = WebViewHelper(this).let {
        it.evaluateJavascript("_bttTagInit") { hasBttTag, error ->
            if(error == null && hasBttTag == "true") {
                it.evaluateJavascript("_bttUtil.prefix") { siteId, err ->
                    if(err == null && siteId == "\"${Tracker.instance?.configuration?.siteId}\"") {
                        task()
                    }
                }
            }
        }
    }

    @JvmStatic
    internal fun updateSession() {
        try {
            Looper.getMainLooper().runCatching {
                webViews.toList().forEach { webView ->

                    webView.get()?.let {

                        it.hasBTTJs {
                            val siteId = Tracker.instance?.configuration?.siteId
                            onLoadResource(it, "https://${siteId}.btttag.com/btt.js")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Tracker.instance?.configuration?.logger?.error("Error while updating WebView session: ${e.message}")
        }
    }

    private fun addWebView(view: WebView) {
        webViews = ArrayList(webViews.filter { webView -> webView.get() != null && webView.get() != view })
        webViews.add(WeakReference(view))
    }

    private fun WebView.setLocalStorage(key:String, value:String) {
        runJS("window.localStorage.setItem('$key', '$value');")
    }

    private fun WebView.runJS(js: String) {
        evaluateJavascript(js, null)
    }

    /**
     * The session values shared with btt.js, keyed by their localStorage key. Null when stitching is disabled.
     */
    internal fun sessionStorageValues(): Map<String, String>? {
        val config = Tracker.instance?.configuration ?: return null
        if (!config.isWebViewStitchingEnabled) return null
        val expiration = (System.currentTimeMillis() + (config.sessionExpiryDuration)).toString()
        val wcdOn = if(config.shouldSampleNetwork) "on" else "off"
        val values = linkedMapOf(
            "BTT_X0siD" to valueJson(config.sessionId.orEmpty(), expiration),
            "BTT_SDK_VER" to "Android-${Tracker.sdkVersion}",
            "BTT_WCD_Collect" to valueJson(wcdOn, expiration),
            "BTT_APP_VER" to valueJson(Tracker.instance?.appVersion.orEmpty(), expiration),
            "BTT_OS_VER" to valueJson(Utils.os, expiration),
        )
        Tracker.instance?.deviceInfoProvider?.getDeviceInfo()?.let { deviceInfo ->
            values["BTT_DEVICE_MODEL"] = valueJson(deviceInfo.deviceModel, expiration)
        }

        return values
    }

    private fun valueJson(value: String, expiration: String): String =
        JSONObject().put("value", value).put("expires", expiration).toString()
}
