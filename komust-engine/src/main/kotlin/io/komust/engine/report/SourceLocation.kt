package io.komust.engine.report

import kotlinx.serialization.Serializable

/**
 * Where a mutant lives in source — the `location` field of every `report.json`
 * and `survivors.json` record (#5, story 27).
 *
 * [path] is repo-root-relative and `/`-separated (the same shape as
 * `scope.json`, `docs/scope-json.md`), so an agent can open the file without
 * guessing at a working directory. [startLine]/[endLine] are 1-based and
 * inclusive; a single-line site has `startLine == endLine`.
 *
 * Columns are **spike-gated on #2** — the IR pass does not yet emit reliable
 * column spans — so [startColumn]/[endColumn] are nullable and omitted from the
 * JSON when absent. Adding them later is an additive change within schema
 * major 1.
 */
@Serializable
public data class SourceLocation(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val startColumn: Int? = null,
    val endColumn: Int? = null,
) {
    init {
        require(startLine >= 1) { "startLine must be >= 1, was $startLine" }
        require(endLine >= startLine) { "endLine ($endLine) must be >= startLine ($startLine)" }
    }

    /** Base name of the source file, e.g. `Calc.kt`. */
    val fileName: String get() = path.substringAfterLast('/').substringAfterLast('\\')
}
