package io.komust.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Seam 3 — the ADR-0001 skip-list and the `@SuppressMutations` / `// komust:ignore`
 * hatch (#29, AC2 / AC4). A protected site produces **no** mutant.
 */
class SkipListAndHatchTest {

    private fun compile(src: String) = FixtureCompiler.compile("S.kt", src)

    @Test fun `bang-bang, TODO, require and check are protected`() {
        val c = compile(
            """
            package s
            fun c(n: Int) {
                require(n > 0)
                check(n < 100)
            }
            fun t(): Int = TODO()
            fun bang(x: String?): Int = x!!.length
            """.trimIndent(),
        )
        assertTrue(c.ok, c.messages)
        // `n > 0` / `n < 100` sit inside require/check → not mutated; `TODO()`
        // returns Nothing; the only surviving site is the `x!!.length` return
        // value (a legitimate empty-return), never the `!!` null-check itself.
        assertTrue(c.mutants.none { it.line in 2..3 }, "require/check lines wove: ${c.mutants.map { it.id }}")
        assertTrue(c.mutants.none { it.token.startsWith("REL") }, "a relational inside require/check wove")
        assertTrue(c.mutants.none { it.line == 4 }, "TODO() wove: ${c.mutants.map { it.id }}")
    }

    @Test fun `null comparisons and the checks that elvis desugars into are protected`() {
        val c = compile(
            """
            package s
            fun a(x: String?): Int = if (x == null) 0 else x.length
            fun b(x: String?): String = x ?: "default"
            fun d(x: String?): Int? = x?.length
            """.trimIndent(),
        )
        assertTrue(c.ok, c.messages)
        assertTrue(c.mutants.none { it.operator == "equality" }, "no equality mutant near null:\n${c.messages}")
    }

    @Test fun `the synthesized conditions of an exhaustive when are protected`() {
        val c = compile(
            """
            package s
            enum class Dir { N, S }
            fun step(d: Dir): Int = when (d) {
                Dir.N -> compute(1)
                Dir.S -> compute(2)
            }
            fun compute(n: Int) = n
            """.trimIndent(),
        )
        assertTrue(c.ok, c.messages)
        // The synthesized `d == Dir.N` / `d == Dir.S` checks must not weave.
        assertTrue(c.mutants.none { it.operator == "equality" }, "when branch check wove: ${c.mutants.map { it.id }}")
    }

    @Test fun `branch results of an exhaustive when are still mutated`() {
        val c = compile(
            """
            package s
            enum class Op { ADD, SUB }
            fun run(op: Op, a: Int, b: Int): Int = when (op) {
                Op.ADD -> a + b
                Op.SUB -> a - b
            }
            """.trimIndent(),
        )
        assertTrue(c.ok, c.messages)
        // The synthesized `op == Op.ADD` checks stay protected, but `a + b` /
        // `a - b` in the results are ordinary code.
        assertTrue(c.mutants.any { it.token == "ARITH_PLUS_TO_MINUS" }, "result `a + b` should weave")
        assertTrue(c.mutants.any { it.token == "ARITH_MINUS_TO_PLUS" }, "result `a - b` should weave")
        assertTrue(c.mutants.none { it.operator == "equality" }, "the when checks stay protected")
    }

    @Test fun `property accessors and synthetic data-class members are not woven`() {
        val c = compile(
            """
            package s
            data class Point(val x: Int, val y: Int)
            class Sums(private val a: Int, private val b: Int) {
                val total: Int get() = a + b
            }
            fun realSum(a: Int, b: Int): Int = a + b
            """.trimIndent(),
        )
        assertTrue(c.ok, c.messages)
        val arithmetic = c.mutants.filter { it.token.startsWith("ARITH") }
        assertEquals(1, arithmetic.size, "only realSum's `a + b`:\n${c.messages}")
        assertEquals("s.SKt", arithmetic.single().binaryClass)
    }

    @Test fun `SuppressMutations on a function suppresses every site inside it`() {
        val c = compile(
            """
            package s
            import io.komust.runtime.SuppressMutations
            fun loud(a: Int, b: Int): Int = a + b * 2
            @SuppressMutations
            fun quiet(a: Int, b: Int): Int = a + b * 2
            """.trimIndent(),
        )
        assertTrue(c.ok, c.messages)
        assertTrue(c.mutants.isNotEmpty(), "loud should weave")
        assertTrue(c.mutants.all { it.line == 3 }, "only `loud` (line 3) should weave: ${c.mutants.map { it.id }}")
    }

    @Test fun `file-level SuppressMutations suppresses the whole file`() {
        val c = compile(
            """
            @file:SuppressMutations
            package s
            import io.komust.runtime.SuppressMutations
            fun f(a: Int, b: Int): Int = a + b
            """.trimIndent(),
        )
        assertTrue(c.ok, c.messages)
        assertEquals(emptyList<String>(), c.mutants.map { it.id })
    }

    @Test fun `const val initializers and annotation defaults are not woven`() {
        val c = compile(
            """
            package s
            const val MAX: Int = 3 + 1
            annotation class Retry(val times: Int = 5)
            fun real(a: Int): Int = a + 1
            """.trimIndent(),
        )
        assertTrue(c.ok, c.messages)
        // Only `real`'s body may weave; the const val and the annotation default
        // must stay compile-time constants.
        assertTrue(c.mutants.all { it.line == 4 }, "const context wove: ${c.mutants.map { it.id }}")
        assertTrue(c.mutants.isNotEmpty())
    }

    @Test fun `komust ignore comment suppresses its line and the line below`() {
        val c = compile(
            """
            package s
            fun f(a: Int, b: Int): Int {
                val x = a + b // komust:ignore
                // komust:ignore
                val y = a - b
                val z = a * b
                return x + y + z
            }
            """.trimIndent(),
        )
        assertTrue(c.ok, c.messages)
        val lines = c.mutants.map { it.line }.toSet()
        assertTrue(3 !in lines, "line 3 (same-line marker) suppressed; got $lines")
        assertTrue(5 !in lines, "line 5 (marker on line above) suppressed; got $lines")
        assertTrue(6 in lines, "line 6 (a * b) still woven; got $lines")
    }
}
