package io.komust.engine.sweep

import io.komust.engine.coverage.CoveragePassResult
import io.komust.engine.coverage.TestId
import kotlin.time.Duration

/**
 * The **sequential mutant sweep** core (ADR-0003, single in-process worker).
 *
 * For each mutant, in the order supplied:
 *
 *  1. **Select** its covering test set by a direct `(binaryClassName, line)`
 *     lookup into the coverage index (ADR-0004 §2). An empty set is
 *     [MutantStatus.NO_COVERAGE] — the mutant is never run and never counts as a
 *     survivor (ADR-0004 §4).
 *  2. **Order** the covering tests **fastest-first** using the coverage pass's
 *     per-test timing, with the test id as a tie-break so the order is fully
 *     deterministic (a test with no recorded time sorts last).
 *  3. **Switch the mutant on** ([MutantSwitchHandle]), run its covering tests
 *     one at a time **fail-fast**, and switch back to the green baseline. The
 *     first covering test that fails ends the mutant as [MutantStatus.KILLED];
 *     if every covering test passes it is [MutantStatus.SURVIVED].
 *
 * Parallelism, hang-kills and the `TIMEOUT` / memory-error outcomes need a
 * process boundary and arrive with the forked worker pool (#34). This class is
 * the correctness core they build on.
 *
 * @param coveragePass the green coverage pass output — its [CoveragePassResult.index]
 *   drives selection and its per-test timing drives fastest-first ordering.
 * @param testRunner runs one covering test against the active mutant; defaults
 *   to the real JUnit Platform Launcher.
 * @param switch writes the runtime switch; defaults to the process-global slot
 *   resolved from the current context class loader.
 */
public class MutantSweep(
    private val coveragePass: CoveragePassResult,
    private val testRunner: CoveringTestRunner = JUnitPlatformCoveringTestRunner(),
    private val switch: MutantSwitchHandle = MutantSwitchHandle.processGlobal(),
) {

    /** Score every mutant in [mutants], sequentially, in the order given. */
    public fun sweep(mutants: List<Mutant>): SweepResult =
        SweepResult(mutants.map(::score))

    private fun score(mutant: Mutant): MutantResult {
        val covering = coveragePass.index.testsCovering(mutant.coverageKey)
        if (covering.isEmpty()) return MutantResult.noCoverage(mutant)

        val ordered = covering.sortedWith(
            compareBy({ coveragePass.timing(it) ?: Duration.INFINITE }, TestId::uniqueId),
        )

        switch.activate(mutant.id)
        try {
            var executed = 0
            for (test in ordered) {
                executed++
                if (testRunner.run(test) == TestVerdict.FAILED) {
                    return MutantResult.killed(mutant, ordered, killedBy = test, testsExecuted = executed)
                }
            }
            return MutantResult.survived(mutant, ordered)
        } finally {
            switch.clear()
        }
    }
}
