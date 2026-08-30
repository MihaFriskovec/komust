package io.komust.compiler

import io.komust.runtime.MutantRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Seam 3 (the compiler-plugin IR transform), first-operator slice — issue #28.
 *
 * komust's first real operator is the arithmetic additive swap. These tests pin
 * the whole weaving contract the catalog and the engine build on:
 *
 *  - a site is woven as a runtime-switched `if/else`, both branches in one compile;
 *  - each mutant is keyed `(file, line, col, operator, ordinal)` and carries its
 *    binary class name;
 *  - the injected `if/else` preserves the site's original `startOffset`;
 *  - the produced mutant set for a known fixture is asserted (golden);
 *  - flipping the process-global switch changes runtime behaviour at the site.
 */
class ArithmeticOperatorTest {

    @AfterEach
    fun clearSwitch() = MutantRegistry.clear()

    // --- Golden fixture ----------------------------------------------------

    private val goldenFixture = """
        package io.komust.golden

        fun add(a: Int, b: Int): Int = a + b

        fun sumThree(a: Int, b: Int, c: Int): Int = a + b + c

        fun diff(a: Int, b: Int): Int = a - b

        class Accumulator(private var total: Int) {
            fun accumulate(delta: Int): Int {
                total = total + delta
                return total
            }

            class Nested {
                fun bump(x: Long): Long = x + 1L
            }
        }
    """.trimIndent()

    @Test
    fun `the produced mutant set for the golden fixture matches`() {
        val result = FixtureCompiler.compile("Golden.kt", goldenFixture)
        assertTrue(result.ok, result.messages)

        val mutants = parseMutants(result.messages)

        // (binary class name, line, operator token, ordinal) — column is
        // spike-gated for this ticket, so it is not asserted.
        val actual = mutants.map { "${it.binaryClass} L${it.line} ${it.token} #${it.ordinal}" }.toSet()
        val expected = setOf(
            "io.komust.golden.GoldenKt L3 ARITH_PLUS_TO_MINUS #0",   // add:      a + b
            "io.komust.golden.GoldenKt L5 ARITH_PLUS_TO_MINUS #0",   // sumThree: (a + b)
            "io.komust.golden.GoldenKt L5 ARITH_PLUS_TO_MINUS #1",   // sumThree: … + c
            "io.komust.golden.GoldenKt L7 ARITH_MINUS_TO_PLUS #0",   // diff:     a - b
            "io.komust.golden.Accumulator L11 ARITH_PLUS_TO_MINUS #0", // accumulate: total + delta
            "io.komust.golden.Accumulator${'$'}Nested L16 ARITH_PLUS_TO_MINUS #0", // Nested.bump: x + 1L
        )
        assertEquals(expected, actual, "golden mutant set drifted:\n${result.messages}")

        assertTrue(
            result.messages.contains("arithmetic operator woven over module") &&
                result.messages.contains("6 mutant(s)"),
            "summary line missing or wrong count:\n${result.messages}",
        )
    }

    @Test
    fun `nested same-line operators share a position and are split by ordinal`() {
        val result = FixtureCompiler.compile("Golden.kt", goldenFixture)
        val lineFive = parseMutants(result.messages).filter { it.line == 5 }

        assertEquals(2, lineFive.size, "a + b + c should weave two sites")
        assertEquals(setOf(0, 1), lineFive.map { it.ordinal }.toSet())
        assertEquals(1, lineFive.map { it.column }.toSet().size, "the two sites share one column")
        assertEquals(2, lineFive.map { it.id }.toSet().size, "…but get distinct ids")
    }

    // --- Runtime switch --------------------------------------------------

    private val flipFixture = """
        package io.komust.flip

        fun add(a: Int, b: Int): Int = a + b

        fun sub(a: Int, b: Int): Int = a - b
    """.trimIndent()

    @Test
    fun `flipping the process-global switch changes behaviour at the woven site`() {
        val result = FixtureCompiler.compile("Flip.kt", flipFixture)
        assertTrue(result.ok, result.messages)
        val mutants = parseMutants(result.messages)

        val plusMutant = mutants.single { it.token == "ARITH_PLUS_TO_MINUS" }.id
        val minusMutant = mutants.single { it.token == "ARITH_MINUS_TO_PLUS" }.id

        // Green baseline: original operators.
        assertEquals(5, result.call("io.komust.flip.FlipKt", "add", 2, 3))
        assertEquals(-1, result.call("io.komust.flip.FlipKt", "sub", 2, 3))

        MutantRegistry.activate(plusMutant)
        assertEquals(-1, result.call("io.komust.flip.FlipKt", "add", 2, 3), "a + b became a - b")
        assertEquals(-1, result.call("io.komust.flip.FlipKt", "sub", 2, 3), "the other site is untouched")

        MutantRegistry.activate(minusMutant)
        assertEquals(5, result.call("io.komust.flip.FlipKt", "add", 2, 3), "add is back to original")
        assertEquals(5, result.call("io.komust.flip.FlipKt", "sub", 2, 3), "a - b became a + b")

        MutantRegistry.clear()
        assertEquals(5, result.call("io.komust.flip.FlipKt", "add", 2, 3))
        assertEquals(-1, result.call("io.komust.flip.FlipKt", "sub", 2, 3))
    }

