package com.bluetriangle.analytics.hybrid

import android.os.Looper
import android.webkit.WebView
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.utility.WebViewHelper
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
        Tracker.instance?.configuration?.let {
            if(!it.isWebViewStitchingEnabled) return@let
            val expiration = (System.currentTimeMillis() + (it.sessionExpiryDuration)).toString()
            val sessionID = "{\"value\":\"${it.sessionId}\",\"expires\":\"$expiration\"}"

            val wcdOn = if(it.shouldSampleNetwork) "on" else "off"
            val wcdCollect = "{\"value\":\"$wcdOn\",\"expires\":\"$expiration\"}"
            val sdkVersion = "Android-${Tracker.sdkVersion}"

            setLocalStorage("BTT_X0siD", sessionID)
            setLocalStorage("BTT_SDK_VER", sdkVersion)
            setLocalStorage("BTT_WCD_Collect", wcdCollect)
            Tracker.instance?.configuration?.logger?.info("Injected session ID and SDK version in WebView: BTT_X0siD: $sessionID, BTT_SDK_VER: $sdkVersion with expiration $expiration")
        }
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

}
