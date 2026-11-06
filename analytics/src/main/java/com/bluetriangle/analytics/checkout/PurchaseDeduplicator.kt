package com.bluetriangle.analytics.checkout

import androidx.annotation.VisibleForTesting
import com.bluetriangle.analytics.Timer
import com.bluetriangle.analytics.utility.logD

/**
 * Keeps track of the order numbers sent in the current session. If a timer is submitted again
 * with an order number already sent in this session, its cart value is moved to page value so
 * the same purchase is not counted twice.
 */
internal object PurchaseDeduplicator {

    private const val TAG = "PurchaseDeduplicator"

    private var sessionId: String? = null
    private val sentOrderNumbers = HashSet<String>()

    @Synchronized
    fun deduplicate(timer: Timer, sessionId: String) {
        if (!timer.isFieldSet(Timer.FIELD_ORDER_NUMBER)) return
        val orderNumber = timer.getField(Timer.FIELD_ORDER_NUMBER) ?: return

        if (this.sessionId != sessionId) {
            this.sessionId = sessionId
            sentOrderNumbers.clear()
        }
        if (sentOrderNumbers.add(orderNumber)) return

        timer.getField(Timer.FIELD_CART_VALUE)?.let { timer.setField(Timer.FIELD_PAGE_VALUE, it) }
        timer.clearField(Timer.FIELD_CART_VALUE)
        logD(TAG, "purchase-deduplicated (orderNumber: \"$orderNumber\", sessionId: $sessionId)")
    }

    @Synchronized
    @VisibleForTesting
    internal fun reset() {
        sessionId = null
        sentOrderNumbers.clear()
    }
}