package io.komust.compiler

import io.komust.compiler.ir.PluginDiagnostics
import io.komust.scope.MutationScope
import io.komust.scope.ScopeJson
import io.komust.scope.ScopeResolutionException
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Paths

/**
 * The compiler plugin's **enclosing-symbol scope test** (#30, ADR-0002 §3).
 *
 * Wraps the resolved **Mutation Scope** read from the `scope.json` path the
 * Gradle plugin passes as a `SubpluginOption`. The plugin's only scope question
 * is *"does this declaration's source-line span intersect a changed range?"* —
 * this class answers exactly that. No Kotlin parsing happens here or on the
 * Gradle side; the input is raw `file → line ranges` (CONTEXT.md, "Mutation
 * Scope"). The **expansion** — a changed line pulling in its whole enclosing
 * member — is the caller's job: it hands this class the enclosing symbol's span,
 * not the changed line.
 *
 * ## Path matching
 *
 * `scope.json` paths are repo-root-relative, `/`-separated (docs/scope-json.md).
 * The compiler reports each `IrFile` by whatever path it was invoked with —
 * absolute in a Gradle build, harness-dependent under `kotlin-compile-testing`.
 * A scope entry matches a compiled file when the file's (`/`-normalised) path
 * equals the entry path or ends with `"/" + entryPath`: an exact match for a
 * full relative path that degrades to a basename match for a bare filename.
 *
 * ## Unconfigured vs. empty
 *
 * [unfiltered] — no `scope.json` option, the `--all` run (ADR-0005 §5) — puts
 * every site in scope. An *empty* [MutationScope] (`{"files":[]}`, a clean
 * changeset) puts nothing in scope: a zero-mutant run the agent loop must
 * tolerate. An unreadable or malformed configured path degrades to empty (and is
 * warned about), never to "mutate the whole module".
 */
internal class MutationScopeFilter private constructor(private val scope: MutationScope?) {

    /** True when no `scope.json` was configured — enclosing-symbol filtering is off entirely. */
    val unfiltered: Boolean get() = scope == null

    /**
     * Whether a declaration in [sourceFilePath] spanning source lines
     * [startLine]..[endLine] (1-based, inclusive) intersects the Mutation Scope,
     * and so should have its mutation points woven. Always `true` when
     * [unfiltered].
     */
    fun enclosesChange(sourceFilePath: String, startLine: Int, endLine: Int): Boolean {
        val scope = scope ?: return true
        val normalized = sourceFilePath.replace('\\', '/')
        return scope.files
            .asSequence()
            .filter { entry -> normalized == entry.path || normalized.endsWith("/${entry.path}") }
            .flatMap { it.ranges }
            .any { it.start <= endLine && startLine <= it.end }
    }

    internal companion object {

        /**
         * Read the Mutation Scope from [scopePath], or an [unfiltered] filter when
         * it is `null` (no `scope.json` — the `--all` run). A missing or malformed
         * file is reported through [diagnostics] and degrades to an empty scope
         * (zero mutants) — never a whole-module run.
         */
        fun from(scopePath: String?, diagnostics: PluginDiagnostics): MutationScopeFilter {
            if (scopePath == null) return MutationScopeFilter(null)
            return try {
                MutationScopeFilter(ScopeJson.read(Paths.get(scopePath)))
            } catch (e: Exception) {
                when (e) {
                    is InvalidPathException, is IOException, is ScopeResolutionException -> {
                        diagnostics.warn(unreadable(scopePath, e))
                        MutationScopeFilter(MutationScope.EMPTY)
                    }
                    else -> throw e
                }
            }
        }

        private fun unreadable(path: String, cause: Exception): String =
            "komust: could not read the scope.json at '$path' (${cause.message}) — treating the " +
                "Mutation Scope as empty (zero mutants). Check the mutationTest wiring."
    }
}
