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
 *  - [coverageKey] — the `(binary class name, source line)` pair the enclosing
 *    declaration and mutated site resolve to; the direct
 *    [io.komust.engine.coverage.CoverageIndex] lookup key (ADR-0004 §2),
 *    strictly more precise than a file-level union. `CoverageKey`'s own
 *    invariant (`line >= 1`) guards construction.
 *  - [sourceFile] — the mutant's source file, repo-root-relative and
 *    `/`-separated (the compiler plugin's `path=` field). Only the **per-file**
 *    `--tests` override ([TestSelectionOverride]) reads it; the sweep's default
 *    coverage-mapped selection never does. `null` when the producer did not
 *    record a path (hand-built fixtures) — a per-file override then cannot
 *    target the mutant, but a global one still can.
 *
 * This is the sweep's slice of the engine input contract (ADR-0005). How the
 * mutant list is produced — a mutation manifest emitted by the compiler plugin,
 * or hand-built fixtures in a test — is not the sweep's concern.
 */
public data class Mutant(
    val id: String,
    val coverageKey: CoverageKey,
    val sourceFile: String? = null,
) {
    public constructor(id: String, binaryClassName: String, line: Int, sourceFile: String? = null) :
        this(id, CoverageKey(binaryClassName, line), sourceFile)

    val binaryClassName: String get() = coverageKey.binaryClassName
    val line: Int get() = coverageKey.line
}
