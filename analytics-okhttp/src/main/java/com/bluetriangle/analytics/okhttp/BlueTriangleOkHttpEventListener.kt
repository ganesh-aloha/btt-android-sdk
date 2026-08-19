package com.bluetriangle.analytics.okhttp

import android.util.Log
import com.bluetriangle.analytics.BlueTriangleConfiguration
import com.bluetriangle.analytics.networkcapture.CapturedRequest
import com.bluetriangle.analytics.networkcapture.NetworkNativeAppProperties
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale

internal class BlueTriangleOkHttpEventListener(
    private val configuration: BlueTriangleConfiguration,
    private val eventListener: EventListener? = null
) : EventListener() {

    private var capturedRequest: CapturedRequest? = null
    private val logger = configuration.logger

    companion object {
        private const val TAG = "OkHttpEventListener"
    }

    override fun cacheConditionalHit(call: Call, cachedResponse: Response) {
        super.cacheConditionalHit(call, cachedResponse)
        guarded {
            eventListener?.cacheConditionalHit(call, cachedResponse)
        }
    }

    private var callStartNanos: Long = 0

    private fun printEvent(name: String) {
        val nowNanos = System.nanoTime()
        if (name.contains("callStart")) {
            callStartNanos = nowNanos
        }
        val elapsedNanos = nowNanos - callStartNanos
        logger?.log(Log.VERBOSE, String.format(Locale.ENGLISH, "%s : %.3f : %s%n", TAG, elapsedNanos / 1000000000.0, name))
    }

    override fun cacheHit(call: Call, response: Response) {
        super.cacheHit(call, response)
        guarded {
            eventListener?.cacheHit(call, response)
        }
    }

    override fun cacheMiss(call: Call) {
        super.cacheMiss(call)
        eventListener?.cacheMiss(call)
    }

    override fun canceled(call: Call) {
        super.canceled(call)
        eventListener?.canceled(call)
    }

    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
        super.proxySelectEnd(call, url, proxies)
        eventListener?.proxySelectEnd(call, url, proxies)
    }

    override fun proxySelectStart(call: Call, url: HttpUrl) {
        super.proxySelectStart(call, url)
        eventListener?.proxySelectStart(call, url)
    }

    override fun satisfactionFailure(call: Call, response: Response) {
        super.satisfactionFailure(call, response)
        eventListener?.satisfactionFailure(call, response)
    }

    override fun callStart(call: Call) {
        super.callStart(call)
        guarded {
            eventListener?.callStart(call)
            printEvent("1.1. callStart(${call.request().url})")
            if (!configuration.shouldSampleNetwork) {
                configuration.logger?.error("Not sampling network")
                return@guarded
            }
            capturedRequest = CapturedRequest().apply {
                start()
                url = call.request().url.toString()
                method = call.request().method
                val mediaType = call.request().headers["Content-Type"]?.toMediaType()
                requestType = requestTypeFromMediaType(file, mediaType)
            }
        }
    }

    override fun callFailed(call: Call, ioe: IOException) {
        super.callFailed(call, ioe)
        guarded {
            eventListener?.callFailed(call, ioe)
            printEvent("1.2. callFailed(${call.request().url}, ${ioe::class.java.simpleName}(\"${ioe.message}\"))")
            if (!configuration.shouldSampleNetwork) {
                configuration.logger?.error("Not sampling network")
                return@guarded
            }

            capturedRequest?.apply {
                encodedBodySize = call.request().body?.contentLength() ?: 0
                responseStatusCode = 600
                nativeAppProperties?.err = "${ioe::class.java.simpleName} : ${ioe.message}"
                stop()
                submit()
            }

            configuration.logger?.debug("Submitted request: ${capturedRequest?.url}, ${capturedRequest?.duration}")
        }
    }

    override fun callEnd(call: Call) {
        super.callEnd(call)
        guarded {
            eventListener?.callEnd(call)
            printEvent("1.3. callEnd(${call.request().url})")
        }
    }

    override fun dnsStart(call: Call, domainName: String) {
        super.dnsStart(call, domainName)
        guarded {
            eventListener?.dnsStart(call, domainName)
            printEvent("2.1. dnsStart(${call.request().url}, $domainName)")
        }
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        super.dnsEnd(call, domainName, inetAddressList)
        guarded {
            eventListener?.dnsEnd(call, domainName, inetAddressList)
            printEvent("2.2. dnsEnd(${call.request().url}, $domainName, $inetAddressList)")
        }
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        super.connectStart(call, inetSocketAddress, proxy)
        guarded {
            eventListener?.connectStart(call, inetSocketAddress, proxy)
            printEvent("3.1. connectStart(${call.request().url}, $inetSocketAddress, $proxy)")
        }
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        super.connectionAcquired(call, connection)
        guarded {
            eventListener?.connectionAcquired(call, connection)
            printEvent("3.2. connectionAcquired(${call.request().url}, $connection)")
        }
    }

    override fun connectionReleased(call: Call, connection: Connection) {
        super.connectionReleased(call, connection)
        guarded {
            eventListener?.connectionReleased(call, connection)
            printEvent("3.3. connectionReleased(${call.request().url}, $connection)")
        }
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) {
        super.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
        guarded {
            eventListener?.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
            printEvent("3.4. connectFailed($${call.request().url}, $inetSocketAddress, $proxy, $protocol, $${ioe::class.java.simpleName}(\"${ioe.message}\"))")
        }
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) {
        super.connectEnd(call, inetSocketAddress, proxy, protocol)
        guarded {
            eventListener?.connectEnd(call, inetSocketAddress, proxy, protocol)
            printEvent("3.5. connectEnd(${call.request().url}, $inetSocketAddress, $proxy, $protocol)")
        }
    }

    override fun requestHeadersStart(call: Call) {
        super.requestHeadersStart(call)
        guarded {
            eventListener?.requestHeadersStart(call)
            printEvent("4.1. requestHeadersStart(${call.request().url})")
        }
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        super.requestHeadersEnd(call, request)
        guarded {
            eventListener?.requestHeadersEnd(call, request)
            printEvent("4.2. requestHeadersEnd(${call.request().url}, ${request.url}, ${request.body?.contentLength()})")
        }
    }

    override fun requestBodyStart(call: Call) {
        super.requestBodyStart(call)
        guarded {
            eventListener?.requestBodyStart(call)
            printEvent("4.3. requestBodyStart(${call.request().url})")
        }
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        super.requestBodyEnd(call, byteCount)
        guarded {
            eventListener?.requestBodyEnd(call, byteCount)
            printEvent("4.4. requestBodyEnd(${call.request().url}, $byteCount)")
        }
    }

    override fun requestFailed(call: Call, ioe: IOException) {
        super.requestFailed(call, ioe)
        guarded {
            eventListener?.requestFailed(call, ioe)
            printEvent("4.5. requestFailed(${call.request().url}, ${ioe::class.java.simpleName}(\"${ioe.message}\"))")
        }
    }

    override fun responseHeadersStart(call: Call) {
        super.responseHeadersStart(call)
        guarded {
            eventListener?.responseHeadersStart(call)
            printEvent("5.1. responseHeadersStart(${call.request().url})")
        }
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        super.responseHeadersEnd(call, response)
        guarded {
            eventListener?.responseHeadersEnd(call, response)
            printEvent("5.2. responseHeadersEnd(${call.request().url}, ${response.code})")
        }
    }

    override fun responseBodyStart(call: Call) {
        super.responseBodyStart(call)
        guarded {
            eventListener?.responseBodyStart(call)
            printEvent("5.3. responseBodyStart(${call.request().url})")
        }
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        super.responseBodyEnd(call, byteCount)
        guarded {
            eventListener?.responseBodyEnd(call, byteCount)
            printEvent("5.4. responseBodyEnd(${call.request().url}, $byteCount)")
        }
    }

    override fun responseFailed(call: Call, ioe: IOException) {
        super.responseFailed(call, ioe)
        guarded {
            eventListener?.responseFailed(call, ioe)
            printEvent("5.5. responseFailed(${call.request().url}, ${ioe::class.java.simpleName}(\"${ioe.message}\"))")
        }
    }

    override fun secureConnectStart(call: Call) {
        super.secureConnectStart(call)
        guarded {
            eventListener?.secureConnectStart(call)
            printEvent("6.1. secureConnectStart(${call.request().url})")
        }
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        super.secureConnectEnd(call, handshake)
        guarded {
            eventListener?.secureConnectEnd(call, handshake)
            printEvent("6.2. secureConnectEnd(${call.request().url}, $handshake)")
        }
    }

    private fun guarded(function: () -> Unit) {
        try {
            function()
        } catch (e: Exception) {
            logger?.error("Exception while handling listener response: ${e.message}")
        }
    }

}