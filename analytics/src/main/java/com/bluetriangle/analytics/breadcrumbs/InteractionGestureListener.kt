package com.bluetriangle.analytics.breadcrumbs

import android.app.Activity
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import com.bluetriangle.analytics.breadcrumbs.touchresolver.TapTarget
import com.bluetriangle.analytics.breadcrumbs.touchresolver.TapTargetResolver

internal class InteractionGestureListener(
    val activity: Activity,
    val userEvent: (UserEventType, TapTarget?) -> Unit,
) : SimpleOnGestureListener() {

    override fun onSingleTapUp(e: MotionEvent): Boolean {

        userEvent(
            UserEventType.TAP,
            TapTargetResolver.resolve(activity, e.rawX, e.rawY)
        )

        return super.onSingleTapUp(e)
    }

}

enum class UserEventType(val value: String) {
    TAP("tap")
}