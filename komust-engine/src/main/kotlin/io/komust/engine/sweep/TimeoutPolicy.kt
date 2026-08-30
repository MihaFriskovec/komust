package io.komust.engine.sweep

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The **baseline-relative timeout budget** for one covering test (ADR-0003
 * §Per-mutant test execution):
 *
 * ```
 * budget = baseConstant + factor × baselineTime
 * ```
 *
 * where `baselineTime` is the test's own wall-clock time from the green coverage
 * pass. A genuinely slow-but-correct test gets a proportionally longer budget
 * instead of being flagged as a hang; a fast test gets a tight one so a true
 * non-terminating mutant is caught quickly.
 *
 * When the coverage pass recorded **no** time for a test (it was added since, or
 * never ran a leaf) the budget falls back to a fixed [ceiling].
 *
 * @property baseConstant the fixed floor added to every budget — absorbs JVM /
 *   scheduling jitter so a sub-millisecond test still gets a usable budget.
 * @property factor how many multiples of the baseline time to allow on top of
 *   [baseConstant].
 * @property ceiling the flat budget for a test with no recorded baseline time.
 */
public data class TimeoutPolicy(
    val baseConstant: Duration = 3.seconds,
    val factor: Double = 3.0,
    val ceiling: Duration = 30.seconds,
) {
    init {
        require(baseConstant >= Duration.ZERO) { "baseConstant must be >= 0, was $baseConstant" }
        require(factor >= 0.0 && factor.isFinite()) { "factor must be finite and >= 0, was $factor" }
        require(ceiling > Duration.ZERO) { "ceiling must be > 0, was $ceiling" }
    }

    /**
     * The timeout budget for a covering test whose green-baseline wall-clock time
     * was [baselineTime], or `null` if the coverage pass has no record for it
     * (→ [ceiling]).
     */
    public fun budgetFor(baselineTime: Duration?): Duration =
        if (baselineTime == null) ceiling else baseConstant + baselineTime * factor
}
