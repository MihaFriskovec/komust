package io.komust.engine.sweep

import io.komust.engine.coverage.TestId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The `--tests` override's *resolution* logic in isolation (issue #36,
 * ADR-0004 §5): global vs. per-file granularity, per-file precedence, path
 * matching, and the replace-not-augment "which set applies to this mutant"
 * decision. The sweep's use of it lives in [MutantSweepOverrideTest].
 */
class TestSelectionOverrideTest {

    private val a = TestId("[engine:junit-jupiter]/[class:ATest]/[method:a()]")
    private val b = TestId("[engine:junit-jupiter]/[class:BTest]/[method:b()]")
    private val c = TestId("[engine:junit-jupiter]/[class:CTest]/[method:c()]")

    private fun mutant(sourceFile: String?) =
        Mutant("Calc.kt:4:40:ARITH#0", "fixture.Calc", 4, sourceFile)

    @Test
    fun `NONE applies to no mutant`() {
        assertTrue(TestSelectionOverride.NONE.isEmpty)
        assertNull(TestSelectionOverride.NONE.testsFor(mutant("src/main/kotlin/fixture/Calc.kt")))
    }

    @Test
    fun `a global override applies to every mutant regardless of file`() {
        val override = TestSelectionOverride.of(global = setOf(a, b))

        assertEquals(setOf(a, b), override.testsFor(mutant("src/main/kotlin/fixture/Calc.kt")))
        assertEquals(setOf(a, b), override.testsFor(mutant("src/main/kotlin/other/Thing.kt")))
        assertEquals(setOf(a, b), override.testsFor(mutant(null)))
        assertTrue(!override.isEmpty)
    }

    @Test
    fun `a per-file override applies only to that file's mutants`() {
        val override = TestSelectionOverride.of(
            perFile = mapOf("src/main/kotlin/fixture/Calc.kt" to setOf(a)),
        )

        assertEquals(setOf(a), override.testsFor(mutant("src/main/kotlin/fixture/Calc.kt")))
        assertNull(override.testsFor(mutant("src/main/kotlin/other/Thing.kt")))
        assertNull(override.testsFor(mutant(null)))
    }

    @Test
    fun `per-file wins over global for a matched file, global covers the rest`() {
        val override = TestSelectionOverride.of(
            global = setOf(c),
            perFile = mapOf("src/main/kotlin/fixture/Calc.kt" to setOf(a, b)),
        )

        assertEquals(setOf(a, b), override.testsFor(mutant("src/main/kotlin/fixture/Calc.kt")))
        assertEquals(setOf(c), override.testsFor(mutant("src/main/kotlin/other/Thing.kt")))
    }

    @Test
    fun `a per-file key matches an exact relative path and degrades to a basename match`() {
        val override = TestSelectionOverride.of(perFile = mapOf("Calc.kt" to setOf(a)))

        assertEquals(setOf(a), override.testsFor(mutant("Calc.kt")))
        assertEquals(setOf(a), override.testsFor(mutant("src/main/kotlin/fixture/Calc.kt")))
        assertEquals(setOf(a), override.testsFor(mutant("/abs/path/to/Calc.kt")))
        // A basename key must not match a different file that merely ends in the same letters.
        assertNull(override.testsFor(mutant("src/main/kotlin/fixture/MyCalc.kt")))
    }

    @Test
    fun `per-file matching normalises backslashes`() {
        val override = TestSelectionOverride.of(
            perFile = mapOf("fixture/Calc.kt" to setOf(a)),
        )

        assertEquals(setOf(a), override.testsFor(mutant("src\\main\\kotlin\\fixture\\Calc.kt")))
    }

    @Test
    fun `when several per-file keys match, the most specific wins regardless of map order`() {
        val override = TestSelectionOverride.of(
            perFile = linkedMapOf(
                "Calc.kt" to setOf(a),                       // basename, inserted first
                "src/main/kotlin/app/Calc.kt" to setOf(b),   // fully-qualified, more specific
            ),
        )

        assertEquals(setOf(b), override.testsFor(mutant("src/main/kotlin/app/Calc.kt")))
        // A file only the basename key can match still resolves to it.
        assertEquals(setOf(a), override.testsFor(mutant("src/main/kotlin/other/Calc.kt")))
    }

    @Test
    fun `an exact match beats a basename suffix match`() {
        val override = TestSelectionOverride.of(
            perFile = linkedMapOf(
                "Calc.kt" to setOf(b),        // basename suffix match, inserted first
                "app/Calc.kt" to setOf(a),    // exact match for the file below
            ),
        )
        assertEquals(setOf(a), override.testsFor(mutant("app/Calc.kt")))
    }

    @Test
    fun `two per-file keys that normalise to the same path with different sets are rejected`() {
        assertThrows<IllegalArgumentException> {
            TestSelectionOverride.of(
                perFile = mapOf("app\\Calc.kt" to setOf(a), "app/Calc.kt" to setOf(b)),
            )
        }
    }

    @Test
    fun `two per-file keys that normalise to the same path with the same set are accepted`() {
        val override = TestSelectionOverride.of(
            perFile = mapOf("app\\Calc.kt" to setOf(a), "app/Calc.kt" to setOf(a)),
        )
        assertEquals(setOf(a), override.testsFor(mutant("app/Calc.kt")))
    }

    @Test
    fun `an empty global set is rejected -- an override pins at least one test`() {
        assertThrows<IllegalArgumentException> { TestSelectionOverride.of(global = emptySet()) }
    }

    @Test
    fun `an empty per-file set is rejected`() {
        assertThrows<IllegalArgumentException> {
            TestSelectionOverride.of(perFile = mapOf("Calc.kt" to emptySet()))
        }
    }

    @Test
    fun `a blank per-file key is rejected`() {
        assertThrows<IllegalArgumentException> {
            TestSelectionOverride.of(perFile = mapOf("  " to setOf(a)))
        }
    }

    @Test
    fun `of() with no arguments is NONE-equivalent`() {
        assertTrue(TestSelectionOverride.of().isEmpty)
    }
}
