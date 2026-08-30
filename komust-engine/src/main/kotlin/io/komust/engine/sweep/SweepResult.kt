package io.komust.engine.sweep

import io.komust.engine.coverage.TestId

/**
 * How the sequential sweep scored one mutant (ADR-0003 §Per-mutant test
 * execution, reconciled with #5's `report.json` status enum).
 *
 * v1's in-process single-worker sweep produces only these three — a killing
 * test failure, all covering tests passing, or no covering test at all. The
 * `TIMEOUT` / memory-error outcomes of the full taxonomy arrive with the forked
 * worker pool (#34); they need a process boundary to detect and recover from.
 */
public enum class MutantStatus {
    /** A covering test failed or threw — the suite detected the change. */
    KILLED,

    /** Every covering test passed — a candidate test-quality gap. */
    SURVIVED,

    /**
     * No test executes this mutant's `(class, line)`. Never run (nothing could
     * kill it), surfaced as its own category, never folded into survivors
     * (ADR-0004 §4).
     */
    NO_COVERAGE,
}

/**
 * The verdict for a single mutant.
 *
 * @property mutant the mutant this scores.
 * @property status the outcome.
 * @property coveringTests the mutant's covering test set, already ordered
 *   **fastest-first** (ADR-0003) — the order the sweep ran them in. Empty when
 *   [status] is [MutantStatus.NO_COVERAGE].
 * @property killedBy the first covering test that failed, when [status] is
 *   [MutantStatus.KILLED]; `null` otherwise. Because the sweep is **fail-fast**,
 *   this is the *only* test known to kill the mutant — later covering tests were
 *   not run.
 * @property testsExecuted how many covering tests actually ran. Equals
 *   `coveringTests.size` for a survivor; smaller for a fail-fast kill; `0` for
 *   `NO_COVERAGE`.
 */
public data class MutantResult(
    val mutant: Mutant,
    val status: MutantStatus,
    val coveringTests: List<TestId>,
    val killedBy: TestId?,
    val testsExecuted: Int,
)

/**
 * The outcome of one sequential sweep over a mutant list: every mutant's
 * [MutantResult] in the order supplied, plus roll-up counts.
 */
public class SweepResult(public val results: List<MutantResult>) {

    public val total: Int get() = results.size
    public val killed: Int get() = results.count { it.status == MutantStatus.KILLED }
    public val survived: Int get() = results.count { it.status == MutantStatus.SURVIVED }
    public val noCoverage: Int get() = results.count { it.status == MutantStatus.NO_COVERAGE }

    /** The result for the mutant with [id], or `null` if it was not in the sweep. */
    public fun forMutant(id: String): MutantResult? = results.firstOrNull { it.mutant.id == id }

    override fun toString(): String =
        "SweepResult(total=$total, killed=$killed, survived=$survived, noCoverage=$noCoverage)"
}
