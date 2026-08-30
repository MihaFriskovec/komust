package io.komust.compiler

import io.komust.scope.LineRange
import io.komust.scope.MutationScope
import io.komust.scope.ScopeJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Seam 3 (the compiler-plugin IR transform), enclosing-symbol expansion — issue
 * #30 (ADR-0002 §3, refined by ADR-0005 §3).
 *
 * The plugin consumes the resolved `scope.json` path as a `SubpluginOption` and
 * weaves a mutation point **only** when its nearest enclosing member declaration
 * intersects a changed line range. These tests pin that contract:
 *
 *  - a changed line pulls its whole enclosing member (fn / property-init / `init`)
 *    into scope;
 *  - lambdas and local functions expand to their **host** symbol, not themselves;
 *  - declarations that intersect nothing produce no mutants;
 *  - a file absent from `scope.json` produces no mutants;
 *  - no `scope.json` option ⇒ the whole module is woven (the `--all` run);
 *  - an unreadable / malformed path degrades to an empty scope (zero mutants),
 *    never a whole-module run.
 */
class EnclosingSymbolScopeTest {

    // --- Two sibling functions -------------------------------------------

    // L1 package / L2 blank / L3-5 inScope / L6 blank / L7-9 outOfScope
    private val twoFns = """
        package io.komust.scoped

        fun inScope(a: Int, b: Int): Int {
            return a + b
        }

        fun outOfScope(a: Int, b: Int): Int {
            return a + b
        }
    """.trimIndent()

    @Test
    fun `a changed line pulls its whole enclosing function into scope`() {
        val result = compileWithScope("Scoped.kt", twoFns, "Scoped.kt" to listOf(LineRange(4, 4)))
        assertTrue(result.ok, result.messages)

        val mutants = parseMutants(result.messages)
        assertEquals(1, mutants.size, "only inScope's site should be woven:\n${result.messages}")
        assertEquals(4, mutants.single().line)
        assertEquals("io.komust.scoped.ScopedKt", mutants.single().binaryClass)
    }

    @Test
    fun `out-of-scope declarations produce no mutants`() {
        val result = compileWithScope("Scoped.kt", twoFns, "Scoped.kt" to listOf(LineRange(8, 8)))
        val mutants = parseMutants(result.messages)
        assertEquals(setOf(8), mutants.map { it.line }.toSet(), "only outOfScope should weave:\n${result.messages}")
    }

    @Test
    fun `a changed line outside every member weaves nothing`() {
        // L1 is the package line — inside no declaration span.
        val result = compileWithScope("Scoped.kt", twoFns, "Scoped.kt" to listOf(LineRange(1, 1)))
        assertTrue(result.ok, result.messages)
        assertTrue(parseMutants(result.messages).isEmpty(), result.messages)
        assertTrue(result.messages.contains("0 mutant(s)"), result.messages)
    }

    @Test
    fun `a whole-file scope entry weaves every declaration in the file`() {
        val result = FixtureCompiler.compile(
            "Scoped.kt",
            twoFns,
            scopeJson = ScopeJson.encode(MutationScope.ofWholeFiles(listOf("Scoped.kt"))),
        )
        assertEquals(setOf(4, 8), parseMutants(result.messages).map { it.line }.toSet(), result.messages)
    }

    @Test
    fun `a multi-segment repo-relative path matches the compiled file`() {
        // The shape a real scope.json always emits (docs/scope-json.md:
        // "repo-root-relative, /-separated POSIX path") vs. the absolute path
        // the compiler reports for an IrFile — matched on the path suffix.
        val matched = compileWithScope(
            "com/example/Scoped.kt",
            twoFns,
            "com/example/Scoped.kt" to listOf(LineRange(4, 4)),
        )
        assertEquals(setOf(4), parseMutants(matched.messages).map { it.line }.toSet(), matched.messages)

        // A same-basename entry under a different directory must not match.
        val wrongDir = compileWithScope(
            "com/example/Scoped.kt",
            twoFns,
            "other/pkg/Scoped.kt" to listOf(LineRange(1, 200)),
        )
        assertTrue(parseMutants(wrongDir.messages).isEmpty(), wrongDir.messages)
    }

    @Test
    fun `a file absent from scope-json produces no mutants`() {
        val result = compileWithScope("Scoped.kt", twoFns, "SomethingElse.kt" to listOf(LineRange(1, 100)))
        assertTrue(result.ok, result.messages)
        assertTrue(parseMutants(result.messages).isEmpty(), result.messages)
    }

    @Test
    fun `an empty scope weaves nothing`() {
        val result = FixtureCompiler.compile(
            "Scoped.kt",
            twoFns,
            scopeJson = ScopeJson.encode(MutationScope.EMPTY),
        )
        assertTrue(result.ok, result.messages)
        assertTrue(parseMutants(result.messages).isEmpty(), result.messages)
    }

    // --- Lambdas and local functions expand to the host symbol ----------

    // L3 `fun host {` / L4 lambda / L5 `return a + b + f(0)` / L6 `}`
    private val withLambda = """
        package io.komust.lam

        fun host(a: Int, b: Int): Int {
            val f: (Int) -> Int = { x -> x + 1 }
            return a + b + f(0)
        }
    """.trimIndent()

    @Test
    fun `a changed line in a lambda body pulls in its host member`() {
        // The changed range covers only the lambda line (L4). The host's own
        // sites on L5 must still weave — the lambda expands to `host`, not itself.
        val result = compileWithScope("Lam.kt", withLambda, "Lam.kt" to listOf(LineRange(4, 4)))
        assertTrue(result.ok, result.messages)

        val lines = parseMutants(result.messages).map { it.line }.toSet()
        assertTrue(5 in lines, "host body sites (L5) should be in scope via the lambda line:\n${result.messages}")
    }

