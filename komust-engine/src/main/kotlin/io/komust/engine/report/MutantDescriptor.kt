package io.komust.engine.report

import io.komust.engine.coverage.CoverageKey
import io.komust.engine.sweep.Mutant

/**
 * The **compile-time half** of one mutant's facts — everything the compiler
 * plugin knows at IR-injection time, before a single test runs.
 *
 * The engine adds the **execution-derived half** (`status`, covering tests, what
 * killed it) during the sweep and joins the two by [id] to build a
 * `report.json` record (#5, story 27). A cached run regenerates *this* half from
 * each compile so line numbers and summaries are never stale (ADR-0003), while
 * the sweep contributes the cached outcome.
 *
 * This is the report layer's slice of the **engine input contract** (ADR-0005).
 * How the descriptor list is produced — a mutation manifest emitted by the
 * compiler plugin, or hand-built fixtures in a test — is not this layer's
 * concern, exactly as with the sweep's [Mutant].
 *
 * @property id the content-addressable mutant id, identical to the [Mutant.id]
 *   the sweep scored — the join key between the two halves.
 * @property location the mutated site's source span (path + line range).
 * @property operator the catalog operator's stable slug (e.g. `arithmetic`).
 * @property original the source token/construct as written (e.g. `+`).
 * @property mutated what the operator rewrote it to (e.g. `-`).
 * @property enclosingSymbol the nearest enclosing member declaration — the unit
 *   an agent writes a targeting test against (CONTEXT.md — **Enclosing symbol**).
 * @property binaryClassName the JVM (dotted) name of the enclosing class, or the
 *   file facade for a top-level declaration — the coverage-index join key's
 *   class side (ADR-0004 §2).
 */
public data class MutantDescriptor(
    val id: String,
    val location: SourceLocation,
    val operator: String,
    val original: String,
    val mutated: String,
    val enclosingSymbol: String,
    val binaryClassName: String,
) {
    /** `original → mutated`, the compact human rendering of the change. */
    val change: String get() = "$original → $mutated"

    /** The `(binary class name, source line)` coverage-index key for this site. */
    val coverageKey: CoverageKey get() = CoverageKey(binaryClassName, location.startLine)

    /** The sweep's slice of this descriptor. */
    public fun toMutant(): Mutant = Mutant(id, coverageKey)
}
