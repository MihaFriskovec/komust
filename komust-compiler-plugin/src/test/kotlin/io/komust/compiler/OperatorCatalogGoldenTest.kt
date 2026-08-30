package io.komust.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Seam 3 — the **golden mutant set** each default-tier operator emits on a known
 * fixture (AC1, #29). Every line is `"<binaryClass> L<line> <token> #<ordinal>"`.
 *
 * These pin the catalog's surface: a drift here is either an intended catalog
 * change (update the golden in the same commit) or a regression.
 */
class OperatorCatalogGoldenTest {

    private fun golden(src: String, only: String): Set<String> {
        val allSlugs = listOf(
            "arithmetic", "relational", "equality", "boolean-logic", "boolean-inversion",
            "constant-boundary", "boolean-return", "nullable-return", "increment", "empty-return", "void-call",
        )
        val c = FixtureCompiler.compile("G.kt", src, disabledOperators = allSlugs - only)
        assertTrue(c.ok, c.messages)
        assertEquals(c.summaryCount, c.mutants.size, "summary count vs parsed mutants")
        return c.goldenSet()
    }

    @Test fun arithmetic() {
        val set = golden(
            """
            package g
            fun f(a: Int, b: Int): Int {
                val s = a + b
                val d = a - b
                val p = a * b
                val q = a / b
                val r = a % b
                return s + d + p + q + r
            }
            """.trimIndent(),
            only = "arithmetic",
        )
        assertEquals(
            setOf(
                "g.GKt L3 ARITH_PLUS_TO_MINUS #0",
                "g.GKt L4 ARITH_MINUS_TO_PLUS #0",
                "g.GKt L5 ARITH_TIMES_TO_DIV #0",
                "g.GKt L6 ARITH_DIV_TO_TIMES #0",
                "g.GKt L7 ARITH_REM_TO_DIV #0",
                "g.GKt L8 ARITH_PLUS_TO_MINUS #0",
                "g.GKt L8 ARITH_PLUS_TO_MINUS #1",
                "g.GKt L8 ARITH_PLUS_TO_MINUS #2",
                "g.GKt L8 ARITH_PLUS_TO_MINUS #3",
            ),
            set,
        )
    }

    @Test fun relational() {
        val set = golden(
            """
            package g
            fun f(a: Int, b: Int): Boolean {
                return a < b
            }
            """.trimIndent(),
            only = "relational",
        )
        assertEquals(
            setOf("g.GKt L3 REL_LT_TO_LE #0", "g.GKt L3 REL_LT_TO_GE #0"),
            set,
        )
    }

    @Test fun equality() {
        val set = golden(
            """
            package g
            fun f(a: Int, b: Int): Boolean {
                val x = a == b
                val y = a != b
                return x || y
            }
            """.trimIndent(),
            only = "equality",
        )
        assertEquals(
            setOf("g.GKt L3 EQ_TO_NE #0", "g.GKt L4 NE_TO_EQ #0"),
            set,
        )
    }

    @Test fun `boolean-logic`() {
        val set = golden(
            """
            package g
            fun f(a: Boolean, b: Boolean): Boolean {
                return (a && b) || (a || b)
            }
            """.trimIndent(),
            only = "boolean-logic",
        )
        assertEquals(
            setOf("g.GKt L3 AND_TO_OR #0", "g.GKt L3 OR_TO_AND #0"),
            set,
        )
    }

    @Test fun `constant-boundary`() {
        val set = golden(
            """
            package g
            fun f(): Int {
                return 5 + 100
            }
            """.trimIndent(),
            only = "constant-boundary",
        )
        // The two literals sit at distinct columns, so each ordinal restarts at 0.
        assertEquals(
            setOf("g.GKt L3 CONST_PLUS_1 #0", "g.GKt L3 CONST_MINUS_1 #0"),
            set,
        )
    }

    @Test fun `boolean-return`() {
        val set = golden(
            """
            package g
            fun f(n: Int): Boolean {
                return n > 0
            }
            """.trimIndent(),
            only = "boolean-return",
        )
        assertEquals(setOf("g.GKt L3 RET_TRUE #0", "g.GKt L3 RET_FALSE #0"), set)
    }

    @Test fun `nullable-return`() {
        val set = golden(
            """
            package g
            fun f(hit: Boolean): String? {
                return if (hit) "x" else "y"
            }
            """.trimIndent(),
            only = "nullable-return",
        )
        assertEquals(setOf("g.GKt L3 RET_NULL #0"), set)
    }
}
