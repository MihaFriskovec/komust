package io.komust.engine.coverage

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The JUnit Platform listener that turns one sequential full-suite run into
 * per-test coverage (research doc §b).
 *
 * The JaCoCo probe counters are one global set per JVM, so the listener slices
 * that stream at every start/finish boundary via [CoverageAgent.captureAndReset]:
 *
 *  - **Container** boundaries carry setup/teardown coverage. On a container's
 *    `executionStarted` the counters are grabbed (crediting the enclosing scope
 *    with anything since the last boundary) and a fresh setup buffer is pushed;
 *    on its `executionStarted` a leaf test first grabs the counters into its
 *    parent container's buffer — that is where a `@BeforeAll` lands.
 *  - **Leaf test** coverage is what accumulates between its `executionStarted`
 *    and `executionFinished`. Its final coverage is that slice **merged with
 *    every ancestor container's setup buffer**, so production code reached only
 *    from `@BeforeAll` is credited to every test of that class rather than lost.
 *
 * `@AfterAll`-only coverage (captured after the last child finishes) has no leaf
 * to attribute to and is dropped — an accepted v1 edge (ADR-0004 §Consequences).
 *
 * Requires a **sequential** run: overlapping tests would cross-contaminate the
 * global counters (research doc §a).
 */
public class PerTestCoverageListener(private val agent: CoverageAgent) : TestExecutionListener {

    private data class Pending(val startNanos: Long, val displayName: String)

    private val pending = ConcurrentHashMap<TestId, Pending>()
    private val lock = Any()

    /** Each active container's accumulated setup-coverage chunks, outermost first. */
    private val containerStack = ArrayDeque<MutableList<ByteArray>>()

    /** Per leaf test: its own slice plus every ancestor container's setup chunks. */
    private val coverageChunks = LinkedHashMap<TestId, List<ByteArray>>()
    private val executions = ArrayList<TestExecution>()

    override fun executionStarted(testIdentifier: TestIdentifier) {
        when {
            testIdentifier.isContainer -> {
                val beforeThisContainer = agent.captureAndReset()
                synchronized(lock) {
                    containerStack.lastOrNull()?.add(beforeThisContainer)
                    containerStack.addLast(mutableListOf())
                }
            }

            testIdentifier.isTest -> {
                val setup = agent.captureAndReset()
                synchronized(lock) { containerStack.lastOrNull()?.add(setup) }
                pending[testIdentifier.toTestId()] =
                    Pending(System.nanoTime(), testIdentifier.displayName)
            }
        }
    }

    override fun executionFinished(
        testIdentifier: TestIdentifier,
        testExecutionResult: TestExecutionResult,
    ) {
        when {
            testIdentifier.isContainer -> {
                val trailing = agent.captureAndReset() // @AfterAll etc. — no leaf to credit
                synchronized(lock) {
                    containerStack.removeLastOrNull()
                    containerStack.lastOrNull()?.add(trailing)
                }
            }

            testIdentifier.isTest -> {
                val endNanos = System.nanoTime()
                val own = agent.captureAndReset()
                val id = testIdentifier.toTestId()
                val started = pending.remove(id)
                val duration =
                    if (started != null) (endNanos - started.startNanos).nanoseconds else Duration.ZERO

                synchronized(lock) {
                    val merged = ArrayList<ByteArray>()
                    merged += own
                    containerStack.forEach { merged += it }
                    coverageChunks[id] = merged
                    executions += TestExecution(
                        id = id,
                        displayName = started?.displayName ?: testIdentifier.displayName,
                        duration = duration,
                        outcome = testExecutionResult.status.toOutcome(),
                    )
                }
            }
        }
    }

    /**
     * Per leaf test, the JaCoCo `.exec` chunks whose union is that test's
     * coverage (its own slice + inherited container setup), in execution order.
     */
    public fun perTestCoverageChunks(): Map<TestId, List<ByteArray>> =
        synchronized(lock) { LinkedHashMap(coverageChunks) }

    /** Every leaf test's record, in execution order. */
    public fun executions(): List<TestExecution> =
        synchronized(lock) { ArrayList(executions) }

    private fun TestIdentifier.toTestId() = TestId(uniqueId)

    private fun TestExecutionResult.Status.toOutcome(): TestOutcome = when (this) {
        TestExecutionResult.Status.SUCCESSFUL -> TestOutcome.PASSED
        TestExecutionResult.Status.FAILED -> TestOutcome.FAILED
        TestExecutionResult.Status.ABORTED -> TestOutcome.ABORTED
    }
}
