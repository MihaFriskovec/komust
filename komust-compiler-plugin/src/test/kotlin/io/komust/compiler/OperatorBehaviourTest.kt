package io.komust.compiler

import io.komust.runtime.MutantRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Seam 3 (the compiler-plugin IR transform) — per-operator runtime behaviour.
 *
 * For each default-tier operator: compile a fixture, find the mutant by token,
 * flip the process-global switch, and assert the woven site's behaviour changes
 * exactly as the operator promises. This is the "flipping the switch changes
 * runtime behaviour" half of the acceptance criteria (#29); the mutant-set
 * golden lives in [OperatorCatalogGoldenTest].
 */
class OperatorBehaviourTest {

    @AfterEach fun reset() = MutantRegistry.clear()

    private fun compile(src: String, vararg disabled: String) =
        FixtureCompiler.compile("Fix.kt", src, disabledOperators = disabled.toList())

    private fun FixtureCompiler.Compiled.mutant(token: String): String {
        assertTrue(ok, messages)
        return mutants.single { it.token == token }.id
    }

    // --- arithmetic -----------------------------------------------------

    @Test fun `arithmetic additive and multiplicative swaps`() {
        val c = compile(
            """
            package t
            fun addSub(a: Int, b: Int) = a + b
            fun mulDiv(a: Int, b: Int) = a * b
            """.trimIndent(),
        )
        assertEquals(5, c.call("t.FixKt", "addSub", 2, 3))
        MutantRegistry.activate(c.mutant("ARITH_PLUS_TO_MINUS"))
        assertEquals(-1, c.call("t.FixKt", "addSub", 2, 3))

        MutantRegistry.clear()
        assertEquals(6, c.call("t.FixKt", "mulDiv", 2, 3))
        MutantRegistry.activate(c.mutant("ARITH_TIMES_TO_DIV"))
        assertEquals(0, c.call("t.FixKt", "mulDiv", 2, 3), "2 * 3 → 2 / 3")
    }

    @Test fun `remainder-to-division and times-to-division are guarded against a zero divisor`() {
        val c = compile(
            """
            package t
            fun mod(a: Int, b: Int) = a % b
            fun mul(a: Int, b: Int) = a * b
            """.trimIndent(),
        )
        assertEquals(1, c.call("t.FixKt", "mod", 7, 3))
        MutantRegistry.activate(c.mutant("ARITH_REM_TO_DIV"))
        assertEquals(2, c.call("t.FixKt", "mod", 7, 3), "7 % 3 → 7 / 3")
        assertEquals(1, c.call("t.FixKt", "mod", 7, 0), "% → / : 7/0 would throw → guard yields 1")

        MutantRegistry.clear()
        assertEquals(0, c.call("t.FixKt", "mul", 7, 0))
        MutantRegistry.activate(c.mutant("ARITH_TIMES_TO_DIV"))
        assertEquals(3, c.call("t.FixKt", "mul", 7, 2), "7 * 2 → 7 / 2")
        assertEquals(1, c.call("t.FixKt", "mul", 7, 0), "* → / : 7/0 would throw → guard yields 1")
    }

    // --- relational ---------------------------------------------------

    @Test fun `relational boundary and flip`() {
        val c = compile(
            """
            package t
            fun lt(a: Int, b: Int) = a < b
            """.trimIndent(),
        )
        assertEquals(false, c.call("t.FixKt", "lt", 3, 3))
        MutantRegistry.activate(c.mutant("REL_LT_TO_LE"))
        assertEquals(true, c.call("t.FixKt", "lt", 3, 3), "< → <=")
        MutantRegistry.activate(c.mutant("REL_LT_TO_GE"))
        assertEquals(true, c.call("t.FixKt", "lt", 3, 3), "< → >=")
        assertEquals(false, c.call("t.FixKt", "lt", 2, 3), "< → >= : 2 >= 3 is false")
    }

