package com.bluetriangle.analytics.okhttp

import com.bluetriangle.analytics.BlueTriangleConfiguration
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.networkcapture.CapturedRequest
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.HttpURLConnection

class BlueTriangleOkHttpInterceptor(private val configuration: BlueTriangleConfiguration) : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!configuration.shouldSampleNetwork && !configuration.shouldSampleNonFatalErrors) {
            return chain.proceed(chain.request())
        }

        val request: Request = chain.request()

        if (!configuration.shouldSampleNetwork && configuration.shouldSampleNonFatalErrors) {
            val response = chain.proceed(request)
            submitErrorIfAny(request, response)
            return response
        }

        val capturedRequest = CapturedRequest()
        capturedRequest.url = request.url.toString()
        capturedRequest.method = request.method

        capturedRequest.start()
        val response: Response = chain.proceed(request)
        capturedRequest.stop()

        capturedRequest.responseStatusCode = response.code
        capturedRequest.encodedBodySize = response.body?.contentLength() ?: 0
        capturedRequest.requestType = requestTypeFromMediaType(capturedRequest.file, response.body?.contentType())
        capturedRequest.submit()
        submitErrorIfAny(request, response)
        return response
    }

    private fun submitErrorIfAny(request: Request, response: Response) {
        if (configuration.shouldSampleNonFatalErrors && !response.isSuccessful && !response.isRedirect && response.code != HttpURLConnection.HTTP_NOT_MODIFIED) {
            Tracker.instance?.reportNetworkError(
                request.url.toString(),
                request.method,
                response.code,
                response.message
            )
        }
    }
}