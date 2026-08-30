package io.komust.engine.sweep

import io.komust.engine.coverage.TestId

/**
 * A caller-supplied pinning of the test set for the sweep — the `--tests`
 * **explicit override** (ADR-0004 §5, issue #36).
 *
 * ## Granularity
 *
 * **Global** (one set for the whole run) and/or **per-file** (one set for every
 * mutant in a given source file). Per-mutant pinning is out for v1 — mutant ids
 * are content-hashes, fine to target but brittle as an override key.
 *
 * ## Replace, never augment
 *
 * Where an override applies to a mutant it **fully replaces** the
 * coverage-derived covering set — it is not merged with it (the same
 * replace-not-merge stance as the Mutation Scope override). Per-file wins over
 * global. A mutant an override applies to skips the coverage lookup entirely and
 * can therefore never be `NO_COVERAGE` (ADR-0004 §4).
 *
 * The **coverage pass still runs** regardless — it is the mandatory green
 * baseline. This type changes only *which tests a mutant is scored against*,
 * never whether the baseline is taken; that is [MutantSweep]'s
 * [CoveragePassResult][io.komust.engine.coverage.CoveragePassResult] input,
 * always produced by the caller before the sweep.
 *
 * ## Input surface is elsewhere
 *
 * The `--tests` flag syntax / file format is the Gradle plugin's concern
 * (ADR-0004 §Consequences). This is the resolved engine-seam value the adapter
 * hands the sweep.
 *
 * An override pins **at least one** test at every configured granularity;
 * "run no tests" is not a valid pinning and is rejected at construction.
 */
public class TestSelectionOverride private constructor(
    private val global: Set<TestId>?,
    private val perFile: Map<String, Set<TestId>>,
) {

    /** True when no override of any granularity is configured (equivalent to [NONE]). */
    public val isEmpty: Boolean get() = global == null && perFile.isEmpty()

    /**
     * The pinned test set that replaces [mutant]'s covering set — the per-file
     * set when one matches its [Mutant.sourceFile], else the global set, else
     * `null` when no override applies and the sweep falls back to the coverage
     * lookup.
     *
     * Path matching mirrors the compiler plugin's Mutation Scope filter: a
     * per-file key matches when the mutant's (`/`-normalised) source file path
     * equals it or ends with `"/" + key` — an exact relative-path match that
     * degrades to a basename match. When more than one key matches, the **most
     * specific** wins (exact match over suffix match; the longest key among
     * suffix matches) so the choice is deterministic regardless of map order —
     * replace-not-merge needs one unambiguous set. A `null` [Mutant.sourceFile]
     * can only be reached by a global override.
     */
    public fun testsFor(mutant: Mutant): Set<TestId>? {
        val file = mutant.sourceFile?.replace('\\', '/')
        if (file != null && perFile.isNotEmpty()) {
            val match = perFile.entries
                .filter { (key, _) -> file == key || file.endsWith("/$key") }
                .maxByOrNull { (key, _) -> if (file == key) Int.MAX_VALUE else key.length }
            if (match != null) return match.value
        }
        return global
    }

    public companion object {
        /** No override — every mutant uses coverage-mapped selection. */
        public val NONE: TestSelectionOverride = TestSelectionOverride(null, emptyMap())

        /**
         * @param global replaces the covering set for **every** mutant not
         *   matched by a [perFile] entry; `null` leaves non-per-file mutants on
         *   coverage-mapped selection. Must be non-empty when present.
         * @param perFile source file path → the set that replaces the covering
         *   set for that file's mutants; takes precedence over [global]. Keys are
         *   repo-root-relative, `/`-separated (a bare basename also matches, as
         *   in `scope.json`); `\` is normalised. Every value set must be
         *   non-empty.
         * @throws IllegalArgumentException if any configured set is empty, any
         *   per-file key is blank, or two per-file keys normalise to the same
         *   path with different test sets.
         */
        public fun of(
            global: Set<TestId>? = null,
            perFile: Map<String, Set<TestId>> = emptyMap(),
        ): TestSelectionOverride {
            require(global == null || global.isNotEmpty()) {
                "a global --tests override must pin at least one test"
            }
            val normalizedPerFile = LinkedHashMap<String, Set<TestId>>(perFile.size)
            perFile.forEach { (path, tests) ->
                require(path.isNotBlank()) { "a per-file --tests override key must not be blank" }
                require(tests.isNotEmpty()) {
                    "the --tests override for '$path' must pin at least one test"
                }
                val normalized = path.replace('\\', '/')
                val existing = normalizedPerFile.put(normalized, tests)
                require(existing == null || existing == tests) {
                    "two per-file --tests override keys normalise to '$normalized' with different test sets"
                }
            }
            if (global == null && normalizedPerFile.isEmpty()) return NONE
            return TestSelectionOverride(global, normalizedPerFile)
        }
    }
}
