package com.bluetriangle.analytics.checkout

import com.bluetriangle.analytics.Timer
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

    private fun purchaseTimer(orderNumber: String) =
        Timer("Confirmation", null).apply {
            setOrderNumber(orderNumber)
            setCartValue(99.5)
            setCartCount(3)
            setCartCountCheckout(2)
        }

    private fun assertIsPurchase(timer: Timer, orderNumber: String) {
        assertEquals(orderNumber, timer.getField(Timer.FIELD_ORDER_NUMBER))
        assertEquals("99.5", timer.getField(Timer.FIELD_CART_VALUE))
        assertFalse(timer.getFields().containsKey(Timer.FIELD_PAGE_VALUE))
    }

    @Test
    fun `first purchase is sent unchanged`() {
        val timer = purchaseTimer("ORD-1")
        deduplicator.deduplicate(timer, "session-1")
        assertIsPurchase(timer, "ORD-1")
    }

    @Test
    fun `repeated order id in same session moves cart value to page value`() {
        deduplicator.deduplicate(purchaseTimer("ORD-1"), "session-1")

        val repeat = purchaseTimer("ORD-1")
        deduplicator.deduplicate(repeat, "session-1")

        assertEquals("99.5", repeat.getField(Timer.FIELD_PAGE_VALUE))
        assertEquals("0", repeat.getField(Timer.FIELD_CART_VALUE))
        // Other order data is kept
        assertEquals("ORD-1", repeat.getField(Timer.FIELD_ORDER_NUMBER))
        assertEquals("3", repeat.getField(Timer.FIELD_CART_COUNT))
        assertEquals("2", repeat.getField(Timer.FIELD_CART_COUNT_CHECKOUT))
    }

    @Test
    fun `different order ids in same session are sent unchanged`() {
        deduplicator.deduplicate(purchaseTimer("ORD-1"), "session-1")

        val other = purchaseTimer("ORD-2")
        deduplicator.deduplicate(other, "session-1")

        assertIsPurchase(other, "ORD-2")
    }

    @Test
    fun `same order id in new session is sent unchanged`() {
        deduplicator.deduplicate(purchaseTimer("ORD-1"), "session-1")

        val timer = purchaseTimer("ORD-1")
        deduplicator.deduplicate(timer, "session-2")

        assertIsPurchase(timer, "ORD-1")
    }

    @Test
    fun `timer without order id is never deduplicated`() {
        repeat(2) {
            val timer = Timer("Cart", null).apply { setCartValue(10.0) }
            deduplicator.deduplicate(timer, "session-1")
            assertEquals("10.0", timer.getField(Timer.FIELD_CART_VALUE))
        }
    }
}
