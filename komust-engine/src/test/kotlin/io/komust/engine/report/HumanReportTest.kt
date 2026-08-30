package io.komust.engine.report

import io.komust.engine.coverage.TestId
import io.komust.engine.sweep.MutantResult
import io.komust.engine.sweep.MutantStatus as SweepStatus
import io.komust.engine.sweep.SweepResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HumanReportTest {

    // The human report is always rendered from a parsed report.json, never run state.
    private val report = ReportJson.decodeReport(ReportJson.encodeReport(ReportFixture.report()))

    @Test
    fun `full human report matches the golden file`() {
        GoldenFiles.check("report.txt", HumanReport.render(report))
    }

    @Test
    fun `compact survivors-only console mode matches the golden file`() {
        GoldenFiles.check("report-survivors-only.txt", HumanReport.renderSurvivorsOnly(report))
    }

    @Test
    fun `survivors-only mode drops the metadata banner`() {
        val compact = HumanReport.renderSurvivorsOnly(report)
        assertFalse(compact.contains("komust mutation report"))
        assertFalse(compact.contains("score:"))
        assertTrue(compact.contains("Surviving mutants"))
        assertTrue(compact.contains("No-coverage mutants"))
    }

    @Test
    fun `a clean run reports no gaps`() {
        val descriptor = ReportFixture.descriptors.first()
        val t = TestId("[engine:junit-jupiter]/[class:T]/[method:t()]")
        val sweep = SweepResult(
            listOf(MutantResult(descriptor.toMutant(), SweepStatus.KILLED, listOf(t), t, 1)),
        )
        val clean = ReportBuilder.build(listOf(descriptor), sweep, ReportFixture.runInfo())
        assertEquals(
            "No surviving or uncovered mutants — every injected fault was caught.\n",
            HumanReport.renderSurvivorsOnly(clean),
        )
    }
}
