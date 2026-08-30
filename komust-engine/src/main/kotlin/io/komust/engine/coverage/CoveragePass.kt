package io.komust.engine.coverage

import org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots
import org.junit.platform.launcher.Launcher
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.nio.file.Path

/**
 * The engine-input slice the coverage pass consumes (ADR-0005 §Engine input
 * contract). Everything else on the contract — the runtime classpaths, the
 * Mutation Scope, the run config — is either already on the engine JVM's
 * classpath or belongs to the later sweep (#33).
 */
public data class CoveragePassInput(
    /**
     * The mutation compilation's output directories — the **one shared compile**
     * (ADR-0004 §1) that both this pass (mutants off) and the sweep (mutants on)
     * observe. JaCoCo analyses coverage against these and SMAP is read from them.
     */
    val classesUnderTest: List<Path>,
    /**
     * Classpath roots holding the compiled test classes to discover and run.
     * The classes themselves plus a JUnit Platform test engine must be reachable
     * from the current thread's context class loader.
     *
     * Also analysed for coverage and SMAP: a test that calls an `inline` main
     * function directly gets the callee's body inlined into the *test* class, so
     * crediting that inline function needs the test classes' line tables too.
     */
    val testClassRoots: List<Path>,
)

/**
 * The **coverage pass** (ADR-0004): one sequential full-suite run over the
 * unmutated program under the JaCoCo runtime agent, driven through the JUnit
 * Platform Launcher API, producing the [CoverageIndex] and per-test timing — and
 * doubling as the mandatory **green baseline** (ADR-0003): a red suite throws
 * [RedBaselineException] and nothing downstream runs.
 *
 * Run once per source snapshot (caching is the caller's concern). The pass is
 * **not** safe to run concurrently with itself or anything else touching the
 * JVM-global JaCoCo counters.
 *
 * @param input the classes to measure against and the test roots to run.
 * @param agent the JaCoCo runtime agent seam; defaults to the one attached to
 *   this JVM, failing fast if none is (the engine fork must carry
 *   `-javaagent:jacocoagent.jar`, #38).
 * @param launcherFactory override only in tests.
 */
public class CoveragePass(
    private val input: CoveragePassInput,
    private val agent: CoverageAgent = JacocoRuntimeAgent.attached(),
    private val launcherFactory: () -> Launcher = { LauncherFactory.create() },
) {
    // Coverage and inline-line normalisation both need the main *and* test class
    // line tables (see CoveragePassInput.testClassRoots).
    private val allClassDirs = (input.classesUnderTest + input.testClassRoots).distinct()
    private val analyzer = JacocoExecAnalyzer(allClassDirs)
    private val normalizer = InlineLineNormalizer.fromClassesDirs(allClassDirs)

    /** Discover tests from [CoveragePassInput.testClassRoots] and run the pass. */
    public fun run(): CoveragePassResult {
        require(input.testClassRoots.isNotEmpty()) { "no test class roots supplied" }

        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectClasspathRoots(input.testClassRoots.toSet()))
            // Per-test coverage capture needs a sequential run — the JaCoCo
            // counters are one global set per JVM (research doc §a). Guard
            // against a project that enables Jupiter parallelism.
            .configurationParameter("junit.jupiter.execution.parallel.enabled", "false")
            .build()

        val launcher = launcherFactory()
        val coverage = PerTestCoverageListener(agent)
        val summary = SummaryGeneratingListener()

        agent.captureAndReset() // clean slate before the first event
        launcher.execute(request, coverage, summary)

        val stats = summary.summary
        if (stats.testsFoundCount == 0L && stats.containersFailedCount == 0L) {
            throw EmptyTestSuiteException(input.testClassRoots)
        }
        if (stats.failures.isNotEmpty() || stats.containersFailedCount > 0L) {
            throw RedBaselineException(
                failures = stats.failures.map {
                    RedBaselineException.Failure(
                        displayName = it.testIdentifier.displayName,
                        message = it.exception?.message,
                    )
                },
                containersFailed = stats.containersFailedCount.toInt(),
            )
        }

        return CoveragePassResult(buildIndex(coverage), coverage.executions())
    }

    private fun buildIndex(coverage: PerTestCoverageListener): CoverageIndex {
        val builder = CoverageIndexBuilder()
        val inlineCallees = normalizer.calleeVmNames.intersect(analyzer.knownVmNames)
        for ((testId, chunks) in coverage.perTestCoverageChunks()) {
            for ((binaryClass, lines) in analyzer.coveredLines(chunks, alsoAnalyse = inlineCallees)) {
                for (line in lines) {
                    builder.add(binaryClass, line, testId)
                    // Inline-function line normalisation (ADR-0004 §3): a covered
                    // synthetic line of the caller also covers the callee's real
                    // source line, where an inline-body mutant is keyed.
                    normalizer.calleeSite(binaryClass, line)?.let {
                        builder.add(it.binaryClassName, it.line, testId)
                    }
                }
            }
        }
        return builder.build()
    }
}
