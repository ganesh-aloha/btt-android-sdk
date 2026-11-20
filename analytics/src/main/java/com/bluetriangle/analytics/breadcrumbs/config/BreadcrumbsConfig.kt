package com.bluetriangle.analytics.breadcrumbs.config

import com.bluetriangle.analytics.Constants.DEFAULT_BREADCRUMBS_CAPACITY
import com.bluetriangle.analytics.Constants.DEFAULT_ENABLE_BREADCRUMBS

internal class BreadcrumbsConfig(
    val isEnabled: Boolean,
    val capacity: Int,
    val ignoredFeatures: List<BreadcrumbsFeature>
) {
    override fun toString(): String {
        return """BreadcrumbsConfig(
            |isEnabled: ${isEnabled},
            |capacity: ${capacity},
            |ignoredFeatures: ${ignoredFeatures.joinToString()}
            |)
        """.trimMargin()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BreadcrumbsConfig) return false

        if (isEnabled != other.isEnabled) return false
        if (capacity != other.capacity) return false
        if (ignoredFeatures != other.ignoredFeatures) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isEnabled.hashCode()
        result = 31 * result + capacity
        result = 31 * result + ignoredFeatures.hashCode()
        return result
    }


    companion object {
        val DEFAULT = BreadcrumbsConfig(
            DEFAULT_ENABLE_BREADCRUMBS,
            DEFAULT_BREADCRUMBS_CAPACITY,
            emptyList()
        )
    }
}