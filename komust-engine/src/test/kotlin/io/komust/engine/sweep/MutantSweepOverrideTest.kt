package io.komust.engine.sweep

import io.komust.engine.coverage.CoverageIndexBuilder
import io.komust.engine.coverage.CoveragePassResult
import io.komust.engine.coverage.TestExecution
import io.komust.engine.coverage.TestId
import io.komust.engine.coverage.TestOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The `--tests` explicit override applied by the sweep (issue #36, ADR-0004 §5):
 * where an override matches a mutant it **fully replaces** the coverage-derived
 * covering set, and such a mutant is never `NO_COVERAGE`. Verified at the engine
 * input-contract seam against fakes — no JUnit Platform, no real mutants.
 */
class MutantSweepOverrideTest {

    private val cov = TestId("[engine:junit-jupiter]/[class:CalcTest]/[method:covers()]")
    private val pinnedFast = TestId("[engine:junit-jupiter]/[class:PinTest]/[method:fast()]")
    private val pinnedSlow = TestId("[engine:junit-jupiter]/[class:PinTest]/[method:slow()]")

    private val calcAdd = Mutant("Calc.kt:4:40:ARITH#0", "fixture.Calc", 4, "src/main/kotlin/fixture/Calc.kt")
    private val calcUncovered = Mutant("Calc.kt:9:40:ARITH#0", "fixture.Calc", 9, "src/main/kotlin/fixture/Calc.kt")
    private val otherMutant = Mutant("Other.kt:4:40:ARITH#0", "fixture.Other", 4, "src/main/kotlin/fixture/Other.kt")

    /** `cov` covers Calc line 4 and Other line 4; nothing covers Calc line 9. */
    private fun coveragePass(): CoveragePassResult {
        val index = CoverageIndexBuilder().apply {
            add("fixture.Calc", 4, cov)
            add("fixture.Other", 4, cov)
        }.build()
        val tests = listOf(
            exec(cov, 20.milliseconds),
            exec(pinnedFast, 5.milliseconds),
            exec(pinnedSlow, 50.milliseconds),
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

    private object NoopSwitch : MutantSwitchHandle {
        override fun activate(mutantId: String?) {}
    }

    @Test
    fun `a global override replaces the covering set for every mutant`() {
        val runner = RecordingRunner()
        val result = MutantSweep(
            coveragePass(), runner, NoopSwitch,
            TestSelectionOverride.of(global = setOf(pinnedFast, pinnedSlow)),
        ).sweep(listOf(calcAdd, otherMutant))

        result.results.forEach { r ->
            assertEquals(setOf(pinnedFast, pinnedSlow), r.coveringTests.toSet(), "mutant ${r.mutant.id}")
            assertEquals(MutantStatus.SURVIVED, r.status)
        }
        // The coverage-derived test `cov` was never run — replace, not augment.
        assertTrue(cov !in runner.calls)
        // Pinned tests are still ordered fastest-first off the coverage-pass timing.
        assertEquals(listOf(pinnedFast, pinnedSlow, pinnedFast, pinnedSlow), runner.calls)
    }

    @Test
    fun `a per-file override replaces the covering set only for that file's mutants`() {
        val runner = RecordingRunner()
        val result = MutantSweep(
            coveragePass(), runner, NoopSwitch,
            TestSelectionOverride.of(
                perFile = mapOf("src/main/kotlin/fixture/Calc.kt" to setOf(pinnedFast)),
            ),
        ).sweep(listOf(calcAdd, otherMutant))

        val calc = result.forMutant(calcAdd.id)!!
        val other = result.forMutant(otherMutant.id)!!

        assertEquals(setOf(pinnedFast), calc.coveringTests.toSet())
        // Other.kt has no per-file entry and no global — it stays coverage-mapped.
        assertEquals(setOf(cov), other.coveringTests.toSet())
    }

    @Test
    fun `replace-not-augment -- a normally-killing coverage test is excluded by the override`() {
        // `cov` would kill calcAdd, but the override pins only non-killing tests.
        val runner = RecordingRunner(fail = setOf(cov))
        val result = MutantSweep(
            coveragePass(), runner, NoopSwitch,
            TestSelectionOverride.of(global = setOf(pinnedFast)),
        ).sweep(listOf(calcAdd))

        assertEquals(MutantStatus.SURVIVED, result.forMutant(calcAdd.id)!!.status)
        assertEquals(listOf(pinnedFast), runner.calls)
    }

    @Test
    fun `an overridden mutant on an uncovered line is never NO_COVERAGE`() {
        val runner = RecordingRunner(fail = setOf(pinnedFast))
        val result = MutantSweep(
            coveragePass(), runner, NoopSwitch,
            TestSelectionOverride.of(global = setOf(pinnedFast)),
        ).sweep(listOf(calcUncovered))

        val r = result.forMutant(calcUncovered.id)!!
        assertEquals(MutantStatus.KILLED, r.status)
        assertEquals(pinnedFast, r.killedBy)
        assertEquals(0, result.noCoverage)
    }

    @Test
    fun `without an override the sweep is unchanged -- coverage-mapped, NO_COVERAGE preserved`() {
        val runner = RecordingRunner()
        val result = MutantSweep(coveragePass(), runner, NoopSwitch)
            .sweep(listOf(calcAdd, calcUncovered))

        assertEquals(setOf(cov), result.forMutant(calcAdd.id)!!.coveringTests.toSet())
        assertEquals(MutantStatus.NO_COVERAGE, result.forMutant(calcUncovered.id)!!.status)
    }

    @Test
    fun `a pinned test id that resolves to nothing fails with an override-aware error`() {
        val ghost = TestId("[engine:junit-jupiter]/[class:PinTest]/[method:tyop()]")
        val runner = CoveringTestRunner { throw UnresolvableCoveringTestException(it) }

        val ex = assertThrows<UnknownPinnedTestException> {
            MutantSweep(
                coveragePass(), runner, NoopSwitch,
                TestSelectionOverride.of(global = setOf(ghost)),
            ).sweep(listOf(calcAdd))
        }
        assertEquals(ghost, ex.test)
    }

    @Test
    fun `an unresolvable coverage-mapped test still fails as UnresolvableCoveringTestException`() {
        val runner = CoveringTestRunner { throw UnresolvableCoveringTestException(it) }

        assertThrows<UnresolvableCoveringTestException> {
            MutantSweep(coveragePass(), runner, NoopSwitch).sweep(listOf(calcAdd))
        }
    }

    @Test
    fun `a per-file override still leaves an unmatched, uncovered mutant as NO_COVERAGE`() {
        val result = MutantSweep(
            coveragePass(), RecordingRunner(), NoopSwitch,
            TestSelectionOverride.of(perFile = mapOf("src/main/kotlin/fixture/Other.kt" to setOf(pinnedFast))),
        ).sweep(listOf(calcUncovered))

        assertEquals(MutantStatus.NO_COVERAGE, result.forMutant(calcUncovered.id)!!.status)
    }
}
