package io.komust.engine.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReportJsonTest {

    private val report = ReportFixture.report()

    @Test
    fun `report json matches the golden file`() {
        GoldenFiles.check("report.json", ReportJson.encodeReport(report) + "\n")
    }

    @Test
    fun `survivors json matches the golden file and is derived from the re-read report`() {
        val persisted = ReportJson.decodeReport(ReportJson.encodeReport(report))
        val survivors = SurvivorsProjection.from(persisted)
        GoldenFiles.check("survivors.json", ReportJson.encodeSurvivors(survivors) + "\n")
    }

    @Test
    fun `report round-trips through encode and decode`() {
        assertEquals(report, ReportJson.decodeReport(ReportJson.encodeReport(report)))
    }

    @Test
    fun `null optionals are omitted, not emitted as null`() {
        val text = ReportJson.encodeReport(report)
        assertFalse(text.contains("null"), "no literal null in the JSON: $text")
        // the killed mutant has no summary, the survivors have no killedBy
        assertTrue(text.contains("\"killedBy\""))
        assertTrue(text.contains("\"summary\""))
    }

    @Test
    fun `survivors json keeps NO_COVERAGE as its own array, never among survivors`() {
        val survivors = SurvivorsProjection.from(report)
        assertEquals(listOf("Bar.kt:10:12:REL_LT_TO_LE#0"), survivors.noCoverage.map { it.id })
        assertTrue(survivors.survivors.none { it.id == "Bar.kt:10:12:REL_LT_TO_LE#0" })
        assertEquals(
            listOf("Calc.kt:4:40:CONST_BOUNDARY_ADD_ONE#0", "Calc.kt:6:40:ARITH_TIMES_TO_DIV#0"),
            survivors.survivors.map { it.id },
        )
    }

    @Test
    fun `a field a later 1_y added is tolerated by this reader (additive-only)`() {
        val withFutureField = ReportJson.encodeReport(report)
            .replaceFirst("\"schemaVersion\": \"1.0.0\"", "\"schemaVersion\": \"1.7.0\",\n  \"newTopLevelField\": 42")
        // does not throw, and the known fields still decode
        val decoded = ReportJson.decodeReport(withFutureField)
        assertEquals(4, decoded.mutants.size)
    }

    @Test
    fun `a different major schemaVersion is rejected, not guessed past`() {
        val breaking = ReportJson.encodeReport(report)
            .replaceFirst("\"schemaVersion\": \"1.0.0\"", "\"schemaVersion\": \"2.0.0\"")
        assertThrows<UnsupportedSchemaVersionException> { ReportJson.decodeReport(breaking) }
    }

    @Test
    fun `malformed json is wrapped in a MalformedReportException`() {
        assertThrows<MalformedReportException> { ReportJson.decodeReport("not json") }
        assertThrows<MalformedReportException> { ReportJson.decodeReport("""{"schemaVersion":"1.0.0"}""") }
    }

    @Test
    fun `both files carry the same schema version`() {
        assertEquals(Report.SCHEMA_VERSION, Survivors.SCHEMA_VERSION)
        assertEquals("1.0.0", Report.SCHEMA_VERSION)
    }
}
