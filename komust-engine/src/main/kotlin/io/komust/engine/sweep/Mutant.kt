package io.komust.engine.sweep

import io.komust.engine.coverage.CoverageKey

/**
 * One mutant the sweep must score.
 *
 * It carries the two things the sequential sweep needs and nothing else:
 *
 *  - [id] — the runtime-switch key the compiler plugin wove into the guard
 *    `if (mutantActive("<id>")) <mutant> else <original>`. The sweep hands this
 *    verbatim to `io.komust.runtime.MutantRegistry.activate(id)` (via
 *    [MutantSwitchHandle]) to switch exactly this mutant on.
 *  - [binaryClassName] + [line] — the `(binary class name, source line)` pair
 *    the enclosing declaration and mutated site resolve to, the direct
 *    [io.komust.engine.coverage.CoverageIndex] lookup key (ADR-0004 §2),
 *    strictly more precise than a file-level union.
 *
 * This is the sweep's slice of the engine input contract (ADR-0005). How the
 * mutant list is produced — a mutation manifest emitted by the compiler plugin,
 * or hand-built fixtures in a test — is not the sweep's concern.
 */
public data class Mutant(
    val id: String,
    val binaryClassName: String,
    val line: Int,
) {
    /** The coverage-index lookup key for this mutant's site. */
    val coverageKey: CoverageKey get() = CoverageKey(binaryClassName, line)
}
