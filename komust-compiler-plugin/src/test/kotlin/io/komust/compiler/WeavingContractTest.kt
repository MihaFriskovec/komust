package io.komust.compiler

import io.komust.runtime.MutantRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Seam 3 — the compile-once weaving contract that every operator shares
 * (carried over from #28 and kept green as the catalog grew): one compilation
 * holds both branches, the green baseline is the untouched original, nested
 * same-position sites are split by ordinal, and the injected `if/else` keeps the
 * site's `startOffset`.
 */
class WeavingContractTest {

    @AfterEach fun reset() = MutantRegistry.clear()

    private val fixture = """
        package w
        fun sumThree(a: Int, b: Int, c: Int): Int = a + b + c
        fun diff(a: Int, b: Int): Int = a - b
    """.trimIndent()

    @Test fun `with no mutant active every symbol behaves as the un-woven original`() {
        val woven = FixtureCompiler.compile("W.kt", fixture)
        val plain = FixtureCompiler.compile("W.kt", fixture, withPlugin = false)
        MutantRegistry.clear()
        for (c in listOf(woven, plain)) {
            assertEquals(6, c.call("w.WKt", "sumThree", 1, 2, 3))
            assertEquals(2, c.call("w.WKt", "diff", 5, 3))
        }
    }

    @Test fun `nested same-line operators share a position and split by ordinal`() {
        val c = FixtureCompiler.compile("W.kt", fixture, disabledOperators = listOf("empty-return", "constant-boundary"))
        val onLine2 = c.mutants.filter { it.line == 2 && it.token == "ARITH_PLUS_TO_MINUS" }
        assertEquals(2, onLine2.size, "a + b + c weaves two plus sites")
        assertEquals(setOf(0, 1), onLine2.map { it.ordinal }.toSet())
        assertEquals(1, onLine2.map { it.column }.toSet().size, "the two sites share a column")
        assertEquals(2, onLine2.map { it.id }.toSet().size, "…but get distinct ids")

        MutantRegistry.activate(onLine2.single { it.ordinal == 0 }.id)
        assertEquals(2, c.call("w.WKt", "sumThree", 1, 2, 3), "(1 - 2) + 3")
        MutantRegistry.activate(onLine2.single { it.ordinal == 1 }.id)
        assertEquals(0, c.call("w.WKt", "sumThree", 1, 2, 3), "(1 + 2) - 3")
    }

    @Test fun `a deeply nested same-operator expression weaves without code-size blow-up`() {
        // 12 additions on one line: a mutant branch rebuilds from raw operands
        // (not the already-woven subtree), so woven code stays linear in depth.
        val c = FixtureCompiler.compile(
            "N.kt",
            """
            package w
            fun big(a: Int): Int =
                a + a + a + a + a + a + a + a + a + a + a + a + a
            """.trimIndent(),
            disabledOperators = listOf("empty-return"),
        )
        assertTrue(c.ok, c.messages)
        val plusMutants = c.mutants.filter { it.token == "ARITH_PLUS_TO_MINUS" }
        assertEquals(12, plusMutants.size)
        // baseline: 13 * a
        assertEquals(13, c.call("w.NKt", "big", 1))
        // Activate the outermost (last-ordinal) plus → one subtraction, rest add.
        MutantRegistry.activate(plusMutants.maxByOrNull { it.ordinal }!!.id)
        assertEquals(11, c.call("w.NKt", "big", 1), "(12*a) - a with a=1")
    }

    @Test fun `the injected if-else preserves each site's original startOffset`() {
        val sink = WovenSiteOffsets()
        val c = FixtureCompiler.compile(
            "W.kt",
            fixture,
            extraRegistrars = listOf(WovenSiteInspectorRegistrar(sink)),
        )
        assertTrue(c.ok, c.messages)
        val reported = c.mutants.associate { it.id to it.startOffset }
        assertEquals(reported.keys, sink.startOffsetById.keys, "inspector saw a different set of woven sites")
        for ((id, wovenOffset) in sink.startOffsetById) {
            assertTrue(wovenOffset >= 0, "woven if/else for $id has an undefined offset")
            assertEquals(reported[id], wovenOffset, "woven if/else for $id moved off its original site")
        }
    }
}