    @Test
    fun `with no mutant active every symbol behaves as the un-woven original`() {
        val withPlugin = FixtureCompiler.compile("Golden.kt", goldenFixture, withPlugin = true)
        val withoutPlugin = FixtureCompiler.compile("Golden.kt", goldenFixture, withPlugin = false)

        MutantRegistry.clear()
        for (c in listOf(withPlugin, withoutPlugin)) {
            assertEquals(5, c.call("io.komust.golden.GoldenKt", "add", 2, 3))
            assertEquals(6, c.call("io.komust.golden.GoldenKt", "sumThree", 1, 2, 3))
            assertEquals(2, c.call("io.komust.golden.GoldenKt", "diff", 5, 3))
            assertEquals(7L, c.callOn("io.komust.golden.Accumulator${'$'}Nested", emptyList(), "bump", 6L))
            assertEquals(9, c.callOn("io.komust.golden.Accumulator", listOf(4), "accumulate", 5))
        }
    }

    @Test
    fun `each ordinal of a same-line site is independently switchable`() {
        val result = FixtureCompiler.compile("Golden.kt", goldenFixture)
        val lineFive = parseMutants(result.messages).filter { it.line == 5 }.sortedBy { it.ordinal }

        // ordinal 0 is the inner (a + b); ordinal 1 the outer (… + c).
        MutantRegistry.activate(lineFive[0].id)
        assertEquals(2, result.call("io.komust.golden.GoldenKt", "sumThree", 1, 2, 3), "(1 - 2) + 3")

        MutantRegistry.activate(lineFive[1].id)
        assertEquals(0, result.call("io.komust.golden.GoldenKt", "sumThree", 1, 2, 3), "(1 + 2) - 3")
    }

    // --- startOffset preservation (AC3) --------------------------------

    @Test
    fun `the injected if-else preserves the site's original startOffset`() {
        val sink = WovenSiteOffsets()
        val result = FixtureCompiler.compile(
            "Golden.kt",
            goldenFixture,
            extraRegistrars = listOf(WovenSiteInspectorRegistrar(sink)),
        )
        assertTrue(result.ok, result.messages)

        val reported = parseMutants(result.messages).associate { it.id to it.startOffset }
        assertEquals(6, reported.size)
        assertEquals(reported.keys, sink.startOffsetById.keys, "inspector saw a different set of woven sites")

        for ((id, wovenOffset) in sink.startOffsetById) {
            assertTrue(wovenOffset >= 0, "woven if/else for $id has an undefined offset")
            assertEquals(reported[id], wovenOffset, "woven if/else for $id moved off the original site")
        }
    }

    // --- Skip-list ----------------------------------------------------

    @Test
    fun `synthetic members and property accessors are not woven`() {
        val result = FixtureCompiler.compile(
            "Skips.kt",
            """
            package io.komust.skips

            data class Point(val x: Int, val y: Int)

            class Sums(private val a: Int, private val b: Int) {
                val total: Int get() = a + b
            }

            fun realSum(a: Int, b: Int): Int = a + b
            """.trimIndent(),
        )
        assertTrue(result.ok, result.messages)

        val mutants = parseMutants(result.messages)
        // `data class Point` has a synthetic `hashCode` whose body is `x * 31 + y`
        // (a real Int.plus site); `Sums.total`'s getter is `a + b`. Neither is
        // woven — only the user-written top-level function is.
        assertEquals(1, mutants.size, "only realSum should be woven:\n${result.messages}")
        assertEquals("io.komust.skips.SkipsKt", mutants.single().binaryClass)
        assertEquals(9, mutants.single().line)
    }

    // --- Nothing to weave ----------------------------------------------

    @Test
    fun `a source with no arithmetic operators weaves nothing`() {
        val result = FixtureCompiler.compile(
            "Plain.kt",
            """
            package io.komust.plain
            val greeting: String = "hi"
            fun shout(): String = greeting.uppercase()
            """.trimIndent(),
        )
        assertTrue(result.ok, result.messages)
        assertTrue(result.messages.contains("0 mutant(s)"), result.messages)
        assertTrue(parseMutants(result.messages).isEmpty())
    }

    // --- helpers ------------------------------------------------------

    private data class ParsedMutant(
        val id: String,
        val binaryClass: String,
        val startOffset: Int,
    ) {
        // id == <file>:<line>:<col>:<token>#<ordinal>
        private val parts = id.split(":")
        val fileName: String get() = parts[0]
        val line: Int get() = parts[1].toInt()
        val column: Int get() = parts[2].toInt()
        val token: String get() = parts[3].substringBefore("#")
        val ordinal: Int get() = parts[3].substringAfter("#").toInt()
    }

    private val mutantLine = Regex(
        """komust-mutant id=(\S+) class=(\S+) startOffset=(\d+) path=\S+""",
    )

    private fun parseMutants(messages: String): List<ParsedMutant> =
        mutantLine.findAll(messages)
            .map { ParsedMutant(it.groupValues[1], it.groupValues[2], it.groupValues[3].toInt()) }
            .toList()
}
