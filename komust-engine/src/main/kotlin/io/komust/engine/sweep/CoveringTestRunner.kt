package io.komust.engine.sweep

import io.komust.engine.coverage.TestId
import org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId
import org.junit.platform.launcher.Launcher
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener

/** Whether one covering test detected the active mutant. */
public enum class TestVerdict {
    /** The test passed — it did not kill the active mutant. */
    PASSED,

    /** The test failed or threw — the active mutant is killed. */
    FAILED,
}

/**
 * Runs a single covering test against the **currently active** mutant and
 * reports whether it killed it.
 *
 * The sweep drives this one test at a time so it can stop at the first killing
 * test — **fail-fast** (ADR-0003). The mutant is already switched on when [run]
 * is called; the runner only executes [test].
 *
 * A seam so the sweep is unit-testable against a fake; the production
 * implementation is [JUnitPlatformCoveringTestRunner].
 */
public fun interface CoveringTestRunner {
    public fun run(test: TestId): TestVerdict
}

/**
 * One covering test's outcome plus the throwables behind a [TestVerdict.FAILED],
 * so a caller can tell a plain assertion failure from a `VirtualMachineError`
 * (OOM / `StackOverflowError`) that JUnit caught and reported as a test failure.
 * The forked worker ([io.komust.engine.sweep.forked.worker.WorkerMain]) needs
 * that distinction to recycle a worker whose heap is now suspect (ADR-0003
 * §Outcome taxonomy).
 */
public data class CoveringTestOutcome(val verdict: TestVerdict, val failures: List<Throwable>) {

    /** True when a failure (or one of its causes) is a JVM memory error. */
    public val isMemoryError: Boolean
        get() = failures.any { failure ->
            generateSequence<Throwable>(failure) { it.cause }.any { it is VirtualMachineError }
        }
}

/**
 * A covering test the coverage index recorded no longer resolves to anything on
 * the test classpath — the suite changed since the coverage pass, or the roots
 * are wrong.
 *
 * Terminal, like the coverage package's [io.komust.engine.coverage.EmptyTestSuiteException]:
 * scoring the mutant `KILLED` off a test that never ran would inflate the
 * mutation score and hide a real gap, so the sweep fails loudly instead.
 */
public class UnresolvableCoveringTestException(public val test: TestId) : RuntimeException(
    "covering test '$test' from the coverage index resolved to no runnable test — " +
        "the test suite changed since the coverage pass, or the test classpath roots are wrong",
)

/**
 * The production [CoveringTestRunner]: re-selects one test by its
 * `TestIdentifier.getUniqueId()` (the [TestId] the coverage index stored) and
 * runs it through the **JUnit Platform Launcher API** directly (ADR-0005 §4).
 *
 * Each call is its own `Launcher.execute` over exactly one `UniqueIdSelector`,
 * which is what gives the sweep fail-fast for free: the sweep simply stops
 * calling once a test comes back [TestVerdict.FAILED].
 *
 * The test classes and a JUnit Platform test engine must be reachable from the
 * current thread's context class loader — the same requirement, and the same
 * loader graph, as the coverage pass (ADR-0004 §0).
 *
 * @param launcherFactory override only in tests.
 */
public class JUnitPlatformCoveringTestRunner(
    private val launcherFactory: () -> Launcher = { LauncherFactory.create() },
) : CoveringTestRunner {

    override fun run(test: TestId): TestVerdict = runReportingFailures(test).verdict

    /**
     * Like [run], but also surfaces the throwables behind a
     * [TestVerdict.FAILED] — the forked worker uses [CoveringTestOutcome.isMemoryError]
     * to decide whether it must recycle itself.
     */
    public fun runReportingFailures(test: TestId): CoveringTestOutcome {
        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectUniqueId(test.uniqueId))
            // The covering-test run mirrors the coverage pass: sequential, no
            // Jupiter parallelism (the process-global switch has one slot).
            .configurationParameter("junit.jupiter.execution.parallel.enabled", "false")
            .build()

        val summary = SummaryGeneratingListener()
        launcherFactory().execute(request, summary)
        val stats = summary.summary

        // A selector that resolved to nothing is a wiring/suite-drift bug, not a
        // survivor and not a kill — fail loudly (matches the coverage package's
        // stance on an empty test set).
        if (stats.testsFoundCount == 0L) throw UnresolvableCoveringTestException(test)

        val failures = stats.failures.mapNotNull { it.exception }
        // A failed container (a throwing @BeforeEach/@BeforeAll) means the
        // covering test could not complete under the mutant — detected
        // divergence, scored as a kill (ADR-0003 outcome taxonomy).
        val failed = stats.containersFailedCount > 0L || stats.totalFailureCount > 0L
        return CoveringTestOutcome(
            verdict = if (failed) TestVerdict.FAILED else TestVerdict.PASSED,
            failures = failures,
        )
    }
}
