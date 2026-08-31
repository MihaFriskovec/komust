package io.komust.example

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [PriceCalculator], written to demonstrate what komust reports:
 *
 *  - [lineTotal], [isFreeShipping], [findCoupon] — thoroughly tested, every
 *    mutant KILLED.
 *  - [discountRate] — tested at qty 5 and 20 but **never at the qty=9 / qty=10
 *    boundary**, so the `qty >= 10` bulk boundary mutants SURVIVE. The README
 *    walks through fixing this.
 *  - [loyaltyPoints] — deliberately has **no test**, so komust reports it as
 *    NO_COVERAGE.
 *  - [checkout] — one smoke test for realism; it carries `@SuppressMutations`, so
 *    komust reports nothing about it either way.
 */
class PriceCalculatorTest {

    private val calc = PriceCalculator()

    // --- lineTotal: exact assertions kill the arithmetic + empty-return mutants ---

    @Test
    fun `lineTotal multiplies price by quantity`() {
        assertEquals(300, calc.lineTotal(100, 3))
        assertEquals(100, calc.lineTotal(50, 2))
    }

    // --- discountRate: covered away from the boundary → the boundary mutants survive ---

    @Test
    fun `discountRate has no bulk discount below the threshold`() {
        assertEquals(0, calc.discountRate(5, Tier.STANDARD))
        assertEquals(10, calc.discountRate(5, Tier.GOLD))
    }

    @Test
    fun `discountRate applies the bulk discount well past the threshold`() {
        assertEquals(15, calc.discountRate(20, Tier.STANDARD))
        assertEquals(25, calc.discountRate(20, Tier.GOLD))
    }

    // --- isFreeShipping: the boundary IS pinned here (contrast with discountRate) ---

    @Test
    fun `isFreeShipping is true at exactly the threshold`() {
        assertTrue(calc.isFreeShipping(100, Tier.STANDARD))
    }

    @Test
    fun `isFreeShipping is false just below the threshold`() {
        assertFalse(calc.isFreeShipping(99, Tier.STANDARD))
    }

    @Test
    fun `isFreeShipping is always true for GOLD members`() {
        assertTrue(calc.isFreeShipping(50, Tier.GOLD))
    }

    @Test
    fun `isFreeShipping is false for a small STANDARD order`() {
        assertFalse(calc.isFreeShipping(50, Tier.STANDARD))
    }

    // --- findCoupon: both found (with percentOff) and not-found kill every mutant ---

    @Test
    fun `findCoupon returns the matching coupon`() {
        assertEquals(Coupon("SAVE10", 10), calc.findCoupon("SAVE10"))
        assertEquals(Coupon("VIP25", 25), calc.findCoupon("VIP25"))
    }

    @Test
    fun `findCoupon returns null for an unknown code`() {
        assertNull(calc.findCoupon("NOPE"))
    }

    // --- checkout: one realism smoke test; suppressed, so irrelevant to the signal ---

    @Test
    fun `checkout applies the discount to the subtotal`() {
        // 2 lines of 100 x qty 20, GOLD → subtotal 4000, rate 25% → 3000.
        assertEquals(3000, calc.checkout(listOf(100, 100), 20, Tier.GOLD, null))
    }

    // NOTE: loyaltyPoints has NO test on purpose — komust reports it NO_COVERAGE.
}
