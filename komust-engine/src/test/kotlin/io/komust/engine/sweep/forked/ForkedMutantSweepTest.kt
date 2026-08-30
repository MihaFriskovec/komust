package io.komust.engine.sweep.forked

import io.komust.engine.coverage.CoverageIndexBuilder
import io.komust.engine.coverage.CoveragePassResult
import io.komust.engine.coverage.TestExecution
import io.komust.engine.coverage.TestId
import io.komust.engine.coverage.TestOutcome
import io.komust.engine.sweep.Mutant
import io.komust.engine.sweep.MutantStatus
import io.komust.engine.sweep.SweepConfig
import io.komust.engine.sweep.TimeoutPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds

@Timeout(20)
class ForkedMutantSweepTest {

    // Three tests, each covering a distinct Calc line; short baseline times keep budgets tiny.
    private val t1 = TestId("[engine:junit-jupiter]/[class:CalcTest]/[method:one()]")
    private val t2 = TestId("[engine:junit-jupiter]/[class:CalcTest]/[method:two()]")
    private val t3 = TestId("[engine:junit-jupiter]/[class:CalcTest]/[method:three()]")

    private fun mutant(line: Int, token: String) = Mutant("Calc.kt:$line:1:$token#0", "fixture.Calc", line)

    private val coveragePass: CoveragePassResult = run {
        val index = CoverageIndexBuilder().apply {
            add("fixture.Calc", 4, t1)
            add("fixture.Calc", 5, t2)
            add("fixture.Calc", 6, t3)
            // line 9 deliberately uncovered -> NO_COVERAGE
        }.build()
        CoveragePassResult(
            index,
            listOf(
                TestExecution(t1, "one", 1.milliseconds, TestOutcome.PASSED),
                TestExecution(t2, "two", 1.milliseconds, TestOutcome.PASSED),
                TestExecution(t3, "three", 1.milliseconds, TestOutcome.PASSED),
            ),
        )
    }

    private val config = SweepConfig(
        workerCount = 2,
        timeoutPolicy = TimeoutPolicy(baseConstant = 30.milliseconds, factor = 1.0, ceiling = 60.milliseconds),
        watchdogGrace = 80.milliseconds,
    )

    private fun sweep(launcher: FakeWorkerLauncher) = ForkedMutantSweep(
        coveragePass = coveragePass,
        launcher = launcher,
        config = config,
        clock = System::nanoTime,
        watchdogInterval = 15.milliseconds,
    )

    private fun killed(id: String, by: TestId?) = MutantOutcome(
        id, MutantOutcome.Status.KILLED, testsExecuted = 1,
        killKind = if (by == null) KillKind.MEMORY_ERROR else KillKind.TEST_FAILURE,
        killedByUniqueId = by?.uniqueId,
    )

    private fun survived(id: String) = MutantOutcome(id, MutantOutcome.Status.SURVIVED, testsExecuted = 1)

    @Test
    fun `scores every mutant across the pool, results in the input order`() {
        val m1 = mutant(4, "A"); val m2 = mutant(5, "B"); val m3 = mutant(6, "C")
        val launcher = FakeWorkerLauncher { id, _ ->
            WorkerScript.Complete(if (id == m2.id) survived(id) else killed(id, t1))
        }

        val result = sweep(launcher).sweep(listOf(m1, m2, m3))

        assertEquals(listOf(m1, m2, m3), result.results.map { it.mutant })
        assertEquals(MutantStatus.KILLED, result.forMutant(m1.id)!!.status)
        assertEquals(MutantStatus.SURVIVED, result.forMutant(m2.id)!!.status)
        assertEquals(2, result.killed)
        assertEquals(1, result.survived)
    }

    @Test
    fun `a NO_COVERAGE mutant is scored without ever reaching a worker`() {
        val covered = mutant(4, "A")
        val uncovered = mutant(9, "Z")
        val launcher = FakeWorkerLauncher { id, _ -> WorkerScript.Complete(survived(id)) }

        val result = sweep(launcher).sweep(listOf(covered, uncovered))

        assertEquals(MutantStatus.NO_COVERAGE, result.forMutant(uncovered.id)!!.status)
        assertEquals(1, result.noCoverage)
        assertFalse(launcher.submittedMutants.contains(uncovered.id))
        assertTrue(launcher.submittedMutants.contains(covered.id))
    }

    @Test
    fun `the worker count is clamped to the number of work items`() {
        val only = mutant(4, "A")
        val launcher = FakeWorkerLauncher { id, _ -> WorkerScript.Complete(survived(id)) }

        ForkedMutantSweep(coveragePass, launcher, config.copy(workerCount = 8), System::nanoTime, 15.milliseconds)
            .sweep(listOf(only))

        assertEquals(1, launcher.launchCount.get())
    }

