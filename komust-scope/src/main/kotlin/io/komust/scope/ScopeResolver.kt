package io.komust.scope

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Knobs for git-derived scope resolution. Everything here has a zero-config
 * default (ADR-0002); the explicit-override producers (`--files` / `--scope` /
 * `--since`) land in #26 and will extend this type.
 */
data class ScopeConfig(
    /** Which paths count as production Kotlin. */
    val filter: ProductionKotlinFilter = ProductionKotlinFilter(),
    /**
     * Ordered fallbacks for the default branch when `origin/HEAD` is not set.
     * The first one that resolves to a commit wins.
     */
    val defaultBranchFallbacks: List<String> =
        listOf("origin/main", "origin/master", "main", "master"),
    /** `git` executable name or path. */
    val gitExecutable: String = "git",
)

/**
 * Resolves the canonical **Mutation Scope** for the zero-config default: the
 * working-tree diff (staged + unstaged + untracked) against the merge-base with
 * the default branch, filtered to production Kotlin sources.
 *
 * This is the sole scope producer in #25. It is build-tool-free by design so
 * the Gradle plugin (#38) and the deferred CLI drive the identical logic —
 * typically through [resolveAndWrite], which also emits `scope.json`.
 */
class ScopeResolver(private val config: ScopeConfig = ScopeConfig()) {

    /**
     * @param dirInRepo any directory inside the target git work tree; the repo
     *   root is discovered from it and every resolved path is relative to that
     *   root.
     * @throws ScopeResolutionException if git is unavailable, [dirInRepo] is not
     *   a work tree, or the default branch cannot be determined. An empty
     *   changeset is **not** an error — it returns [MutationScope.EMPTY].
     */
    fun resolveFromGit(dirInRepo: Path): MutationScope =
        // Anchor every command at the repo root so `git diff -- '*.kt'` and
        // `git ls-files` emit paths relative to it, matching the scope.json
        // contract (repo-root-relative), regardless of which subdirectory the
        // caller pointed us at.
        resolve(GitClient(repoRoot(dirInRepo), config.gitExecutable))

    private fun resolve(git: GitClient): MutationScope {
        val base = resolveBaseRef(git)
        val fragments = HashMap<String, MutableList<LineRange>>()

        // Staged + unstaged tracked changes vs the base commit. `git diff <base>`
        // compares the base to the working tree (index changes included).
        //   --diff-filter=d  drops deletions (a deleted file has nothing to mutate)
        //   --no-renames     a rename surfaces as delete + add, so a renamed-then-
        //                    edited file enters whole at its new path; rename-*follow*
        //                    (keeping only the changed lines) is #26's job. This
        //                    also makes output independent of the user's
        //                    `diff.renames` config.
        val diff = git.runOrThrow(
            "-c", "core.quotePath=false",
            "diff", "--no-color", "--unified=0", "--diff-filter=d", "--no-renames",
            base, "--", "*.kt",
        )
        for (file in UnifiedDiffParser.parse(diff)) {
            if (!config.filter.accepts(file.path)) continue
            val ranges = fragments.getOrPut(file.path) { mutableListOf() }
            if (file.isNewFile) ranges += LineRange.WHOLE_FILE else ranges += file.ranges
        }

        // Untracked files (respecting .gitignore) — brand new, so whole-file.
        val untracked = git.runOrThrow(
            "-c", "core.quotePath=false",
            "ls-files", "--others", "--exclude-standard", "-z", "--", "*.kt",
        )
        for (path in untracked.split(Char.MIN_VALUE)) {
            if (path.isEmpty()) continue
            if (!config.filter.accepts(path)) continue
            fragments.getOrPut(path) { mutableListOf() } += LineRange.WHOLE_FILE
        }

        return MutationScope.of(fragments)
    }

    /**
     * Resolve the git-derived scope for [dirInRepo] and write it to [scopeJson]
     * (default `<repo>/build/komust/scope.json`), returning the resolved scope.
     * The single call the Gradle plugin (#38) and the deferred CLI make; an
     * empty changeset writes `{ "version": 1, "files": [] }` and the caller
     * exits cleanly with zero mutants.
     */
    fun resolveAndWrite(dirInRepo: Path, scopeJson: Path? = null): MutationScope {
        val root = repoRoot(dirInRepo)
        val git = GitClient(root, config.gitExecutable)
        val scope = resolve(git)
        ScopeJson.write(scope, scopeJson ?: root.resolve("build/komust/scope.json"))
        return scope
    }

    /** Discover the work-tree root that owns [dirInRepo]. */
    private fun repoRoot(dirInRepo: Path): Path {
        val topLevel = GitClient(dirInRepo, config.gitExecutable).run("rev-parse", "--show-toplevel")
        if (!topLevel.ok) {
            throw ScopeResolutionException(
                "'$dirInRepo' is not inside a git work tree: ${topLevel.stderr.trim()}",
            )
        }
        return Path(topLevel.stdout.trim())
    }

    /**
     * The base ref for the diff: the merge-base of `HEAD` with the default
     * branch. When `HEAD` is unborn (a repo with no commits) it is git's
     * empty-tree object, so every staged/tracked line reads as "new".
     */
    private fun resolveBaseRef(git: GitClient): String {
        if (!git.run("rev-parse", "--verify", "--quiet", "HEAD").ok) {
            return EMPTY_TREE_OBJECT
        }

        val defaultBranch = defaultBranch(git)
        val mergeBase = git.run("merge-base", "HEAD", defaultBranch)
        if (!mergeBase.ok) {
            throw ScopeResolutionException(
                "HEAD and '$defaultBranch' have no common ancestor, so there is no " +
                    "merge-base to diff against; set an explicit base ref",
            )
        }
        return mergeBase.stdout.trim()
    }

    private fun defaultBranch(git: GitClient): String {
        val originHead = git.run("symbolic-ref", "--quiet", "refs/remotes/origin/HEAD")
        if (originHead.ok) {
            val ref = originHead.stdout.trim().removePrefix("refs/remotes/")
            if (ref.isNotEmpty() && git.refExists(ref)) return ref
        }
        return config.defaultBranchFallbacks.firstOrNull { git.refExists(it) }
            ?: throw ScopeResolutionException(
                "could not determine the default branch (tried origin/HEAD and " +
                    "${config.defaultBranchFallbacks}); set a base ref explicitly",
            )
    }

    companion object {
        /** `git hash-object -t tree /dev/null` — the well-known empty tree. */
        private const val EMPTY_TREE_OBJECT = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
    }
}
