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

    override fun run(test: TestId): TestVerdict {
        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectUniqueId(test.uniqueId))
            // The covering-test run mirrors the coverage pass: sequential, no
            // Jupiter parallelism (the process-global switch has one slot).
            .configurationParameter("junit.jupiter.execution.parallel.enabled", "false")
            .build()

        val summary = SummaryGeneratingListener()
        launcherFactory().execute(request, summary)
        val stats = summary.summary

        // A selector that resolved to nothing, or a container that blew up in
        // discovery/setup, is not a pass — treat it as a kill so a broken
        // selection is visible rather than silently scoring SURVIVED.
        if (stats.testsFoundCount == 0L || stats.containersFailedCount > 0L) return TestVerdict.FAILED

        return if (stats.totalFailureCount == 0L) TestVerdict.PASSED else TestVerdict.FAILED
    }
}