    @Test
    fun `a silent worker is killed by the watchdog and its mutant scored TIMEOUT`() {
        val hanging = mutant(4, "A")
        val launcher = FakeWorkerLauncher { _, _ -> WorkerScript.HangAfterStart }

        val result = sweep(launcher).sweep(listOf(hanging))

        assertEquals(MutantStatus.TIMEOUT, result.forMutant(hanging.id)!!.status)
        assertEquals(1, result.timedOut)
        assertEquals(1, result.detected)
        assertTrue(launcher.killedWorkerIds.isNotEmpty(), "the watchdog force-killed the silent worker")
    }

    @Test
    fun `a worker that dies after START without a RESULT scores its mutant TIMEOUT`() {
        val m = mutant(4, "A")
        val launcher = FakeWorkerLauncher { _, _ -> WorkerScript.DieAfterStart(139) }

        val result = sweep(launcher).sweep(listOf(m))

        assertEquals(MutantStatus.TIMEOUT, result.forMutant(m.id)!!.status)
    }

    @Test
    fun `a worker that dies before START requeues its mutant for a fresh worker`() {
        val m = mutant(4, "A")
        val launcher = FakeWorkerLauncher { id, attempt ->
            if (attempt == 1) WorkerScript.DieBeforeStart(1) else WorkerScript.Complete(survived(id))
        }

        val result = sweep(launcher).sweep(listOf(m))

        assertEquals(MutantStatus.SURVIVED, result.forMutant(m.id)!!.status)
        assertEquals(listOf(m.id, m.id), launcher.submittedMutants.toList(), "the mutant was submitted twice")
    }

    @Test
    fun `a self-recycling TIMEOUT worker is respawned and the remaining queue still drains`() {
        val slow = mutant(4, "A"); val rest = mutant(5, "B")
        val launcher = FakeWorkerLauncher { id, _ ->
            if (id == slow.id) {
                WorkerScript.RecycleThenExit(MutantOutcome(id, MutantOutcome.Status.TIMEOUT, 1), code = 70)
            } else {
                WorkerScript.Complete(survived(id))
            }
        }

        val result = ForkedMutantSweep(coveragePass, launcher, config.copy(workerCount = 1), System::nanoTime, 15.milliseconds)
            .sweep(listOf(slow, rest))

        assertEquals(MutantStatus.TIMEOUT, result.forMutant(slow.id)!!.status)
        assertEquals(MutantStatus.SURVIVED, result.forMutant(rest.id)!!.status)
        assertTrue(launcher.launchCount.get() >= 2, "a replacement worker was spawned after the recycle")
    }

    @Test
    fun `a memory-error kill is scored KILLED with no killing test and the worker recovers`() {
        val oom = mutant(4, "A"); val rest = mutant(5, "B")
        val launcher = FakeWorkerLauncher { id, _ ->
            if (id == oom.id) {
                WorkerScript.RecycleThenExit(killed(id, by = null), code = 71)
            } else {
                WorkerScript.Complete(survived(id))
            }
        }

        val result = ForkedMutantSweep(coveragePass, launcher, config.copy(workerCount = 1), System::nanoTime, 15.milliseconds)
            .sweep(listOf(oom, rest))

        val r = result.forMutant(oom.id)!!
        assertEquals(MutantStatus.KILLED, r.status)
        assertNull(r.killedBy)
        assertEquals(MutantStatus.SURVIVED, result.forMutant(rest.id)!!.status)
    }

    @Test
    fun `a FATAL message aborts the whole sweep`() {
        val m = mutant(4, "A")
        val fatalLauncher = object : WorkerLauncher {
            override fun launch(id: Int, sink: (WorkerEnvelope) -> Unit): WorkerHandle {
                return object : WorkerHandle {
                    override val id = id
                    override fun submit(item: WorkItem) {
                        sink(WorkerEnvelope(id, WorkerEvent.Message(WorkerMessage.Fatal("covering test resolved to nothing"))))
                        sink(WorkerEnvelope(id, WorkerEvent.Exited(1)))
                    }
                    override fun endInput() {}
                    override fun kill() {}
                }
            }
        }

        val ex = assertThrows<ForkedSweepException> {
            ForkedMutantSweep(coveragePass, fatalLauncher, config, System::nanoTime, 15.milliseconds)
                .sweep(listOf(m))
        }
        assertTrue(ex.message!!.contains("resolved to nothing"))
    }
}
