package io.komust.scope

/**
 * Decides whether a repo-relative path is a **production Kotlin source** that a
 * mutation run should consider (ADR-0002: "production Kotlin sources only").
 *
 * `komust-scope` is deliberately build-tool-free — the deferred CLI reuses it
 * verbatim — so this is a path heuristic tuned for conventional Gradle/Maven
 * layouts, not a query against a resolved source-set model. The Gradle plugin
 * (#38) can substitute a precise filter later; this is the zero-config default.
 *
 * Excluded:
 *  - anything that is not a `.kt` file (`.kts` scripts included — they are build
 *    logic, not production code under test)
 *  - test source sets: a `src/<name>/…` segment where `<name>` is `test` or
 *    `testFixtures`, or a camelCase name ending in `Test` — `integrationTest`,
 *    `androidTest`, `functionalTest`, …
 *  - generated / build output directories: any path segment in [DEFAULT_EXCLUDED_DIRS]
 *
 * [prunesDirectory] exposes the directory half of this policy as a walk-pruning
 * predicate for the `--files` override, so the exclusion rules live in one place.
 */
class ProductionKotlinFilter(
    private val excludedDirs: Set<String> = DEFAULT_EXCLUDED_DIRS,
) {

    fun accepts(path: String): Boolean {
        if (!path.endsWith(".kt")) return false

        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.any { it in excludedDirs }) return false

        val srcIndex = segments.indexOf("src")
        if (srcIndex >= 0 && srcIndex + 1 < segments.size) {
            val sourceSet = segments[srcIndex + 1]
            if (sourceSet == "test" ||
                sourceSet == "testFixtures" ||
                sourceSet.endsWith("Test")
            ) {
                return false
            }
        }

        return true
    }

    /**
     * Whether a directory named [name] should be skipped when walking the work
     * tree for the `--files` override — the exclusion policy expressed as a
     * pruning predicate so a large `build/` tree is never descended, and so
     * "what is excluded" stays owned by this class rather than the walker.
     * Dot-directories (`.git`, `.idea`, `.gradle`, …) are always pruned.
     */
    fun prunesDirectory(name: String): Boolean =
        name.startsWith(".") || name in excludedDirs

    companion object {
        val DEFAULT_EXCLUDED_DIRS = setOf(
            "build",
            "out",
            "target",
            ".gradle",
            "generated",
        )
    }
}
