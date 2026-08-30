package io.komust.scope

import java.nio.file.Path

/**
 * How a run asks `komust-scope` to produce its **Mutation Scope**. Exactly one
 * of these reaches [ScopeResolver.resolve] per run — the caller (the Gradle
 * `mutationTest` task today, the deferred CLI later) maps its flags onto one
 * value.
 *
 * The three shapes mirror ADR-0002's producers, all normalising to the same
 * [MutationScope]:
 *
 *  - [Git] — the zero-config default: the working-tree diff against the
 *    merge-base with the default branch. `--since` swaps the ref that
 *    merge-base is taken with, without otherwise changing git resolution.
 *  - [Files] — `--files <globs>`: whole-file sugar.
 *  - [ScopeFileDocument] — `--scope <file.json>`: precise line ranges passed
 *    straight through (the agent's deterministic path).
 *
 * **Precedence is structural.** An explicit override ([Files] / [ScopeFileDocument])
 * *fully replaces* git — git is never consulted when one is present (ADR-0002).
 * Because the overrides and the git default are distinct variants of this
 * sealed type, "an override plus git" is unrepresentable: the caller resolves
 * the conflict before it gets here.
 */
sealed interface ScopeSpec {

    /**
     * Git-derived resolution (ADR-0002 zero-config default).
     *
     * @param since `--since <ref>`: resolve scope against the merge-base of
     *   `HEAD` with this ref instead of with the auto-detected default branch.
     *   `--since HEAD` collapses to "working-tree changes only" (merge-base of
     *   `HEAD` with itself is `HEAD`); `--since <other-branch>` compares against
     *   that branch's divergence point. `null` keeps default-branch detection.
     */
    data class Git(val since: String? = null) : ScopeSpec

    /**
     * `--files <globs>`: every matched production Kotlin source enters **whole**.
     *
     * Globs are matched against repo-root-relative, `/`-separated paths and
     * support `*` (within one segment), `**` (across segments) and `?`. A bare
     * pattern with no `/` also matches by basename anywhere in the tree
     * (`--files Foo.kt`). Git is not consulted — the file list comes from
     * walking the work tree. A pattern that matches nothing is an error (a
     * typo should not silently produce an empty scope).
     */
    data class Files(val globs: List<String>) : ScopeSpec {
        init {
            require(globs.isNotEmpty()) { "--files needs at least one pattern" }
        }
    }

    /**
     * `--scope <file.json>`: read the [MutationScope] straight out of a
     * `scope.json` document at [path]. The ranges are passed through unchanged
     * (only re-normalised: sorted, merged, path-sorted). Git is not consulted.
     */
    data class ScopeFileDocument(val path: Path) : ScopeSpec

    companion object {
        /** The zero-config default: git-derived, default-branch base ref. */
        val GIT_DEFAULT: ScopeSpec = Git()
    }
}