    @Test fun `relational on a non-primitive Comparable`() {
        val c = compile(
            """
            package t
            fun after(a: String, b: String) = a > b
            """.trimIndent(),
        )
        assertEquals(true, c.call("t.FixKt", "after", "b", "a"))
        MutantRegistry.activate(c.mutant("REL_GT_TO_LE"))
        assertEquals(false, c.call("t.FixKt", "after", "b", "a"), "> → <=")
    }

    // --- equality ---------------------------------------------------

    @Test fun `equality swap both directions`() {
        val c = compile(
            """
            package t
            fun eq(a: Int, b: Int) = a == b
            fun ne(a: Int, b: Int) = a != b
            """.trimIndent(),
        )
        MutantRegistry.activate(c.mutant("EQ_TO_NE"))
        assertEquals(false, c.call("t.FixKt", "eq", 2, 2), "== → !=")
        MutantRegistry.activate(c.mutant("NE_TO_EQ"))
        assertEquals(true, c.call("t.FixKt", "ne", 2, 2), "!= → ==")
    }

    @Test fun `null comparisons are not mutated`() {
        val c = compile(
            """
            package t
            fun isNull(a: String?) = a == null
            fun notNull(a: String?) = a != null
            """.trimIndent(),
        )
        assertTrue(c.ok, c.messages)
        assertTrue(c.mutants.none { it.operator == "equality" }, "no equality mutant on == null / != null")
    }

    // --- boolean logic & inversion ----------------------------------

    @Test fun `boolean-logic and-to-or preserves short-circuit`() {
        val c = compile(
            """
            package t
            fun bothPositive(a: Int, b: Int) = a > 0 && b > 0
            """.trimIndent(),
            "relational", "constant-boundary",
        )
        assertEquals(false, c.call("t.FixKt", "bothPositive", 1, -1))
        MutantRegistry.activate(c.mutant("AND_TO_OR"))
        assertEquals(true, c.call("t.FixKt", "bothPositive", 1, -1), "&& → ||")
        assertEquals(false, c.call("t.FixKt", "bothPositive", -1, -1))
    }

    @Test fun `boolean inversion negates an if condition`() {
        val c = compile(
            """
            package t
            fun pick(flag: Boolean): String {
                if (flag) return "yes"
                return "no"
            }
            """.trimIndent(),
            "boolean-return",
        )
        assertEquals("yes", c.call("t.FixKt", "pick", true))
        MutantRegistry.activate(c.mutant("IF_NEGATE"))
        assertEquals("no", c.call("t.FixKt", "pick", true), "if (flag) → if (!flag)")
    }

    // --- constant boundary ---------------------------------------

    @Test fun `constant boundary shifts a numeric literal by one`() {
        val c = compile(
            """
            package t
            fun offset(a: Int) = a + 10
            """.trimIndent(),
            "arithmetic",
        )
        assertEquals(20, c.call("t.FixKt", "offset", 10))
        MutantRegistry.activate(c.mutant("CONST_PLUS_1"))
        assertEquals(21, c.call("t.FixKt", "offset", 10), "10 → 11")
        MutantRegistry.activate(c.mutant("CONST_MINUS_1"))
        assertEquals(19, c.call("t.FixKt", "offset", 10), "10 → 9")
    }

    // --- return-value operators --------------------------------

    @Test fun `boolean return forces true and false`() {
        val c = compile(
            """
            package t
            fun even(n: Int): Boolean {
                return n % 2 == 0
            }
            """.trimIndent(),
            "arithmetic", "equality", "constant-boundary",
        )
        assertEquals(false, c.call("t.FixKt", "even", 3))
        MutantRegistry.activate(c.mutant("RET_TRUE"))
        assertEquals(true, c.call("t.FixKt", "even", 3))
        MutantRegistry.activate(c.mutant("RET_FALSE"))
        assertEquals(false, c.call("t.FixKt", "even", 2))
    }

    @Test fun `nullable return forces null`() {
        val c = compile(
            """
            package t
            fun lookup(hit: Boolean): String? {
                return if (hit) "found" else "missing"
            }
            """.trimIndent(),
            "boolean-inversion",
        )
        assertEquals("found", c.call("t.FixKt", "lookup", true))
        MutantRegistry.activate(c.mutant("RET_NULL"))
        assertEquals(null, c.call("t.FixKt", "lookup", true))
    }

