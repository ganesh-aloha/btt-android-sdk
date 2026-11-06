package com.bluetriangle.analytics.checkout

import androidx.annotation.VisibleForTesting
import com.bluetriangle.analytics.Timer
import com.bluetriangle.analytics.checkout.event.CheckoutEventReporter.Companion.CHECKOUT_PAGE_NAME
import com.bluetriangle.analytics.utility.logD

/**
 * Keeps track of the order numbers sent in the current session. If a PurchaseConfirmation timer
 * is submitted again with an order number already sent in this session, it is sent without the
 * order data so the same purchase is not counted twice.
 */
internal object PurchaseDeduplicator {

    private const val TAG = "PurchaseDeduplicator"

    private var sessionId: String? = null
    private val sentOrderNumbers = HashSet<String>()

    @Synchronized
    fun deduplicate(timer: Timer, sessionId: String) {
        if (timer.getField(Timer.FIELD_PAGE_NAME) != CHECKOUT_PAGE_NAME) return
        if (!timer.isFieldSet(Timer.FIELD_ORDER_NUMBER)) return
        val orderNumber = timer.getField(Timer.FIELD_ORDER_NUMBER) ?: return

        if (this.sessionId != sessionId) {
            this.sessionId = sessionId
            sentOrderNumbers.clear()
        }
        if (sentOrderNumbers.add(orderNumber)) return

        timer.clearField(Timer.FIELD_ORDER_NUMBER)
        timer.clearField(Timer.FIELD_CART_VALUE)
        timer.clearField(Timer.FIELD_CART_COUNT)
        timer.clearField(Timer.FIELD_CART_COUNT_CHECKOUT)
        logD(TAG, "purchase-deduplicated (orderNumber: \"$orderNumber\", sessionId: $sessionId)")
    }

    @Synchronized
    @VisibleForTesting
    internal fun reset() {
        sessionId = null
        sentOrderNumbers.clear()
    }
}