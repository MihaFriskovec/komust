package io.komust.engine.report

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The single (de)serialisation point for `report.json` and `survivors.json`.
 *
 * Both files share one [Json] configuration so their formatting is identical:
 * pretty-printed, two-space indent, a trailing newline added by the writer.
 * `null` fields (an unkilled mutant's `killedBy`, a killed mutant's `summary`)
 * are **omitted**, not emitted as `"x": null`, keeping the token-dense file
 * dense; a reader must treat an absent optional field as `null`.
 *
 * Decoding mirrors `ScopeJson` (`docs/scope-json.md`): a parse failure becomes a
 * [MalformedReportException], and a `schemaVersion` from a **different major**
 * is rejected ([UnsupportedSchemaVersionException]) rather than guessed past —
 * while any later minor within the supported major is accepted (additive-only).
 *
 * The format contract — field rules, ordering, the status enum, versioning — is
 * specified once in `docs/report-json.md`; this object and the `@Serializable`
 * models are its only implementation.
 */
public object ReportJson {

    @OptIn(ExperimentalSerializationApi::class) // prettyPrintIndent
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
        explicitNulls = false
        // Additive-only within a major: a reader built against 1.x must tolerate
        // fields a later 1.y added, so an unknown key is skipped, not an error.
        ignoreUnknownKeys = true
    }

    public fun encodeReport(report: Report): String =
        json.encodeToString(Report.serializer(), report)

    public fun decodeReport(text: String): Report =
        decode(text, Report.serializer()) { it.schemaVersion }

    public fun encodeSurvivors(survivors: Survivors): String =
        json.encodeToString(Survivors.serializer(), survivors)

    public fun decodeSurvivors(text: String): Survivors =
        decode(text, Survivors.serializer()) { it.schemaVersion }

    private fun <T> decode(
        text: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        schemaVersionOf: (T) -> String,
    ): T {
        val decoded = try {
            json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw MalformedReportException("malformed komust report JSON: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw MalformedReportException("malformed komust report JSON: ${e.message}", e)
        }
        requireSupportedMajor(schemaVersionOf(decoded))
        return decoded
    }

    private fun requireSupportedMajor(found: String) {
        val supported = Report.SCHEMA_VERSION
        if (found.substringBefore('.') != supported.substringBefore('.')) {
            throw UnsupportedSchemaVersionException(found, supported)
        }
    }
}
