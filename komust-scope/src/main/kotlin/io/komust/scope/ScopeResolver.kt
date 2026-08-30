package io.komust.scope

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.Path
import kotlin.io.path.name

/**
 * Knobs for scope resolution. Everything here has a zero-config default
 * (ADR-0002); per-run choices (`--since` / `--files` / `--scope`) are expressed
 * as a [ScopeSpec] passed to [ScopeResolver.resolve], not as config.
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
 * Resolves the canonical **Mutation Scope** (ADR-0002).
 *
 * The zero-config default is the git-derived changeset: the working-tree diff
 * (staged + unstaged + untracked) against the merge-base with the default
 * branch, filtered to production Kotlin sources. Renames follow to the new
 * path, deletions drop, genuinely new files enter whole.
 *
 * The explicit-override producers — `--files` (whole-file sugar), `--scope`
 * (precise line ranges), `--since` (a different git base ref) — are selected by
 * the [ScopeSpec] handed to [resolve] / [resolveAndWrite]. An explicit override
 * **fully replaces** git; git is never consulted when one is present.
 *
 * Build-tool-free by design so the Gradle plugin and the deferred CLI drive the
 * identical logic — typically through [resolveAndWrite], which also emits
 * `scope.json`.
 */
class ScopeResolver(private val config: ScopeConfig = ScopeConfig()) {

    /**
     * Resolve the Mutation Scope for [spec].
     *
     * @param dirInRepo any directory inside the target git work tree; the repo
     *   root is discovered from it and every resolved path is relative to that
     *   root.
     * @throws ScopeResolutionException if git is unavailable, [dirInRepo] is not
     *   a work tree, the base ref cannot be determined, a `--files` pattern
     *   matches nothing, or a `--scope` document is missing or malformed. An
     *   empty git changeset is **not** an error — it returns [MutationScope.EMPTY].
     */
    fun resolve(dirInRepo: Path, spec: ScopeSpec): MutationScope =
        resolveForSpec(repoRoot(dirInRepo), spec)

    /**
     * Resolve the Mutation Scope for [spec] and write it to [scopeJson]
     * (default `<repo>/build/komust/scope.json`), returning the resolved scope.
     * The single call the Gradle plugin and the deferred CLI make; an empty
     * changeset writes `{ "version": 1, "files": [] }` and the caller exits
     * cleanly with zero mutants.
     */
    fun resolveAndWrite(
        dirInRepo: Path,
        spec: ScopeSpec,
        scopeJson: Path? = null,
    ): MutationScope {
        val root = repoRoot(dirInRepo)
        val scope = resolveForSpec(root, spec)
        ScopeJson.write(scope, scopeJson ?: root.resolve("build/komust/scope.json"))
        return scope
    }

    private fun resolveForSpec(root: Path, spec: ScopeSpec): MutationScope =
        when (spec) {
            is ScopeSpec.Git ->
                resolveGit(GitClient(root, config.gitExecutable), spec.since)
            is ScopeSpec.Files -> resolveFiles(root, spec.globs)
            is ScopeSpec.ScopeFileDocument -> readScopeDocument(spec.path)
        }

    /** The zero-config path: git-derived scope against the default branch. */
    fun resolveFromGit(dirInRepo: Path): MutationScope =
        resolve(dirInRepo, ScopeSpec.Git())

    /**
     * The zero-config path, also emitting `scope.json`.
     * @see resolveAndWrite
     */
    fun resolveAndWrite(dirInRepo: Path, scopeJson: Path? = null): MutationScope =
        resolveAndWrite(dirInRepo, ScopeSpec.Git(), scopeJson)

    // --- git-derived --------------------------------------------------------

    private fun resolveGit(git: GitClient, since: String?): MutationScope {
        val base = resolveBaseRef(git, since)
        val fragments = HashMap<String, MutableList<LineRange>>()

        // Staged + unstaged tracked changes vs the base commit. `git diff <base>`
        // compares the base to the working tree (index changes included).
        //   --diff-filter=d  drops deletions (a deleted file has nothing to mutate)
        //   --find-renames   follow a rename to its new path: a renamed-then-
        //                    edited file surfaces once, at the new path, carrying
        //                    only its changed lines — not as a whole-file add.
        //                    Passing it explicitly also makes output independent
        //                    of the user's `diff.renames` config. A rename git
        //                    cannot pair up (heavily rewritten) still degrades
        //                    safely: delete (dropped) + add (whole file).
        val diff = git.runOrThrow(
            "-c", "core.quotePath=false",
            "diff", "--no-color", "--unified=0", "--diff-filter=d", "--find-renames",
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
     * The base ref for the diff: the merge-base of `HEAD` with the default
     * branch, or — when [since] is given (`--since <ref>`) — with [since]
     * instead. When `HEAD` is unborn (a repo with no commits) it is git's
     * empty-tree object, so every staged/tracked line reads as "new".
     */
    private fun resolveBaseRef(git: GitClient, since: String?): String {
        if (!git.run("rev-parse", "--verify", "--quiet", "HEAD").ok) {
            return EMPTY_TREE_OBJECT
        }

        val target = if (since != null) {
            if (!git.refExists(since)) {
                throw ScopeResolutionException(
                    "--since ref '$since' does not resolve to a commit",
                )
            }
            since
        } else {
            defaultBranch(git)
        }

        val mergeBase = git.run("merge-base", "HEAD", target)
        if (!mergeBase.ok) {
            val what = if (since != null) "--since ref '$since'" else "'$target'"
            throw ScopeResolutionException(
                "HEAD and $what have no common ancestor, so there is no " +
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

    // --- --files -----------------------------------------------------------

    /**
     * `--files <globs>`: every production Kotlin source under [root] that any
     * pattern matches enters whole. Git is not consulted — the candidate list
     * is a work-tree walk. A pattern matching nothing is an error.
     */
    private fun resolveFiles(root: Path, globs: List<String>): MutationScope {
        val candidates = productionKotlinFilesUnder(root)
        val fragments = HashMap<String, List<LineRange>>()
        for (pattern in globs) {
            val glob = PathGlob(pattern)
            val matched = candidates.filter { glob.matches(it) }
            if (matched.isEmpty()) {
                throw ScopeResolutionException(
                    "--files pattern '$pattern' matched no production Kotlin source under $root",
                )
            }
            matched.forEach { fragments[it] = listOf(LineRange.WHOLE_FILE) }
        }
        return MutationScope.of(fragments)
    }

    /** Repo-root-relative, `/`-separated paths of every production Kotlin file. */
    private fun productionKotlinFilesUnder(root: Path): List<String> {
        val paths = ArrayList<String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == root) return FileVisitResult.CONTINUE
                val name = dir.name
                return if (name.startsWith(".") || name in config.filter.excludedDirs) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = root.relativize(file).joinToString("/")
                if (config.filter.accepts(rel)) paths += rel
                return FileVisitResult.CONTINUE
            }
        })
        return paths
    }

    // --- --scope ----------------------------------------------------------

    private fun readScopeDocument(path: Path): MutationScope {
        if (!Files.isRegularFile(path)) {
            throw ScopeResolutionException("--scope file '$path' does not exist")
        }
        return ScopeJson.read(path)
    }

    // --- shared ----------------------------------------------------------

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

    companion object {
        /** `git hash-object -t tree /dev/null` — the well-known empty tree. */
        private const val EMPTY_TREE_OBJECT = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
    }
}
