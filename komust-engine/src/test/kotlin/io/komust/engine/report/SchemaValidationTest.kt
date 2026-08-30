package io.komust.engine.report

import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The emitted `report.json` / `survivors.json` must validate against the in-repo
 * JSON Schema under `schema/` — the schema and the serialised shape cannot drift
 * (#35, story 31).
 */
class SchemaValidationTest {

    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    private fun schema(name: String): JsonSchema {
        val file = TestPaths.schemaDir.resolve(name)
        assertTrue(file.exists(), "missing in-repo schema $file")
        return factory.getSchema(file.readText())
    }

    private fun validate(schema: String, json: String) =
        schema(schema).validate(json, InputFormat.JSON)

    @Test
    fun `report json validates against report_schema_json`() {
        val errors = validate("report.schema.json", ReportJson.encodeReport(ReportFixture.report()))
        assertEquals(emptySet<Any>(), errors, "report.json violated its schema: $errors")
    }

    @Test
    fun `survivors json validates against survivors_schema_json`() {
        val json = ReportJson.encodeSurvivors(SurvivorsProjection.from(ReportFixture.report()))
        val errors = validate("survivors.schema.json", json)
        assertEquals(emptySet<Any>(), errors, "survivors.json violated its schema: $errors")
    }

    @Test
    fun `a TIMEOUT entry validates even though the v1 sweep never emits one`() {
        // TIMEOUT arrives with #34; the schema must already accept it (no schema change then).
        val timeoutReport = ReportFixture.report().let { r ->
            r.copy(
                mutants = r.mutants.map {
                    if (it.status == MutantStatus.KILLED) it.copy(status = MutantStatus.TIMEOUT, killedBy = null) else it
                },
            )
        }
        val errors = validate("report.schema.json", ReportJson.encodeReport(timeoutReport))
        assertEquals(emptySet<Any>(), errors, "a TIMEOUT report violated the schema: $errors")
    }

    @Test
    fun `a document from a later 1_y still validates (additive-only, no additionalProperties false)`() {
        val future = ReportJson.encodeReport(ReportFixture.report())
            .replaceFirst("\"schemaVersion\": \"1.0.0\"", "\"schemaVersion\": \"1.9.0\",\n  \"futureField\": true")
        val errors = validate("report.schema.json", future)
        assertEquals(emptySet<Any>(), errors, "a forward-compatible 1.y doc violated the 1.x schema: $errors")
    }

    @Test
    fun `schema files carry an id and the models pin the version`() {
        assertTrue(TestPaths.schemaDir.resolve("report.schema.json").readText().contains("\"\$id\""))
        assertTrue(TestPaths.schemaDir.resolve("survivors.schema.json").readText().contains("\"\$id\""))
        assertEquals("1.0.0", Report.SCHEMA_VERSION)
        assertEquals(Report.SCHEMA_VERSION, Survivors.SCHEMA_VERSION)
    }
}
