package com.bluetriangle.analytics.breadcrumbs.touchresolver

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

fun View.toViewTargetInfo(tapX: Float, tapY: Float): TapTarget.ViewTarget {
    // React Native compiles 'testID' props into the view's Tag property on Android.
    val reactTestId = this.tag?.toString()?.takeIf { it.isNotEmpty() }

    // Use testID first if available; otherwise, look for a native XML resource identifier.
    val resourceId: String? = reactTestId ?: runCatching {
        if (id != View.NO_ID) resources.getResourceName(id) else null
    }.getOrNull()

    var text: String? = (this as? TextView)?.text?.toString()?.takeIf { it.isNotEmpty() }

    // React Native views (ReactViewGroup) house text inside nested ReactTextView children.
    // If text is null and this is a container, look deep into its hierarchy.
    if (text == null && this is ViewGroup) {
        text = this.findDeepText()
    }

    return TapTarget.ViewTarget(
        className = javaClass.simpleName,
        resourceId = resourceId,
        contentDescription = contentDescription?.toString(),
        text = text,
        tapX,
        tapY
    )
}

private fun ViewGroup.findDeepText(): String? {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child is TextView && child.text.isNotEmpty()) {
            return child.text.toString()
        } else if (child is ViewGroup) {
            val nestedText = child.findDeepText()
            if (nestedText != null) return nestedText
        }
    }

    return null
}