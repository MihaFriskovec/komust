package io.komust.engine.report

import kotlinx.serialization.Serializable

/**
 * `report.json` — the **lossless, canonical** record of a mutation run: every
 * mutant with its full compile-time facts and its execution outcome, plus run
 * metadata (#5, story 25).
 *
 * It is the **source of truth**: `survivors.json` ([Survivors]) and the human
 * report ([HumanReport]) are both projections of it, re-derived from the
 * serialized file so they can never disagree with the contract (story 29).
 *
 * ## Schema stability
 *
 * [schemaVersion] is semver. Within a major version every change is **additive**
 * — new optional fields, new enum values, never a removal or a type change — so
 * a tool built against `1.x` keeps working against any later `1.y`. The wire
 * shape is pinned by `schema/report.schema.json`.
 *
 * ## Determinism
 *
 * [mutants] is sorted by `(location.path, location.startLine, id)` — the same
 * order `survivors.json` uses — so two runs of the same input produce
 * byte-identical files and golden-file assertions are meaningful (story 32).
 */
@Serializable
public data class Report(
    val schemaVersion: String,
    val run: RunMetadata,
    val mutants: List<MutantEntry>,
) {
    public companion object {
        /** The only schema version this build emits. Bump additively (see class doc). */
        public const val SCHEMA_VERSION: String = "1.0.0"
    }
}

/**
 * Run-level metadata: when the run happened, which komust produced it, which
 * operators were in play, and the roll-up [counts].
 *
 * There is deliberately **no mutation-score field**: score reporting (and
 * thresholds / CI gating) is out of v1 (spec #23, Out of Scope). A consumer that
 * wants a ratio derives it from [counts]; adding a score later is additive.
 */
@Serializable
public data class RunMetadata(
    /** ISO-8601 instant the run started. */
    val startedAt: String,
    /** ISO-8601 instant the run finished. */
    val finishedAt: String,
    /** The komust version that produced this report. */
    val komustVersion: String,
    /** The operator slugs that produced at least one mutant, sorted. */
    val operators: List<String>,
    val counts: Counts,
)

/** Mutant tallies by outcome. `total == killed + survived + noCoverage + timeout`. */
@Serializable
public data class Counts(
    val total: Int,
    val killed: Int,
    val survived: Int,
    val noCoverage: Int,
    val timeout: Int,
)

/**
 * The `report.json` status enum (ADR-0003, reconciled with #5). `NO_COVERAGE` is
 * its **own category**, never folded into `SURVIVED` (ADR-0004 §4); `TIMEOUT` is
 * scored as killed but reported distinctly.
 *
 * The v1 in-process sweep (#33) produces `KILLED | SURVIVED | NO_COVERAGE`;
 * `TIMEOUT` arrives with the forked worker pool (#34) — it needs a process
 * boundary to detect. The value is in the enum and the schema now so #34 is a
 * no-schema-change addition.
 */
@Serializable
public enum class MutantStatus {
    /** A covering test failed or threw — the suite detected the change. */
    KILLED,

    /** Every covering test passed — a candidate test-quality gap. */
    SURVIVED,

    /** No test executes this mutant's line — never run, its own category. */
    NO_COVERAGE,

    /** The mutant exceeded the baseline-relative timeout — scored killed (#34). */
    TIMEOUT,
}

/**
 * One mutant in `report.json`: its compile-time facts (from [MutantDescriptor])
 * plus its execution outcome (from the sweep).
 *
 * @property coveringTests the mutant's covering test set — JUnit Platform
 *   `uniqueId`s, ordered fastest-first (the sweep's visit order). The **whole**
 *   selected set for `KILLED`/`SURVIVED` (lossless — a fail-fast kill only ran a
 *   prefix); empty for `NO_COVERAGE`.
 * @property killedBy the `uniqueId` of the first covering test that failed, for
 *   `KILLED`; `null` otherwise. Because the sweep is fail-fast this is the only
 *   test known to kill the mutant.
 * @property testsExecuted how many of [coveringTests] actually ran — equals
 *   `coveringTests.size` for `SURVIVED`, smaller for a fail-fast `KILLED`, `0`
 *   for `NO_COVERAGE`. Lossless bookkeeping (story 25): a fail-fast kill only
 *   ran a prefix of the set, and that fact is not otherwise recoverable.
 * @property summary the rendered "write a test that does X" instruction, present
 *   for `SURVIVED` and `NO_COVERAGE` (the actionable outcomes); `null` for
 *   `KILLED`/`TIMEOUT`, which need no action.
 */
@Serializable
public data class MutantEntry(
    val id: String,
    val status: MutantStatus,
    val location: SourceLocation,
    val operator: String,
    val original: String,
    val mutated: String,
    val enclosingSymbol: String,
    val coveringTests: List<String>,
    val testsExecuted: Int,
    val killedBy: String? = null,
    val summary: String? = null,
)
