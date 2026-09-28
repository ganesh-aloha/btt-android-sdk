package com.bluetriangle.analytics.checkout.config

import com.bluetriangle.analytics.Constants.DEFAULT_CART_COUNT
import com.bluetriangle.analytics.Constants.DEFAULT_CART_COUNT_CHECKOUT
import com.bluetriangle.analytics.Constants.DEFAULT_CHECKOUT_AMOUNT
import com.bluetriangle.analytics.Constants.DEFAULT_CHECKOUT_TRACKING_ENABLED
import com.bluetriangle.analytics.Constants.DEFAULT_TIMER_VALUE
import com.bluetriangle.analytics.SdkCheckoutConfig

internal class CheckoutConfig(
    val isEnabled: Boolean,
    val classNames: List<String>,
    val networkUrlPattern: String?,
    val checkoutAmount: Double,
    val cartCount: Int,
    val cartCountCheckout: Int,
    val orderNumber: String?,
    val timerValue: Int
) {
    companion object {
        val DEFAULT = CheckoutConfig(
            DEFAULT_CHECKOUT_TRACKING_ENABLED,
            emptyList(),
            null,
            DEFAULT_CHECKOUT_AMOUNT,
            DEFAULT_CART_COUNT,
            DEFAULT_CART_COUNT_CHECKOUT,
            null,
            DEFAULT_TIMER_VALUE
        )
    }

    override fun equals(other: Any?): Boolean {
        if(other !is CheckoutConfig) return false
        return other.isEnabled == isEnabled &&
                other.classNames.joinToString() == classNames.joinToString() &&
                other.networkUrlPattern == networkUrlPattern &&
                other.checkoutAmount == checkoutAmount &&
                other.cartCount == cartCount &&
                other.cartCountCheckout == cartCountCheckout &&
                other.orderNumber == orderNumber &&
                other.timerValue == timerValue
    }

    override fun hashCode(): Int {
        var result = isEnabled.hashCode()
        result = 31 * result + classNames.hashCode()
        result = 31 * result + networkUrlPattern.hashCode()
        result = 31 * result + checkoutAmount.hashCode()
        result = 31 * result + cartCount.hashCode()
        result = 31 * result + cartCountCheckout.hashCode()
        result = 31 * result + orderNumber.hashCode()
        result = 31 * result + timerValue.hashCode()
        return result
    }

    override fun toString(): String {
        return """CheckoutConfig(
            |isEnabled: $isEnabled
            |classNames: "${classNames.joinToString()}"
            |networkUrlPattern: "$networkUrlPattern"
            |checkoutAmount: $checkoutAmount
            |cartCount: $cartCount
            |cartCountCheckout: $cartCountCheckout
            |orderNumber: "$orderNumber"
            |timerValue: $timerValue
            |)
        """.trimMargin()
    }

    fun toSdkConfig(): SdkCheckoutConfig{
        return SdkCheckoutConfig(
            isEnabled = isEnabled,
            classNames = classNames,
            networkUrlPattern = networkUrlPattern,
            checkoutAmount = checkoutAmount,
            cartCount = cartCount,
            cartCountCheckout = cartCountCheckout,
            orderNumber = orderNumber,
            timerValue = timerValue
        )
    }
}