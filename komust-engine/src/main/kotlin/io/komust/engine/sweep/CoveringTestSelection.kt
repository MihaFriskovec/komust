package io.komust.engine.sweep

import io.komust.engine.coverage.CoveragePassResult
import io.komust.engine.coverage.TestId
import kotlin.time.Duration

/**
 * **Test selection** for one mutant (ADR-0004 §2, ADR-0003 §Per-mutant test
 * execution) — the single piece of logic the sequential ([MutantSweep]) and
 * forked ([ForkedMutantSweep]) sweeps must agree on exactly:
 *
 *  1. the covering test set is a direct `(binary class name, source line)` lookup
 *     into the coverage index — an empty set is `NO_COVERAGE`, never a survivor;
 *  2. the set is ordered **fastest-first** by the coverage pass's per-test
 *     timing, with the test id as a tie-break so a covering set with equal or
 *     missing timings still has one deterministic order (a test with no recorded
 *     time sorts last).
 */
internal object CoveringTestSelection {

    /**
     * The covering tests for [mutant], ordered fastest-first. Empty when no test
     * covers the mutant's `(class, line)` — the caller scores that
     * [MutantStatus.NO_COVERAGE].
     */
    fun select(coveragePass: CoveragePassResult, mutant: Mutant): List<TestId> =
        coveragePass.index.testsCovering(mutant.coverageKey).sortedWith(
            compareBy({ coveragePass.timing(it) ?: Duration.INFINITE }, TestId::uniqueId),
        )
}
