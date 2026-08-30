package io.komust.engine.sweep

import io.komust.engine.coverage.TestId

/**
 * How a sweep scored one mutant (ADR-0003 §Per-mutant test execution, §Outcome
 * taxonomy, reconciled with #5's `report.json` status enum).
 *
 * The sequential in-process sweep ([MutantSweep]) produces only [KILLED],
 * [SURVIVED] and [NO_COVERAGE] — it has no process boundary to detect a hang or
 * recover from a memory error. The forked worker pool ([ForkedMutantSweep], #34)
 * adds [TIMEOUT]; a mutant that OOMs or stack-overflows is folded into [KILLED]
 * (the internal memory-error cause is tracked only long enough to recycle the
 * worker whose heap is now unstable).
 */
public enum class MutantStatus {
    /**
     * A covering test failed or threw — the suite detected the change. Also the
     * outcome for a mutant that made a covering test run out of memory or
     * overflow the stack (ADR-0003 §Outcome taxonomy).
     */
    KILLED,

    /** Every covering test passed — a candidate test-quality gap. */
    SURVIVED,

    /**
     * No test executes this mutant's `(class, line)`. Never run (nothing could
     * kill it), surfaced as its own category, never folded into survivors
     * (ADR-0004 §4).
     */
    NO_COVERAGE,

    /**
     * A covering test ran past its baseline-relative timeout budget — a probable
     * non-terminating mutant (ADR-0003 §Hang detection). **Counts as killed**
     * for scoring: the mutant produced detectable behavioural divergence. Only
     * the forked sweep can produce this; detecting it needs a killable process.
     */
    TIMEOUT,
}

/**
 * The verdict for a single mutant.
 *
 * @property mutant the mutant this scores.
 * @property status the outcome.
 * @property coveringTests the mutant's covering test set, ordered
 *   **fastest-first** (ADR-0003) — the order the sweep visits them in. This is
 *   the whole selected set; on a fail-fast [MutantStatus.KILLED] only the first
 *   [testsExecuted] of them actually ran. Empty when [status] is
 *   [MutantStatus.NO_COVERAGE].
 * @property killedBy the first covering test that failed, when [status] is
 *   [MutantStatus.KILLED] off a test failure. `null` for a survivor, for
 *   `NO_COVERAGE`, for a `TIMEOUT`, and for a `KILLED` caused by a memory error
 *   (no single test "failed" — the JVM did). Because a sweep is **fail-fast**,
 *   when present this is the *only* test known to kill the mutant.
 * @property testsExecuted how many covering tests actually ran. Equals
 *   `coveringTests.size` for a survivor; smaller for a fail-fast kill; the index
 *   of the offending test (1-based) for a `TIMEOUT`; `0` for `NO_COVERAGE`.
 */
public data class MutantResult(
    val mutant: Mutant,
    val status: MutantStatus,
    val coveringTests: List<TestId>,
    val killedBy: TestId?,
    val testsExecuted: Int,
) {
    internal companion object {
        fun noCoverage(mutant: Mutant) =
            MutantResult(mutant, MutantStatus.NO_COVERAGE, emptyList(), killedBy = null, testsExecuted = 0)

        /**
         * A kill. [killedBy] is the failing covering test for a test-failure
         * kill, or `null` when the JVM (not a single test) died — a memory-error
         * kill (OOM / `StackOverflowError`), tracked as `KILLED` per ADR-0003.
         */
        fun killed(mutant: Mutant, coveringTests: List<TestId>, killedBy: TestId?, testsExecuted: Int) =
            MutantResult(mutant, MutantStatus.KILLED, coveringTests, killedBy, testsExecuted)

        fun timedOut(mutant: Mutant, coveringTests: List<TestId>, testsExecuted: Int) =
            MutantResult(mutant, MutantStatus.TIMEOUT, coveringTests, killedBy = null, testsExecuted = testsExecuted)

        fun survived(mutant: Mutant, coveringTests: List<TestId>) =
            MutantResult(mutant, MutantStatus.SURVIVED, coveringTests, killedBy = null, testsExecuted = coveringTests.size)
    }
}

/**
 * The outcome of one sweep over a mutant list: every mutant's [MutantResult] in
 * the order supplied (regardless of the order workers actually scored them in),
 * plus roll-up counts.
 */
public class SweepResult(public val results: List<MutantResult>) {

    private val byMutantId: Map<String, MutantResult> = results.associateBy { it.mutant.id }

    public val total: Int get() = results.size
    public val killed: Int get() = results.count { it.status == MutantStatus.KILLED }
    public val survived: Int get() = results.count { it.status == MutantStatus.SURVIVED }
    public val noCoverage: Int get() = results.count { it.status == MutantStatus.NO_COVERAGE }
    public val timedOut: Int get() = results.count { it.status == MutantStatus.TIMEOUT }

    /**
     * Mutants the suite detected: [killed] plus [timedOut]. Both count as killed
     * for scoring — the mutant produced detectable divergence (ADR-0003
     * §Outcome taxonomy).
     */
    public val detected: Int get() = killed + timedOut

    /** The result for the mutant with [id], or `null` if it was not in the sweep. */
    public fun forMutant(id: String): MutantResult? = byMutantId[id]

    override fun toString(): String =
        "SweepResult(total=$total, detected=$detected (killed=$killed, timedOut=$timedOut), " +
            "survived=$survived, noCoverage=$noCoverage)"
}