    // L3 `fun host {` / L4 `fun helper(...) = x + 1` / L5 `return a + b + helper(0)` / L6 `}`
    private val withLocalFn = """
        package io.komust.loc

        fun host(a: Int, b: Int): Int {
            fun helper(x: Int): Int = x + 1
            return a + b + helper(0)
        }
    """.trimIndent()

    @Test
    fun `a changed line in a local function pulls in its host member`() {
        val result = compileWithScope("Loc.kt", withLocalFn, "Loc.kt" to listOf(LineRange(4, 4)))
        assertTrue(result.ok, result.messages)

        val lines = parseMutants(result.messages).map { it.line }.toSet()
        assertTrue(5 in lines, "host body sites (L5) should be in scope via the local-fn line:\n${result.messages}")
    }

    @Test
    fun `a range hitting neither the host nor its nested code weaves nothing`() {
        val result = compileWithScope("Lam.kt", withLambda, "Lam.kt" to listOf(LineRange(1, 1)))
        assertTrue(parseMutants(result.messages).isEmpty(), result.messages)
    }

    // --- Property initializers ------------------------------------------

    // L3 `val base` / L4 blank / L5 `val derived: Int = base + 5`
    private val topLevelProps = """
        package io.komust.props

        val base: Int = 10

        val derived: Int = base + 5
    """.trimIndent()

    @Test
    fun `a changed line pulls in an enclosing property initializer`() {
        val inScope = compileWithScope("Props.kt", topLevelProps, "Props.kt" to listOf(LineRange(5, 5)))
        assertEquals(setOf(5), parseMutants(inScope.messages).map { it.line }.toSet(), inScope.messages)

        val outOfScope = compileWithScope("Props.kt", topLevelProps, "Props.kt" to listOf(LineRange(3, 3)))
        assertTrue(parseMutants(outOfScope.messages).isEmpty(), outOfScope.messages)
    }

    // --- init blocks ---------------------------------------------------

    // L3 `class C` / L4 `val stored` / L6 `init {` / L7 `stored = p + 1` / L8 `}`
    private val withInit = """
        package io.komust.ini

        class C(p: Int) {
            val stored: Int

            init {
                stored = p + 1
            }
        }
    """.trimIndent()

    @Test
    fun `a changed line pulls in an enclosing init block`() {
        val inScope = compileWithScope("Ini.kt", withInit, "Ini.kt" to listOf(LineRange(7, 7)))
        assertEquals(setOf(7), parseMutants(inScope.messages).map { it.line }.toSet(), inScope.messages)
        assertEquals("io.komust.ini.C", parseMutants(inScope.messages).single().binaryClass)

        val outOfScope = compileWithScope("Ini.kt", withInit, "Ini.kt" to listOf(LineRange(4, 4)))
        assertTrue(parseMutants(outOfScope.messages).isEmpty(), outOfScope.messages)
    }

    // --- Comment-only changed lines are not filtered in v1 --------------

    @Test
    fun `a changed comment line inside a function puts the whole function in scope`() {
        val source = """
            package io.komust.cmt

            fun f(a: Int, b: Int): Int {
                // a comment that "changed"
                return a + b
            }
        """.trimIndent()
        // The changed range is the comment line (L4) only. v1 does not tokenise
        // the diff, so the enclosing function still weaves (issue #30, deferred).
        val result = compileWithScope("Cmt.kt", source, "Cmt.kt" to listOf(LineRange(4, 4)))
        assertEquals(setOf(5), parseMutants(result.messages).map { it.line }.toSet(), result.messages)
    }

    // --- No option / broken option ------------------------------------

    @Test
    fun `with no scope option the whole module is woven`() {
        val result = FixtureCompiler.compile("Scoped.kt", twoFns)
        assertEquals(setOf(4, 8), parseMutants(result.messages).map { it.line }.toSet(), result.messages)
    }

    @Test
    fun `a missing scope-json path warns and weaves nothing`() {
        val result = FixtureCompiler.compile(
            "Scoped.kt",
            twoFns,
            scopeOptionValue = "/no/such/komust-scope.json",
        )
        assertTrue(result.ok, result.messages)
        assertTrue(parseMutants(result.messages).isEmpty(), result.messages)
        assertTrue(
            result.messages.contains("could not read the scope.json"),
            "expected a warning about the unreadable scope.json:\n${result.messages}",
        )
    }

    @Test
    fun `a malformed scope-json warns and weaves nothing`() {
        val result = FixtureCompiler.compile("Scoped.kt", twoFns, scopeJson = "{ not valid json")
        assertTrue(result.ok, result.messages)
        assertTrue(parseMutants(result.messages).isEmpty(), result.messages)
        assertTrue(result.messages.contains("could not read the scope.json"), result.messages)
    }

    // --- helpers ------------------------------------------------------

    private fun compileWithScope(
        fileName: String,
        source: String,
        vararg entries: Pair<String, List<LineRange>>,
    ): FixtureCompiler.Compiled =
        FixtureCompiler.compile(
            fileName,
            source,
            scopeJson = ScopeJson.encode(MutationScope.of(entries.toMap())),
        )

    private data class ParsedMutant(val id: String, val binaryClass: String) {
        private val parts = id.split(":")
        val line: Int get() = parts[1].toInt()
    }

    private val mutantLine = Regex("""komust-mutant id=(\S+) class=(\S+) startOffset=\d+ path=\S+""")

    private fun parseMutants(messages: String): List<ParsedMutant> =
        mutantLine.findAll(messages).map { ParsedMutant(it.groupValues[1], it.groupValues[2]) }.toList()
}
