package com.bluetriangle.analytics.breadcrumbs

import android.content.Context
import com.bluetriangle.analytics.breadcrumbs.config.BreadcrumbsConfig
import com.bluetriangle.analytics.breadcrumbs.config.BreadcrumbsFeature
import com.bluetriangle.analytics.breadcrumbs.instrumentation.AppInstallInstrumentation
import com.bluetriangle.analytics.breadcrumbs.instrumentation.AppLifecycleInstrumentation
import com.bluetriangle.analytics.breadcrumbs.instrumentation.AppUpdateInstrumentation
import com.bluetriangle.analytics.breadcrumbs.instrumentation.BreadcrumbInstrumentation
import com.bluetriangle.analytics.breadcrumbs.instrumentation.NetworkRequestInstrumentation
import com.bluetriangle.analytics.breadcrumbs.instrumentation.NetworkStateInstrumentation
import com.bluetriangle.analytics.breadcrumbs.instrumentation.SystemEventsInstrumentation
import com.bluetriangle.analytics.breadcrumbs.instrumentation.UiLifecycleInstrumentation
import com.bluetriangle.analytics.breadcrumbs.instrumentation.UserEventInstrumentation
import org.json.JSONArray
import java.lang.ref.WeakReference

internal class BreadcrumbsManager(var config: BreadcrumbsConfig, private val shouldDetectTap: Boolean, private val context: WeakReference<Context>) {
    private var breadcrumbsCollector: BreadcrumbsCollector? = null
    private var instrumentations: MutableMap<BreadcrumbsFeature, BreadcrumbInstrumentation> = mutableMapOf()

    private var features = BreadcrumbsFeature.values().filterNot { config.ignoredFeatures.contains(it) }

    fun install() {
        breadcrumbsCollector = BreadcrumbsCollector(config.capacity, context)

        breadcrumbsCollector?.let { collector ->
            instrumentations = features.associateWith {
                featureToInstrumentation(it, collector)
            }.toMutableMap()
        }

        instrumentations.values.forEach {
            if (it !is UserEventInstrumentation || shouldDetectTap) it.enable()
        }
    }

    fun uninstall() {
        instrumentations.values.forEach {
            it.disable()
        }
        instrumentations = mutableMapOf()
        breadcrumbsCollector?.clear()
        breadcrumbsCollector = null
    }

    fun featureToInstrumentation(feature: BreadcrumbsFeature, collector: BreadcrumbsCollector) = when(feature) {
        BreadcrumbsFeature.AppLifecycle -> AppLifecycleInstrumentation(collector)
        BreadcrumbsFeature.UiLifecycle -> UiLifecycleInstrumentation(collector)
        BreadcrumbsFeature.NetworkRequest -> NetworkRequestInstrumentation(collector)
        BreadcrumbsFeature.NetworkState -> NetworkStateInstrumentation(collector)
        BreadcrumbsFeature.AppInstall -> AppInstallInstrumentation(collector)
        BreadcrumbsFeature.AppUpdate -> AppUpdateInstrumentation(collector)
        BreadcrumbsFeature.UserEvent -> UserEventInstrumentation(collector)
        BreadcrumbsFeature.SystemEvent -> SystemEventsInstrumentation(collector)
    }

    fun updateConfig(config: BreadcrumbsConfig, shouldDetectTap: Boolean) {
        val collector = breadcrumbsCollector?: return
        val newFeatures = BreadcrumbsFeature.values().filterNot { config.ignoredFeatures.contains(it) }
        val toBeDisabled = features.filterNot { newFeatures.contains(it) }
        val toBeEnabled = newFeatures.filterNot { features.contains(it) }

        toBeDisabled.forEach {
            instrumentations[it]?.disable()
            instrumentations.remove(it)
        }
        toBeEnabled.forEach {
            instrumentations[it] = featureToInstrumentation(it, collector)
            // Enable User Event only if tap tracking is enabled
            if (it == BreadcrumbsFeature.UserEvent && !shouldDetectTap) {
                instrumentations[it]?.disable()
                instrumentations.remove(it)
            } else {
                instrumentations[it]?.enable()
            }
        }
    }

    fun snapshot(): JSONArray? {
        return breadcrumbsCollector?.snapshot()
    }

    fun getCachedSnapshot(): JSONArray? {
        return breadcrumbsCollector?.getCachedSnapshot()
    }

    fun dump() {
        breadcrumbsCollector?.dump()
    }

    // Exposed for React Native UI Action events
    fun addEvent(event: BreadcrumbEvent)  {
        breadcrumbsCollector?.add(event)
    }
}