package io.komust.engine.report

import kotlinx.serialization.Serializable

/**
 * `survivors.json` — the **token-dense projection** of [Report] carrying only
 * the actionable outcomes, sized for an agent's context window (#5, story 26).
 *
 * Two disjoint categories, never merged (ADR-0004 §4):
 *
 *  - [survivors] — mutants a test *covers* but no test *kills*: a test-quality
 *    gap. Each carries the covering-but-not-killing `uniqueId`s.
 *  - [noCoverage] — mutants on a line **no test executes**: untested-but-mutable
 *    code, a maximally actionable signal, surfaced as its own category.
 *
 * `KILLED` / `TIMEOUT` mutants are absent — there is nothing to act on. The full
 * record for those lives in `report.json`.
 *
 * Derived **from** the serialized `report.json` ([SurvivorsProjection]), so it
 * can never drift from the source of truth. Both lists are sorted by
 * `(location.path, location.startLine, id)`, identical to `report.json` (story
 * 32). Pinned by `schema/survivors.schema.json`; same additive-only-within-major
 * rule as [Report].
 */
@Serializable
public data class Survivors(
    val schemaVersion: String,
    val survivors: List<Survivor>,
    val noCoverage: List<NoCoverageMutant>,
) {
    public companion object {
        public const val SCHEMA_VERSION: String = "1.0.0"
    }
}

/**
 * One surviving mutant, phrased so an agent can act on it directly.
 *
 * @property coveringTests the tests that ran this mutant and **passed anyway** —
 *   every one of them is a test that *should* have caught the change. `uniqueId`
 *   strings, fastest-first.
 * @property summary the rendered "write a test that does X" instruction.
 */
@Serializable
public data class Survivor(
    val id: String,
    val location: SourceLocation,
    val operator: String,
    val original: String,
    val mutated: String,
    val enclosingSymbol: String,
    val coveringTests: List<String>,
    val summary: String,
)

/**
 * One mutant on a line no test executes. No [Survivor.coveringTests] — there are
 * none; that is the whole point.
 */
@Serializable
public data class NoCoverageMutant(
    val id: String,
    val location: SourceLocation,
    val operator: String,
    val original: String,
    val mutated: String,
    val enclosingSymbol: String,
    val summary: String,
)
