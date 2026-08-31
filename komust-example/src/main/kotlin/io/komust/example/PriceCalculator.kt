package io.komust.example

import io.komust.runtime.SuppressMutations

/** Customer tier. GOLD members earn extra perks. */
enum class Tier { STANDARD, GOLD }

/** A discount coupon: [code] unlocks [percentOff] percent off the subtotal. */
data class Coupon(val code: String, val percentOff: Int)

/**
 * A small pricing / shopping-cart calculator — the sample under mutation test.
 *
 * Each method is written to exercise a different family of komust's default
 * mutation operators (arithmetic, relational-boundary, equality-swap,
 * boolean-logic, boolean-return, nullable-return, constant-boundary, increment,
 * empty-return). The accompanying [PriceCalculatorTest] deliberately shapes the
 * signal komust reports:
 *
 *  - [lineTotal], [isFreeShipping], [findCoupon] are tested thoroughly — every
 *    mutant is KILLED;
 *  - [discountRate] is tested except at its bulk-quantity boundary — komust
 *    surfaces the untested boundary as SURVIVORs (see the README);
 *  - [loyaltyPoints] has no test at all — komust flags every mutant NO_COVERAGE;
 *  - [checkout] carries [@SuppressMutations][SuppressMutations] — komust weaves
 *    no mutants anywhere inside it.
 */
class PriceCalculator {

    private val knownCoupons = listOf(
        Coupon("SAVE10", 10),
        Coupon("VIP25", 25),
    )

    /**
     * Price of one order line.
     *
     * The `require(...)` precondition is a komust *skip-list* construct: komust
     * never mutates inside `require` / `check` / `error` / `assert`, so the
     * `qty > 0` guard sits unmutated even though the arithmetic beside it does not.
     */
    fun lineTotal(price: Int, qty: Int): Int {
        require(qty > 0) { "qty must be positive" }
        return price * qty
    }

    /**
     * Percentage discount for an order: a bulk discount once quantity reaches a
     * threshold, plus a loyalty bonus for GOLD members.
     *
     * The `qty >= 10` boundary is the one the tests never pin — the source of the
     * example's surviving mutants.
     */
    fun discountRate(qty: Int, tier: Tier): Int {
        val bulk = if (qty >= 10) 15 else 0
        val loyalty = if (tier == Tier.GOLD) 10 else 0
        return bulk + loyalty
    }

    /** Free shipping over a threshold, or for GOLD members regardless of total. */
    fun isFreeShipping(total: Int, tier: Tier): Boolean {
        return total >= 100 || tier == Tier.GOLD
    }

    /** Look up a coupon by its code, or `null` if there is no such coupon. */
    fun findCoupon(code: String): Coupon? {
        return knownCoupons.firstOrNull { it.code == code }
    }

    /**
     * Loyalty points per order line — a running point per item plus a tenth of
     * each line's price. Shipped without a single test (see the README): komust
     * flags every mutant here — the `tally++` increment, the arithmetic, the
     * `return points` — as NO_COVERAGE.
     */
    fun loyaltyPoints(prices: List<Int>): List<Int> {
        if (prices.isEmpty()) return emptyList()
        val points = mutableListOf<Int>()
        var tally = 0
        for (price in prices) {
            tally++
            points.add(price / 10 + tally)
        }
        return points
    }

    /**
     * Ties the calculator together: subtotal, discount, and a logged side effect.
     *
     * Carries [SuppressMutations] — the escape hatch for code whose mutants would
     * be noise (here, the logging and the glue). komust weaves no mutants anywhere
     * in this function, so it never appears in the report.
     */
    @SuppressMutations
    fun checkout(prices: List<Int>, qty: Int, tier: Tier, coupon: String?): Int {
        var subtotal = 0
        for (price in prices) {
            subtotal += lineTotal(price, qty)
        }
        val couponOff = coupon?.let { findCoupon(it)?.percentOff } ?: 0
        val rate = discountRate(qty, tier) + couponOff
        val total = subtotal - subtotal * rate / 100
        println("[komust-example] checkout: subtotal=$subtotal rate=$rate total=$total")
        return total
    }
}
