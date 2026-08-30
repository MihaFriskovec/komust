package io.komust.engine.report

import io.komust.engine.sweep.Mutant
import io.komust.engine.sweep.MutantResult
import io.komust.engine.sweep.MutantStatus as SweepStatus
import io.komust.engine.sweep.SweepResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReportBuilderTest {

    private val report = ReportFixture.report()

    @Test
    fun `mutants are sorted by (path, startLine, id) across the whole report`() {
        assertEquals(
            listOf(
                "Bar.kt:10:12:REL_LT_TO_LE#0",
                "Calc.kt:4:40:ARITH_PLUS_TO_MINUS#0",
                "Calc.kt:4:40:CONST_BOUNDARY_ADD_ONE#0",
                "Calc.kt:6:40:ARITH_TIMES_TO_DIV#0",
            ),
            report.mutants.map { it.id },
        )
    }

    @Test
    fun `status enum maps sweep outcomes and keeps NO_COVERAGE its own category`() {
        val byId = report.mutants.associateBy { it.id }
        assertEquals(MutantStatus.NO_COVERAGE, byId.getValue("Bar.kt:10:12:REL_LT_TO_LE#0").status)
        assertEquals(MutantStatus.KILLED, byId.getValue("Calc.kt:4:40:ARITH_PLUS_TO_MINUS#0").status)
        assertEquals(MutantStatus.SURVIVED, byId.getValue("Calc.kt:6:40:ARITH_TIMES_TO_DIV#0").status)
    }

    @Test
    fun `counts tally every category and total is their sum`() {
        val c = report.run.counts
        assertEquals(4, c.total)
        assertEquals(1, c.killed)
        assertEquals(2, c.survived)
        assertEquals(1, c.noCoverage)
        assertEquals(0, c.timeout)
        assertEquals(c.total, c.killed + c.survived + c.noCoverage + c.timeout)
    }

    @Test
    fun `there is no mutation-score field (out of v1 scope)`() {
        // RunMetadata carries counts only; score reporting is deferred (#23).
        val fields = RunMetadata::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { it.contains("score", ignoreCase = true) }, "unexpected score field: $fields")
    }

    @Test
    fun `killed entry carries killedBy, the whole covering set, a fail-fast testsExecuted, no summary`() {
        val killed = report.mutants.single { it.status == MutantStatus.KILLED }
        assertEquals("[engine:junit-jupiter]/[class:fixture.CalcTest]/[method:addExact()]", killed.killedBy)
        assertEquals(2, killed.coveringTests.size)
        assertEquals(1, killed.testsExecuted, "fail-fast: only the first covering test ran")
        assertNull(killed.summary)
    }

    @Test
    fun `a survivor's testsExecuted equals its covering set size`() {
        val survivor = report.mutants.single { it.id == "Calc.kt:4:40:CONST_BOUNDARY_ADD_ONE#0" }
        assertEquals(survivor.coveringTests.size, survivor.testsExecuted)
        assertEquals(2, survivor.testsExecuted)
    }

    @Test
    fun `survivor summary reads as a write-a-test instruction and names the covering count`() {
        val survivor = report.mutants.single { it.id == "Calc.kt:6:40:ARITH_TIMES_TO_DIV#0" }
        assertEquals(
            "In `Calc.mul` (Calc.kt:6), changing `*` to `/` is not detected — 1 covering test still passes. " +
                "Add or strengthen a test of `Calc.mul` (Calc.kt:6) so that this change makes it fail.",
            survivor.summary,
        )
        assertNull(survivor.killedBy)
    }

    @Test
    fun `no-coverage summary asks for a test and has an empty covering set`() {
        val nc = report.mutants.single { it.status == MutantStatus.NO_COVERAGE }
        assertTrue(nc.coveringTests.isEmpty())
        assertEquals(
            "In `Bar.clamp` (Bar.kt:10), no test executes the `<` at line 10. " +
                "Write a test that exercises `Bar.clamp` (Bar.kt:10) and would fail if `<` became `<=`.",
            nc.summary,
        )
    }

    @Test
    fun `operators are the distinct producing slugs, sorted`() {
        assertEquals(listOf("arithmetic", "constant-boundary", "relational"), report.run.operators)
    }

    @Test
    fun `run metadata carries the supplied instants and version`() {
        assertEquals("2026-08-30T10:00:00Z", report.run.startedAt)
        assertEquals("2026-08-30T10:00:42Z", report.run.finishedAt)
        assertEquals("0.1.0-SNAPSHOT", report.run.komustVersion)
        assertEquals(Report.SCHEMA_VERSION, report.schemaVersion)
    }

    @Test
    fun `a descriptor with no sweep result is rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            ReportBuilder.build(ReportFixture.descriptors, SweepResult(emptyList()), ReportFixture.runInfo())
        }
        assertTrue(ex.message!!.contains("no sweep result"))
    }

    @Test
    fun `a sweep result with no descriptor is rejected`() {
        val orphan = MutantResult(
            Mutant("ghost#0", "fixture.Ghost", 1), SweepStatus.SURVIVED, emptyList(), null, 0,
        )
        val ex = assertThrows<IllegalArgumentException> {
            ReportBuilder.build(
                ReportFixture.descriptors,
                SweepResult(ReportFixture.sweep.results + orphan),
                ReportFixture.runInfo(),
            )
        }
        assertTrue(ex.message!!.contains("no descriptor"))
    }

    @Test
    fun `duplicate descriptor ids are rejected`() {
        val dup = ReportFixture.descriptors.first()
        val ex = assertThrows<IllegalArgumentException> {
            ReportBuilder.build(
                ReportFixture.descriptors + dup,
                ReportFixture.sweep,
                ReportFixture.runInfo(),
            )
        }
        assertTrue(ex.message!!.contains("duplicate mutant id"))
    }
}
