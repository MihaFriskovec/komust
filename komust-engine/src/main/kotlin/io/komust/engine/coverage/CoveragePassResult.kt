package io.komust.engine.coverage

import kotlin.time.Duration

/** How a single test finished during the coverage pass. */
public enum class TestOutcome {
    /** The test passed. */
    PASSED,

    /** The test failed or threw — makes the suite **red** (aborts the run). */
    FAILED,

    /** The test aborted (a failed `assumeTrue` etc.) — not a red suite. */
    ABORTED,
}

/**
 * One leaf test's record from the coverage pass: its identity, a human label,
 * how long it ran, and how it finished.
 *
 * The [duration] is the per-test timing ADR-0003 needs to order a mutant's
 * covering tests fastest-first.
 */
public data class TestExecution(
    val id: TestId,
    val displayName: String,
    val duration: Duration,
    val outcome: TestOutcome,
)

/**
 * The output of a green coverage pass (ADR-0004): the normalised
 * [CoverageIndex] plus every leaf test's [TestExecution] record.
 *
 * Only produced when the suite was green — a red suite throws
 * [RedBaselineException] before a result is built.
 */
public class CoveragePassResult internal constructor(
    public val index: CoverageIndex,
    public val tests: List<TestExecution>,
) {
    private val byId: Map<TestId, TestExecution> = tests.associateBy { it.id }

    /** Number of leaf tests executed. */
    public val testCount: Int get() = tests.size

    /** The recorded wall-clock time for [id], or `null` if it was not executed. */
    public fun timing(id: TestId): Duration? = byId[id]?.duration

    public fun execution(id: TestId): TestExecution? = byId[id]

    override fun toString(): String =
        "CoveragePassResult(tests=${tests.size}, index=$index)"
}
