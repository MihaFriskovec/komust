package io.komust.engine.sweep

import io.komust.engine.coverage.CoverageIndexBuilder
import io.komust.engine.coverage.CoveragePassResult
import io.komust.engine.coverage.TestExecution
import io.komust.engine.coverage.TestId
import io.komust.engine.coverage.TestOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The sequential sweep's selection / ordering / fail-fast / scoring logic,
 * against a recording fake runner and switch — no JUnit Platform, no real
 * mutants (issue #33 acceptance criteria 1–4).
 */
class MutantSweepTest {

    private val fast = TestId("[engine:junit-jupiter]/[class:CalcTest]/[method:fast()]")
    private val slow = TestId("[engine:junit-jupiter]/[class:CalcTest]/[method:slow()]")
    private val untimed = TestId("[engine:junit-jupiter]/[class:CalcTest]/[method:untimed()]")
    private val unrelated = TestId("[engine:junit-jupiter]/[class:OtherTest]/[method:x()]")

    private val addMutant = Mutant("Calc.kt:4:40:ARITH_PLUS_TO_MINUS#0", "fixture.Calc", 4)
    private val untestedMutant = Mutant("Calc.kt:6:40:ARITH_TIMES_TO_DIV#0", "fixture.Calc", 6)

    /**
     * A coverage pass where `fast` + `slow` + `untimed` cover Calc line 4 and
     * nothing covers line 6. `untimed` has **no** timing record, so it must sort
     * last regardless of the other two.
     */
    private fun coveragePass(): CoveragePassResult {
        val index = CoverageIndexBuilder().apply {
            add("fixture.Calc", 4, slow)
            add("fixture.Calc", 4, fast)
            add("fixture.Calc", 4, untimed)
            add("fixture.Calc", 10, unrelated)
        }.build()
        val tests = listOf(
            exec(fast, 5.milliseconds),
            exec(slow, 50.milliseconds),
            // `untimed` deliberately absent — models a covering test the pass has
            // no recorded time for.
        )
        return CoveragePassResult(index, tests)
    }

    private fun exec(id: TestId, duration: Duration) =
        TestExecution(id, id.uniqueId.substringAfterLast(":").removeSuffix("()"), duration, TestOutcome.PASSED)

    private class RecordingRunner(private val fail: Set<TestId> = emptySet()) : CoveringTestRunner {
        val calls = mutableListOf<TestId>()
        override fun run(test: TestId): TestVerdict {
            calls += test
            return if (test in fail) TestVerdict.FAILED else TestVerdict.PASSED
        }
    }

    private class RecordingSwitch : MutantSwitchHandle {
        val activations = mutableListOf<String?>()
        override fun activate(mutantId: String?) { activations += mutantId }
    }

    @Test
    fun `exact (class, line) lookup selects the covering set - unrelated tests excluded`() {
        val runner = RecordingRunner()
        val switch = RecordingSwitch()

        val result = MutantSweep(coveragePass(), runner, switch).sweep(listOf(addMutant))
        val m = result.forMutant(addMutant.id)!!

        assertEquals(setOf(fast, slow, untimed), m.coveringTests.toSet())
        assertTrue(unrelated !in m.coveringTests)
    }

    @Test
    fun `covering tests run fastest-first, a test with no recorded timing last`() {
        val runner = RecordingRunner()
        MutantSweep(coveragePass(), runner, RecordingSwitch()).sweep(listOf(addMutant))

        assertEquals(listOf(fast, slow, untimed), runner.calls)
    }

    @Test
    fun `all covering tests pass -- SURVIVED, every covering test executed`() {
        val runner = RecordingRunner()
        val result = MutantSweep(coveragePass(), runner, RecordingSwitch()).sweep(listOf(addMutant))
        val m = result.forMutant(addMutant.id)!!

        assertEquals(MutantStatus.SURVIVED, m.status)
        assertNull(m.killedBy)
        assertEquals(3, m.testsExecuted)
        assertEquals(3, runner.calls.size)
    }

    @Test
    fun `a covering test fails -- KILLED, fail-fast stops at the first killing test`() {
        val runner = RecordingRunner(fail = setOf(slow)) // slow is 2nd in fastest-first order
        val result = MutantSweep(coveragePass(), runner, RecordingSwitch()).sweep(listOf(addMutant))
        val m = result.forMutant(addMutant.id)!!

        assertEquals(MutantStatus.KILLED, m.status)
        assertEquals(slow, m.killedBy)
        assertEquals(2, m.testsExecuted) // fast, then slow — untimed never ran
        assertEquals(listOf(fast, slow), runner.calls)
    }

    @Test
    fun `no covering test -- NO_COVERAGE, never run, switch never touched`() {
        val runner = RecordingRunner()
        val switch = RecordingSwitch()
        val result = MutantSweep(coveragePass(), runner, switch).sweep(listOf(untestedMutant))
        val m = result.forMutant(untestedMutant.id)!!

        assertEquals(MutantStatus.NO_COVERAGE, m.status)
        assertEquals(emptyList<TestId>(), m.coveringTests)
        assertEquals(0, m.testsExecuted)
        assertTrue(runner.calls.isEmpty())
        assertTrue(switch.activations.isEmpty())
    }

    @Test
    fun `the mutant is switched on before its tests and back to baseline after -- even on a kill`() {
        val switch = RecordingSwitch()
        MutantSweep(coveragePass(), RecordingRunner(fail = setOf(untimed)), switch).sweep(listOf(addMutant))

        assertEquals(listOf(addMutant.id, null), switch.activations)
    }

    @Test
    fun `sweep scores every mutant, in order, with roll-up counts`() {
        val result = MutantSweep(
            coveragePass(),
            RecordingRunner(fail = setOf(fast)),
            RecordingSwitch(),
        ).sweep(listOf(addMutant, untestedMutant))

        assertEquals(listOf(addMutant, untestedMutant), result.results.map { it.mutant })
        assertEquals(1, result.killed)
        assertEquals(0, result.survived)
        assertEquals(1, result.noCoverage)
        assertEquals(2, result.total)
    }

    @Test
    fun `NO_COVERAGE is never a survivor`() {
        val result = MutantSweep(coveragePass(), RecordingRunner(), RecordingSwitch())
            .sweep(listOf(untestedMutant))
        assertEquals(0, result.survived)
        assertSame(MutantStatus.NO_COVERAGE, result.results.single().status)
    }

    @Test
    fun `covering tests with equal timing get a stable tie-break by test id`() {
        // fastA and fastB both have the same recorded time — id order decides.
        val fastA = TestId("[engine:junit-jupiter]/[class:CalcTest]/[method:aaa()]")
        val fastB = TestId("[engine:junit-jupiter]/[class:CalcTest]/[method:bbb()]")
        val index = CoverageIndexBuilder().apply {
            add("fixture.Calc", 4, fastB) // inserted first, deliberately out of id order
            add("fixture.Calc", 4, fastA)
        }.build()
        val pass = CoveragePassResult(
            index,
            listOf(exec(fastA, 5.milliseconds), exec(fastB, 5.milliseconds)),
        )
        val runner = RecordingRunner()

        MutantSweep(pass, runner, RecordingSwitch()).sweep(listOf(addMutant))

        assertEquals(listOf(fastA, fastB), runner.calls)
    }

    @Test
    fun `a runner error (unresolvable covering test) propagates and still clears the switch`() {
        val switch = RecordingSwitch()
        val throwing = CoveringTestRunner { throw UnresolvableCoveringTestException(fast) }

        assertThrows<UnresolvableCoveringTestException> {
            MutantSweep(coveragePass(), throwing, switch).sweep(listOf(addMutant))
        }
        assertEquals(listOf(addMutant.id, null), switch.activations)
    }
}
