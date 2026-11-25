package com.bluetriangle.analytics.checkout

import androidx.annotation.VisibleForTesting
import com.bluetriangle.analytics.Timer
import com.bluetriangle.analytics.utility.logD
import java.util.concurrent.TimeUnit

/**
 * Keeps track of the order numbers sent in the current session. If a timer is submitted again
 * with an order number already sent in this session within the last hour, its cart value is moved
 * to page value so the same purchase is not counted twice.
 */
internal object PurchaseDeduplicator {

    private const val TAG = "PurchaseDeduplicator"

    private val DEDUPLICATION_WINDOW_MILLIS = TimeUnit.HOURS.toMillis(1)

    @VisibleForTesting
    internal var currentTimeMillis: () -> Long = System::currentTimeMillis

    private var sessionId: String? = null

    /** Order number to the time it was last sent as a purchase. */
    private val sentOrderNumbers = HashMap<String, Long>()

    @Synchronized
    fun deduplicate(timer: Timer, sessionId: String) {
        if (!timer.isFieldSet(Timer.FIELD_ORDER_NUMBER)) return
        val orderNumber = timer.getField(Timer.FIELD_ORDER_NUMBER) ?: return

        if (this.sessionId != sessionId) {
            this.sessionId = sessionId
            sentOrderNumbers.clear()
        }

        val now = currentTimeMillis()
        sentOrderNumbers.values.removeAll { now - it >= DEDUPLICATION_WINDOW_MILLIS }

        if (!sentOrderNumbers.containsKey(orderNumber)) {
            sentOrderNumbers[orderNumber] = now
            return
        }

        timer.getField(Timer.FIELD_CART_VALUE)?.let { timer.setField(Timer.FIELD_PAGE_VALUE, it) }
        timer.clearField(Timer.FIELD_CART_VALUE)
        logD(TAG, "purchase-deduplicated (orderNumber: \"$orderNumber\", sessionId: $sessionId)")
    }

    @Synchronized
    @VisibleForTesting
    internal fun reset() {
        sessionId = null
        sentOrderNumbers.clear()
        currentTimeMillis = System::currentTimeMillis
    }
}