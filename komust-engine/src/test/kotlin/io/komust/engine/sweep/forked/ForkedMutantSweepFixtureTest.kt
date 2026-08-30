package io.komust.engine.sweep.forked

import io.komust.engine.coverage.CoveragePass
import io.komust.engine.coverage.CoveragePassResult
import io.komust.engine.coverage.JacocoRuntimeAgent
import io.komust.engine.sweep.Mutant
import io.komust.engine.sweep.MutantFixtureProject
import io.komust.engine.sweep.MutantStatus
import io.komust.engine.sweep.SweepConfig
import io.komust.engine.sweep.SweepResult
import io.komust.engine.sweep.TimeoutPolicy
import io.komust.runtime.MutantRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The forked worker pool (#34) exercised end to end against a **real fixture**
 * compiled with the compiler plugin and **real forked JVMs** — covering every
 * acceptance criterion that needs a process boundary:
 *
 *  - **state isolation** (criterion 2): a singleton counter and a lazily-created
 *    temp-file handle do not leak between mutants scored by the same worker;
 *  - **non-terminating mutant** (criterion 3): scored `TIMEOUT`, the worker
 *    recycles and a replacement finishes the queue;
 *  - **memory-error mutant** (criterion 4): a `StackOverflowError` mutant scored
 *    `KILLED` (no killing test), the worker recovers;
 *  - **spawned-thread mutant** (criterion 6): a mutant on a spawned thread still
 *    fires (process-global switch);
 *  - **parallel work-stealing** across a multi-worker pool.
 *
 * The controller-side watchdog + requeue path (criterion 5) is covered against
 * the launcher seam in [ForkedMutantSweepTest] — forcing a real worker silent
 * past `budget + grace` without it self-reporting first is inherently racy.
 *
 * Needs the JaCoCo runtime agent for the coverage pass it builds on; self-skips
 * in a bare IDE run without it.
 */
@Timeout(180)
class ForkedMutantSweepFixtureTest {

    companion object {
        private lateinit var fx: MutantFixtureProject
        private lateinit var coveragePass: CoveragePassResult

        private val ISO = "Iso.kt" to """
            package fixture

            import java.io.File

            object Counter {
                var value = 0
                fun bump() { value = value + 1 }
            }

            object Ledger {
                private val file: File = File.createTempFile("komust-iso", ".txt").also { it.deleteOnExit() }
                fun append() { file.appendText("x") }
                fun size(): Int = file.readText().length
            }

            class Stateful {
                fun viaCounter(x: Int, y: Int): Int {
                    Counter.bump()
                    return x + y
                }

                fun viaLedger(x: Int, y: Int): Int {
                    Ledger.append()
                    return y + x
                }
            }
        """.trimIndent()

        private val LOOP = "Loop.kt" to """
            package fixture

            class Loop {
                fun sum(n: Int): Int {
                    var total = 0
                    var i = 0
                    while (i < n) {
                        total = total + i
                        i = i + 1
                    }
                    return total
                }
            }
        """.trimIndent()

        private val RECURSION = "Recursion.kt" to """
            package fixture

            class Recursion {
                fun depth(n: Int): Int {
                    if (n <= 0) return 0
                    return 1 + depth(n - 1)
                }
            }
        """.trimIndent()

        private val CONCURRENT = "Concurrent.kt" to """
            package fixture

            import java.util.concurrent.atomic.AtomicInteger

            class Concurrent {
                fun sumOnThread(x: Int, y: Int): Int {
                    val box = AtomicInteger(0)
                    val worker = Thread { box.set(combine(x, y)) }
                    worker.start()
                    worker.join()
                    return box.get()
                }

                private fun combine(a: Int, b: Int): Int {
                    return a + b
                }

                fun neverTested(x: Int): Int = x + 1
            }
        """.trimIndent()

        private val TESTS = "FixtureTest.kt" to """
            package fixture

            import org.junit.jupiter.api.Test
            import org.junit.jupiter.api.Assertions.assertEquals

            class FixtureTest {
                @Test fun counterStartsClean() { Stateful().viaCounter(1, 1); assertEquals(1, Counter.value) }
                @Test fun ledgerStartsClean() { Stateful().viaLedger(1, 1); assertEquals(1, Ledger.size()) }
                @Test fun loopSums() { assertEquals(10, Loop().sum(5)) }
                @Test fun recursionDepth() { assertEquals(3, Recursion().depth(3)) }
                @Test fun spawnedThreadSees() { assertEquals(5, Concurrent().sumOnThread(2, 3)) }
            }
        """.trimIndent()

        @BeforeAll
        @JvmStatic
        fun compileAndCover(@TempDir tmp: Path) {
            assumeTrue(JacocoRuntimeAgent.isAttached(), "no JaCoCo runtime agent attached to this JVM")
            fx = MutantFixtureProject.compile(
                tmp.toFile().resolve("forked"),
                mainSources = listOf(ISO, LOOP, RECURSION, CONCURRENT),
                testSources = listOf(TESTS),
            )
            val previous = Thread.currentThread().contextClassLoader
            Thread.currentThread().contextClassLoader = fx.classLoader
            try {
                coveragePass = CoveragePass(fx.coverageInput()).run()
            } finally {
                Thread.currentThread().contextClassLoader = previous
            }
        }
    }

    @AfterEach
    fun resetSwitch() = MutantRegistry.clear()

    private fun sweep(
        mutants: List<Mutant>,
        workerCount: Int = 2,
        // A real-ish budget: it must cover a *cold* worker's first covering-test
        // run (JUnit Platform bootstrap + JIT), not just the test body.
        timeoutPolicy: TimeoutPolicy = TimeoutPolicy(baseConstant = 5.seconds, factor = 2.0, ceiling = 5.seconds),
    ): SweepResult = ForkedMutantSweep.forking(
        coveragePass = coveragePass,
        workerClasspath = fx.workerClasspath,
        reloadableRoots = fx.reloadableRoots,
        config = SweepConfig(workerCount = workerCount, timeoutPolicy = timeoutPolicy, watchdogGrace = 10.seconds),
    ).sweep(mutants)

    @Test
    fun `state does not leak between mutants scored by the same worker`() {
        // One worker scores all of these in the same JVM, back to back. The
        // covering tests assert a *fresh* singleton / temp file each time, so a
        // pass proves each mutant got reloaded classes.
        val stateful = fx.mutantsOn("Iso.kt", "return x + y") + fx.mutantsOn("Iso.kt", "return y + x")
        assertTrue(stateful.size >= 2, "expected a mutant in each stateful method, got $stateful")

        val result = sweep(stateful, workerCount = 1)

        stateful.forEach { m ->
            assertEquals(
                MutantStatus.SURVIVED,
                result.forMutant(m.id)!!.status,
                "mutant $m must survive — a KILLED here means leaked state from a previous mutant",
            )
        }
    }

    @Test
    fun `a non-terminating mutant is scored TIMEOUT and the worker is recycled and respawned`() {
        val loopMutants = fx.mutants.filter { it.binaryClassName == "fixture.Loop" }
        assertTrue(loopMutants.size >= 3, "the loop body should weave several mutants")

        val result = sweep(loopMutants, workerCount = 1)

        val incrementLine = fx.mutantsOn("Loop.kt", "i = i + 1")
        assertTrue(incrementLine.isNotEmpty())
        assertTrue(
            incrementLine.any { result.forMutant(it.id)!!.status == MutantStatus.TIMEOUT },
            "a mutant that stops the loop counter advancing must time out: " +
                incrementLine.map { result.forMutant(it.id)!!.status },
        )
        // Every mutant got a verdict → the worker respawned after each self-recycle
        // and drained the rest of the queue, and the ones after the hang were still
        // scored (fail-fast KILLED for the wrong-answer mutations).
        assertEquals(loopMutants.size, result.total)
        assertTrue(result.killed >= 3, "loop mutants after the hang were still scored by a fresh worker")
    }

    @Test
    fun `a StackOverflowError mutant is scored KILLED with no killing test and the worker recovers`() {
        val recursionMutants = fx.mutants.filter { it.binaryClassName == "fixture.Recursion" }

        val result = sweep(recursionMutants, workerCount = 1)

        val recurseCall = fx.mutantsOn("Recursion.kt", "depth(n - 1)")
        assertTrue(recurseCall.isNotEmpty())
        val memoryErrorKills = recurseCall.filter {
            val r = result.forMutant(it.id)!!
            r.status == MutantStatus.KILLED && r.killedBy == null
        }
        assertTrue(
            memoryErrorKills.isNotEmpty(),
            "a mutant that makes depth() recurse forever must be a memory-error KILLED (killedBy=null): " +
                recurseCall.map { result.forMutant(it.id)!!.let { r -> r.status to r.killedBy } },
        )
        assertEquals(recursionMutants.size, result.total, "the worker recovered and scored every recursion mutant")
        assertTrue(result.killed >= 3, "the non-memory-error recursion mutants were still scored after the recovery")
    }

    @Test
    fun `a mutant on a spawned thread still fires under the process-global switch`() {
        // `combine` does the arithmetic and is *called from* the spawned thread.
        val spawned = fx.mutantsOn("Concurrent.kt", "return a + b")
        assertTrue(spawned.isNotEmpty(), "expected an arithmetic mutant in combine()")

        val result = sweep(spawned)

        spawned.forEach { m ->
            assertEquals(
                MutantStatus.KILLED,
                result.forMutant(m.id)!!.status,
                "mutant $m fired on the child thread (a thread-local switch would leave it SURVIVED)",
            )
        }
    }

    @Test
    fun `the whole fixture sweeps across a multi-worker pool with every mutant scored once`() {
        val all = fx.mutants
        val result = sweep(all, workerCount = 3)

        assertEquals(all.size, result.total)
        assertEquals(all.map { it.id }.toSet(), result.results.map { it.mutant.id }.toSet())
        assertEquals(all.size, result.detected + result.survived + result.noCoverage)
        assertTrue(result.noCoverage >= 1, "Concurrent.neverTested has no covering test")
        assertTrue(result.timedOut >= 1 && result.killed >= 1)
    }
}
