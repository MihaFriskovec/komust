package io.komust.engine.sweep

import io.komust.engine.coverage.CoveragePass
import io.komust.engine.coverage.CoveragePassResult
import io.komust.engine.coverage.JacocoRuntimeAgent
import io.komust.runtime.MutantRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The whole sequential sweep — coverage-mapped `(class, line)` selection,
 * fastest-first ordering, fail-fast, KILLED / SURVIVED / NO_COVERAGE scoring,
 * driven through the real JUnit Platform Launcher and the real process-global
 * runtime switch — verified end to end against a fixture compiled **with the
 * compiler plugin** (issue #33 acceptance criterion 5).
 *
 * Needs the JaCoCo runtime agent (for the coverage pass it builds on); Gradle's
 * `jacoco` plugin attaches it to the `test` task, so this self-skips in a bare
 * IDE run without it.
 */
class MutantSweepFixtureTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun requireAgent() {
            assumeTrue(JacocoRuntimeAgent.isAttached(), "no JaCoCo runtime agent attached to this JVM")
        }

        private val CALC = "Calc.kt" to """
            package fixture

            class Calc {
                fun add(a: Int, b: Int): Int = a + b
                fun sub(a: Int, b: Int): Int = a - b
                fun mul(a: Int, b: Int): Int = a * b
                fun unused(a: Int, b: Int): Int = a + b
            }
        """.trimIndent()

        private val TESTS = "CalcTest.kt" to """
            package fixture

            import org.junit.jupiter.api.Test
            import org.junit.jupiter.api.Assertions.assertEquals
            import org.junit.jupiter.api.Assertions.assertTrue

            class CalcTest {
                // add: a fast exact test + a slow loose test on the same line.
                @Test fun addFastExact() { assertEquals(5, Calc().add(2, 3)) }
                @Test fun addSlowLoose() { Thread.sleep(60); assertTrue(Calc().add(2, 3) != 999) }

                // sub: a fast loose test + a slow exact test on the same line.
                @Test fun subFastLoose() { assertTrue(Calc().sub(5, 2) != 999) }
                @Test fun subSlowExact() { Thread.sleep(60); assertEquals(3, Calc().sub(5, 2)) }

                // mul: covered, but never actually asserted -> survivors.
                @Test fun mulLoose() { assertTrue(Calc().mul(3, 4) != 999) }
            }
        """.trimIndent()
    }

    @AfterEach
    fun resetSwitch() {
        MutantRegistry.clear()
    }

    private fun runSweep(
        fixture: MutantFixtureProject,
        overrideFrom: (CoveragePassResult) -> TestSelectionOverride = { TestSelectionOverride.NONE },
    ): Pair<CoveragePassResult, SweepResult> {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = fixture.classLoader
        try {
            val pass = CoveragePass(fixture.coverageInput()).run()
            val sweep = MutantSweep(
                pass,
                JUnitPlatformCoveringTestRunner(),
                MutantSwitchHandle.processGlobal(fixture.classLoader),
                overrideFrom(pass),
            ).sweep(fixture.mutants)
            return pass to sweep
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    /** The coverage-pass [io.komust.engine.coverage.TestId] whose method name is [method]. */
    private fun CoveragePassResult.testId(method: String) =
        tests.first { it.id.uniqueId.contains("$method(") }.id

    private fun fixture(tmp: Path) = MutantFixtureProject.compile(
        tmp.toFile().resolve("sweep"),
        mainSources = listOf(CALC),
        testSources = listOf(TESTS),
    )

    @Test
    fun `a mutant on an exactly-asserted line is KILLED - covering set came from the class-line lookup`(
        @TempDir tmp: Path,
    ) {
        val fx = fixture(tmp)
        val (_, sweep) = runSweep(fx)

        val addMutants = fx.mutantsOn("Calc.kt", "a + b")
        assertTrue(addMutants.isNotEmpty(), "the plugin wove at least one mutant on the add line")

        addMutants.forEach { m ->
            val r = sweep.forMutant(m.id)!!
            assertEquals(MutantStatus.KILLED, r.status, "mutant $m should be killed")
            // Only CalcTest's add-line tests are selected — nothing from sub/mul.
            assertTrue(r.coveringTests.all { it.uniqueId.contains("addFastExact") || it.uniqueId.contains("addSlowLoose") },
                "covering set must be the add-line tests only: ${r.coveringTests}")
            assertTrue(r.killedBy!!.uniqueId.contains("addFastExact"), "the fast exact test kills it: ${r.killedBy}")
        }
    }

    @Test
    fun `fail-fast - the fast killing test ends the mutant, the slow covering test never runs`(
        @TempDir tmp: Path,
    ) {
        val fx = fixture(tmp)
        val (_, sweep) = runSweep(fx)

        val m = fx.mutantsOn("Calc.kt", "a + b").first()
        val r = sweep.forMutant(m.id)!!

        assertEquals(2, r.coveringTests.size, "both add-line tests are covering")
        assertEquals("addFastExact", shortName(r.coveringTests.first()), "fastest-first puts the fast test first")
        assertEquals(1, r.testsExecuted, "fail-fast stopped after the first (killing) test")
    }

    @Test
    fun `fastest-first - a slow exact test still kills, but only after the fast covering test ran`(
        @TempDir tmp: Path,
    ) {
        val fx = fixture(tmp)
        val (_, sweep) = runSweep(fx)

        val subMutants = fx.mutantsOn("Calc.kt", "a - b")
        assertTrue(subMutants.isNotEmpty())
        val r = sweep.forMutant(subMutants.first().id)!!

        assertEquals(MutantStatus.KILLED, r.status)
        assertEquals(listOf("subFastLoose", "subSlowExact"), r.coveringTests.map(::shortName))
        assertEquals("subSlowExact", shortName(r.killedBy!!))
        assertEquals(2, r.testsExecuted)
    }

    @Test
    fun `a covered-but-never-asserted line SURVIVES`(@TempDir tmp: Path) {
        val fx = fixture(tmp)
        val (_, sweep) = runSweep(fx)

        val mulMutants = fx.mutantsOn("Calc.kt", "a * b")
        assertTrue(mulMutants.isNotEmpty())
        mulMutants.forEach { m ->
            val r = sweep.forMutant(m.id)!!
            assertEquals(MutantStatus.SURVIVED, r.status, "mutant $m")
            assertEquals(listOf("mulLoose"), r.coveringTests.map(::shortName))
            assertNull(r.killedBy)
        }
    }

    @Test
    fun `a mutant in an uncovered method is NO_COVERAGE and is never executed`(@TempDir tmp: Path) {
        val fx = fixture(tmp)
        val (_, sweep) = runSweep(fx)

        val unusedMutants = fx.mutantsOn("Calc.kt", "unused")
        assertTrue(unusedMutants.isNotEmpty(), "the plugin wove a mutant inside unused()")
        unusedMutants.forEach { m ->
            val r = sweep.forMutant(m.id)!!
            assertEquals(MutantStatus.NO_COVERAGE, r.status, "mutant $m")
            assertEquals(emptyList<Any>(), r.coveringTests)
            assertEquals(0, r.testsExecuted)
        }
    }

    @Test
    fun `a global --tests override replaces the covering set -- a normally-killed mutant SURVIVES`(
        @TempDir tmp: Path,
    ) {
        val fx = fixture(tmp)
        // Pin the whole run to `mulLoose` only — it never asserts add's result.
        val (pass, sweep) = runSweep(fx) { TestSelectionOverride.of(global = setOf(it.testId("mulLoose"))) }

        // The green coverage pass still ran despite the override (ADR-0004 §5).
        assertTrue(pass.testCount > 0, "coverage pass executed the suite")
        assertTrue(pass.index.size > 0, "coverage index was still built")

        val add = fx.mutantsOn("Calc.kt", "a + b").first()
        val r = sweep.forMutant(add.id)!!
        assertEquals(MutantStatus.SURVIVED, r.status, "override excludes addFastExact, the only test that kills it")
        assertEquals(listOf("mulLoose"), r.coveringTests.map(::shortName))
        assertNull(r.killedBy)
    }

    @Test
    fun `an overridden mutant in an uncovered method is never NO_COVERAGE`(@TempDir tmp: Path) {
        val fx = fixture(tmp)
        val (_, sweep) = runSweep(fx) { TestSelectionOverride.of(global = setOf(it.testId("mulLoose"))) }

        val unused = fx.mutantsOn("Calc.kt", "unused").first()
        val r = sweep.forMutant(unused.id)!!
        assertTrue(r.status != MutantStatus.NO_COVERAGE, "the pinned test replaces the (empty) covering set")
        assertEquals(listOf("mulLoose"), r.coveringTests.map(::shortName))
    }

    @Test
    fun `a per-file --tests override replaces the covering set for that file's mutants`(@TempDir tmp: Path) {
        val fx = fixture(tmp)
        val (_, sweep) = runSweep(fx) {
            TestSelectionOverride.of(perFile = mapOf("Calc.kt" to setOf(it.testId("mulLoose"))))
        }

        val add = fx.mutantsOn("Calc.kt", "a + b").first()
        val r = sweep.forMutant(add.id)!!
        assertEquals(MutantStatus.SURVIVED, r.status)
        assertEquals(listOf("mulLoose"), r.coveringTests.map(::shortName))
    }

    @Test
    fun `the sweep returns to the green baseline after every mutant`(@TempDir tmp: Path) {
        val fx = fixture(tmp)
        runSweep(fx)
        assertNull(MutantRegistry.current(), "no mutant left switched on after the sweep")
    }

    private fun shortName(id: io.komust.engine.coverage.TestId): String =
        Regex("""method:([^)]+)\(""").find(id.uniqueId)?.groupValues?.get(1) ?: id.uniqueId
}
