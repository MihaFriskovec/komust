package consumer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomustReportVerificationTest {
    private val candidateVersion = System.getProperty("komust.candidateVersion")
    private val schemaDirectory = File(System.getProperty("komust.schemaDirectory"))
    private val reportDirectory = File(System.getProperty("komust.reportDirectory"))
    private val mapper = ObjectMapper()
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    @Test
    fun `reports are schema-valid and reconciled`() {
        val report = validate("report.schema.json", reportDirectory.resolve("report.json"))
        val survivors = validate("survivors.schema.json", reportDirectory.resolve("survivors.json"))

        val counts = report.path("run").path("counts")
        assertTrue(counts.path("total").asInt() > 0, "whole-project mutation must execute at least one mutant")
        assertEquals(
            counts.path("total").asInt(),
            listOf("killed", "survived", "noCoverage", "timeout").sumOf { counts.path(it).asInt() },
            "report outcome counts must sum to total",
        )
        assertEquals(candidateVersion, report.path("run").path("komustVersion").asText())

        val actionableReportIds = report.path("mutants")
            .filter { it.path("status").asText() in setOf("SURVIVED", "NO_COVERAGE") }
            .map { it.path("id").asText() }
            .toSet()
        val projectedIds = (survivors.path("survivors").toList() + survivors.path("noCoverage").toList())
            .map { it.path("id").asText() }
            .toSet()
        assertEquals(actionableReportIds, projectedIds, "survivors.json must reconcile with report.json")
    }

    private fun validate(schemaName: String, document: File): JsonNode {
        assertTrue(document.isFile, "missing komust report ${document.absolutePath}")
        val errors = factory.getSchema(schemaDirectory.resolve(schemaName).readText())
            .validate(document.readText(), InputFormat.JSON)
        assertTrue(errors.isEmpty(), "$schemaName validation failed for ${document.name}: $errors")
        return mapper.readTree(document)
    }
}
