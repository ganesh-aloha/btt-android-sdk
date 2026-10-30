package com.bluetriangle.analytics.breadcrumbs.touchresolver

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView

fun View.toViewTargetInfo(tapX: Float, tapY: Float): TapTarget.ViewTarget {
    val isReactView = isReactNativeView()

    // React Native compiles 'testID' props into the view's Tag property on Android. Only trust the tag
    // on RN views: native apps often keep ViewHolders/models there, whose toString() may carry PII.
    val reactTestId = if (isReactView) (tag as? String)?.takeIf { it.isNotEmpty() } else null

    // Use testID first if available; otherwise, look for a native XML resource identifier.
    val resourceId: String? = reactTestId ?: runCatching {
        if (id != View.NO_ID) resources.getResourceName(id) else null
    }.getOrNull()

    var text: String? = (this as? TextView)?.text?.toString()?.takeIf { it.isNotEmpty() }

    // React Native views (ReactViewGroup) house text inside nested ReactTextView children.
    // If text is null and this is a container, look deep into its hierarchy.
    if (text == null && isReactView && this is ViewGroup) {
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
        // Never pick up typed user input from an EditText inside the container
        if (child is TextView && child !is EditText && child.text.isNotEmpty()) {
            return child.text.toString()
        } else if (child is ViewGroup) {
            val nestedText = child.findDeepText()
            if (nestedText != null) return nestedText
        }
    }

    return null
}

private fun View.isReactNativeView(): Boolean = javaClass.name.startsWith("com.facebook.react.")