    @Test fun `nullable return fires even when the returned value is non-null`() {
        val c = compile(
            """
            package t
            fun greet(): String? = "hi"
            """.trimIndent(),
            "empty-return",
        )
        assertEquals("hi", c.call("t.FixKt", "greet"))
        MutantRegistry.activate(c.mutant("RET_NULL"))
        assertEquals(null, c.call("t.FixKt", "greet"), "String? function returning a literal still gets RET_NULL")
    }

    @Test fun `empty return does not fire on an already-zero return`() {
        val c = compile(
            """
            package t
            fun zero(): Double = 0.0
            fun blank(): String = ""
            """.trimIndent(),
            "constant-boundary",
        )
        assertTrue(c.ok, c.messages)
        assertTrue(c.mutants.none { it.token == "RET_EMPTY" }, "no equivalent RET_EMPTY: ${c.mutants.map { it.id }}")
    }

    @Test fun `empty return yields an empty default`() {
        val c = compile(
            """
            package t
            fun name(): String {
                return "komust"
            }
            fun size(): Int {
                return 42
            }
            """.trimIndent(),
            "constant-boundary",
        )
        assertTrue(c.ok, c.messages)
        MutantRegistry.activate(c.mutants.single { it.token == "RET_EMPTY" && it.line == 3 }.id)
        assertEquals("", c.call("t.FixKt", "name"))
        MutantRegistry.activate(c.mutants.single { it.token == "RET_EMPTY" && it.line == 6 }.id)
        assertEquals(0, c.call("t.FixKt", "size"))
    }

    @Test fun `return-value operators reach an expression body`() {
        val c = compile(
            """
            package t
            fun even(n: Int): Boolean = n % 2 == 0
            """.trimIndent(),
            "arithmetic", "equality", "constant-boundary",
        )
        assertEquals(false, c.call("t.FixKt", "even", 3))
        MutantRegistry.activate(c.mutant("RET_TRUE"))
        assertEquals(true, c.call("t.FixKt", "even", 3), "expression-body return replaced with true")
    }

    @Test fun `empty return yields an empty collection`() {
        val c = compile(
            """
            package t
            fun items(): List<String> {
                return listOf("a", "b")
            }
            """.trimIndent(),
            "constant-boundary",
        )
        assertTrue(c.ok, c.messages)
        assertEquals(listOf("a", "b"), c.call("t.FixKt", "items"))
        MutantRegistry.activate(c.mutant("RET_EMPTY"))
        assertEquals(emptyList<String>(), c.call("t.FixKt", "items"))
    }

    // --- spike-gated: increments & void-call ---------------------

    @Test fun `increment operator swaps ++ and --`() {
        val c = compile(
            """
            package t
            fun next(n: Int): Int {
                var x = n
                x++
                return x
            }
            """.trimIndent(),
            "constant-boundary", "arithmetic",
        )
        assertEquals(6, c.call("t.FixKt", "next", 5))
        MutantRegistry.activate(c.mutant("INC_TO_DEC"))
        assertEquals(4, c.call("t.FixKt", "next", 5), "x++ → x--")
    }

    @Test fun `void-call removal drops a Unit-returning call`() {
        val c = compile(
            """
            package t
            class Box {
                var log = ""
                fun record(s: String) { log += s }
                fun run(): String {
                    record("a")
                    record("b")
                    return log
                }
            }
            """.trimIndent(),
            "constant-boundary", "arithmetic",
        )
        assertEquals("ab", c.callOn("t.Box", emptyList(), "run"))
        // remove the first record("a") call only
        val first = c.mutants.first { it.token == "VOID_CALL_REMOVE" }
        MutantRegistry.activate(first.id)
        assertEquals("b", c.callOn("t.Box", emptyList(), "run"), "first record() call removed")
    }
}
