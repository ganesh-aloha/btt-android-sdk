package com.bluetriangle.analytics.checkout

import com.bluetriangle.analytics.Timer
import com.bluetriangle.analytics.checkout.event.CheckoutEventReporter.Companion.CHECKOUT_PAGE_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class PurchaseDeduplicatorTest {

    private val deduplicator = PurchaseDeduplicator

    @Before
    fun setUp() {
        deduplicator.reset()
    }

    private fun purchaseTimer(orderNumber: String, pageName: String = CHECKOUT_PAGE_NAME) =
        Timer(pageName, null).apply {
            setOrderNumber(orderNumber)
            setCartValue(99.5)
            setCartCount(3)
            setCartCountCheckout(2)
        }

    private fun assertHasOrderData(timer: Timer, orderNumber: String) {
        assertEquals(orderNumber, timer.getField(Timer.FIELD_ORDER_NUMBER))
        assertEquals("99.5", timer.getField(Timer.FIELD_CART_VALUE))
        assertEquals("3", timer.getField(Timer.FIELD_CART_COUNT))
        assertEquals("2", timer.getField(Timer.FIELD_CART_COUNT_CHECKOUT))
    }

    private fun assertHasNoOrderData(timer: Timer) {
        assertEquals("", timer.getField(Timer.FIELD_ORDER_NUMBER))
        assertEquals("0", timer.getField(Timer.FIELD_CART_VALUE))
        assertFalse(timer.getFields().containsKey(Timer.FIELD_CART_COUNT))
        assertFalse(timer.getFields().containsKey(Timer.FIELD_CART_COUNT_CHECKOUT))
    }

    @Test
    fun `first purchase confirmation is sent with order data`() {
        val timer = purchaseTimer("ORD-1")
        deduplicator.deduplicate(timer, "session-1")
        assertHasOrderData(timer, "ORD-1")
    }

    @Test
    fun `repeated order id in same session is sent without order data`() {
        deduplicator.deduplicate(purchaseTimer("ORD-1"), "session-1")

        val repeat = purchaseTimer("ORD-1")
        deduplicator.deduplicate(repeat, "session-1")

        assertHasNoOrderData(repeat)
        assertEquals(CHECKOUT_PAGE_NAME, repeat.getField(Timer.FIELD_PAGE_NAME))
    }

    @Test
    fun `different order ids in same session keep order data`() {
        deduplicator.deduplicate(purchaseTimer("ORD-1"), "session-1")

        val other = purchaseTimer("ORD-2")
        deduplicator.deduplicate(other, "session-1")

        assertHasOrderData(other, "ORD-2")
    }

    @Test
    fun `same order id in new session keeps order data`() {
        deduplicator.deduplicate(purchaseTimer("ORD-1"), "session-1")

        val timer = purchaseTimer("ORD-1")
        deduplicator.deduplicate(timer, "session-2")

        assertHasOrderData(timer, "ORD-1")
    }

    @Test
    fun `timers other than purchase confirmation are not touched`() {
        deduplicator.deduplicate(purchaseTimer("ORD-1", "Confirmation"), "session-1")

        val repeat = purchaseTimer("ORD-1", "Confirmation")
        deduplicator.deduplicate(repeat, "session-1")

        assertHasOrderData(repeat, "ORD-1")
    }
}
