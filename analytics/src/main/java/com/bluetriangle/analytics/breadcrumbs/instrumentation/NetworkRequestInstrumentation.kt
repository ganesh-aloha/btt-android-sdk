package com.bluetriangle.analytics.breadcrumbs.instrumentation

import com.bluetriangle.analytics.breadcrumbs.BreadcrumbEvent
import com.bluetriangle.analytics.breadcrumbs.BreadcrumbsCollector
import com.bluetriangle.analytics.eventhub.sdkeventhub.NetworkEventConsumer
import com.bluetriangle.analytics.eventhub.sdkeventhub.SDKEventHub
import com.bluetriangle.analytics.networkcapture.CapturedRequest

internal class NetworkRequestInstrumentation(breadcrumbsCollector: BreadcrumbsCollector) : NetworkEventConsumer,
    BreadcrumbInstrumentation(breadcrumbsCollector) {

    override fun enable() {
        SDKEventHub.instance.addConsumer(this)
    }

    override fun disable() {
        SDKEventHub.instance.removeConsumer(this)
    }

    override fun onNetworkRequestCaptured(networkRequest: CapturedRequest) {
        val url = networkRequest.url ?: return
        val method = networkRequest.method ?: return
        val statusCode = networkRequest.responseStatusCode ?: return
        addBreadcrumb(url, method, statusCode)
    }

    private fun addBreadcrumb(
        url: String,
        method: String,
        statusCode: Int
    ) = collector.get()?.add(
        BreadcrumbEvent.NetworkRequest(BreadcrumbEvent.NetworkRequest.NetworkRequestData(url, method, statusCode))
    )
}