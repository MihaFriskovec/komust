package io.komust.engine.report

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Writes a run's three artifacts under `build/komust/` (#5):
 *
 *  - `report.json`   — the lossless canonical record ([Report]),
 *  - `survivors.json` — the token-dense projection ([Survivors]),
 *  - `report.txt`     — the full human report ([HumanReport.render]).
 *
 * `survivors.json` and `report.txt` are derived from the **re-read**
 * `report.json`, not from the in-memory [Report], so the three files provably
 * agree with the bytes on disk (story 29).
 *
 * Every file ends with a trailing newline, matching `scope.json`
 * (`docs/scope-json.md`).
 */
public object ReportWriter {

    /**
     * The fixed output location, relative to the project root: `build/komust/`.
     * v1 does **not** make this configurable (spec #23 — "JSON output paths…
     * fixed under `build/komust/`"); [write] still takes an explicit dir so
     * tests and the future CLI can redirect it.
     */
    public val DEFAULT_OUTPUT_DIR: Path = Path.of("build", "komust")

    public const val REPORT_JSON: String = "report.json"
    public const val SURVIVORS_JSON: String = "survivors.json"
    public const val HUMAN_REPORT: String = "report.txt"

    public fun write(outputDir: Path, report: Report): WrittenReport {
        outputDir.createDirectories()

        val reportPath = outputDir.resolve(REPORT_JSON)
        reportPath.writeText(ReportJson.encodeReport(report) + "\n")

        // Re-read and derive everything else from it: report.json is the source
        // of truth, and this proves the projections match what was persisted.
        val persisted = ReportJson.decodeReport(reportPath.readText())

        val survivors = SurvivorsProjection.from(persisted)
        val survivorsPath = outputDir.resolve(SURVIVORS_JSON)
        survivorsPath.writeText(ReportJson.encodeSurvivors(survivors) + "\n")

        val humanText = HumanReport.render(persisted)
        val humanPath = outputDir.resolve(HUMAN_REPORT)
        humanPath.writeText(humanText)

        return WrittenReport(reportPath, survivorsPath, humanPath)
    }
}

/** Paths of the three files [ReportWriter.write] produced. */
public data class WrittenReport(
    val reportJson: Path,
    val survivorsJson: Path,
    val humanReport: Path,
)
