package io.komust.engine.report

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReportWriterTest {

    @Test
    fun `writes the three artifacts under the output dir`(@TempDir tmp: Path) {
        val out = tmp.resolve("build/komust")
        val written = ReportWriter.write(out, ReportFixture.report())

        assertEquals(out.resolve("report.json"), written.reportJson)
        assertEquals(out.resolve("survivors.json"), written.survivorsJson)
        assertEquals(out.resolve("report.txt"), written.humanReport)
        assertTrue(written.reportJson.toFile().isFile)
        assertTrue(written.survivorsJson.toFile().isFile)
        assertTrue(written.humanReport!!.toFile().isFile)
    }

    @Test
    fun `humanReport = false suppresses report_txt and reports no path`(@TempDir tmp: Path) {
        val written = ReportWriter.write(tmp, ReportFixture.report(), humanReport = false)

        assertEquals(null, written.humanReport)
        assertFalse(tmp.resolve("report.txt").toFile().exists(), "report.txt must not be written")
        assertTrue(written.reportJson.toFile().isFile)
        assertTrue(written.survivorsJson.toFile().isFile)
    }

    @Test
    fun `humanReport = false removes a stale report_txt from a prior run`(@TempDir tmp: Path) {
        ReportWriter.write(tmp, ReportFixture.report(), humanReport = true)
        assertTrue(tmp.resolve("report.txt").toFile().isFile)

        ReportWriter.write(tmp, ReportFixture.report(), humanReport = false)
        assertFalse(tmp.resolve("report.txt").toFile().exists())
    }

    @Test
    fun `report and survivors json end with a trailing newline`(@TempDir tmp: Path) {
        val written = ReportWriter.write(tmp, ReportFixture.report())
        assertTrue(written.reportJson.readText().endsWith("}\n"))
        assertTrue(written.survivorsJson.readText().endsWith("}\n"))
    }

    @Test
    fun `survivors and human report are derived from the persisted report json`(@TempDir tmp: Path) {
        val written = ReportWriter.write(tmp, ReportFixture.report())

        val persisted = ReportJson.decodeReport(written.reportJson.readText())
        assertEquals(
            ReportJson.encodeSurvivors(SurvivorsProjection.from(persisted)) + "\n",
            written.survivorsJson.readText(),
        )
        assertEquals(HumanReport.render(persisted), written.humanReport!!.readText())
    }

    @Test
    fun `written report json is byte-identical to the golden`(@TempDir tmp: Path) {
        val written = ReportWriter.write(tmp, ReportFixture.report())
        GoldenFiles.check("report.json", written.reportJson.readText())
    }
}
