package io.komust.engine.coverage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Verifies the whole coverage pass — JaCoCo runtime agent + JUnit Platform
 * Launcher + per-test listener + exec analysis + inline normalisation + green
 * baseline — end to end against a compiled fixture project (issue #32).
 *
 * Needs the JaCoCo runtime agent on this JVM; Gradle's `jacoco` plugin attaches
 * it to the `test` task, so this runs in the build but self-skips in a bare IDE
 * run without the agent.
 */
class CoveragePassFixtureTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun requireAgent() {
            assumeTrue(JacocoRuntimeAgent.isAttached(), "no JaCoCo runtime agent attached to this JVM")
        }

        private val CALC = "Calculator.kt" to """
            package fixture

            class Calculator {
                fun add(a: Int, b: Int): Int = a + b
                fun subtract(a: Int, b: Int): Int = a - b
                fun untested(a: Int, b: Int): Int = a * b
            }
        """.trimIndent()

        private val INLINE = "Inline.kt" to """
            package fixture

            inline fun timesTwo(x: Int): Int = x + x
        """.trimIndent()

        private val GREEN_TESTS = "CalculatorTest.kt" to """
            package fixture

            import org.junit.jupiter.api.Test
            import org.junit.jupiter.api.Assertions.assertEquals

            class CalculatorTest {
                @Test fun addWorks() { assertEquals(5, Calculator().add(2, 3)) }
                @Test fun subtractWorks() { assertEquals(1, Calculator().subtract(3, 2)) }
                @Test fun inlineWorks() { assertEquals(8, timesTwo(4)) }
            }
        """.trimIndent()
    }

    private fun runPass(fixture: FixtureProject): CoveragePassResult {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = fixture.classLoader
        try {
            return CoveragePass(fixture.input()).run()
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    @Test
    fun `green suite produces a coverage index keyed by (class, line) with per-test timing`(
        @TempDir tmp: Path,
    ) {
        val fixture = FixtureProject.compile(
            tmp.toFile().resolve("green"),
            listOf(CALC, INLINE, GREEN_TESTS),
        )

        val result = runPass(fixture)

        assertEquals(3, result.testCount, "three leaf tests ran")

        val addLine = fixture.lineOf("Calculator.kt", "a + b")
        val subLine = fixture.lineOf("Calculator.kt", "a - b")
        val untestedLine = fixture.lineOf("Calculator.kt", "a * b")

        val addTests = result.index.testsCovering("fixture.Calculator", addLine)
        val subTests = result.index.testsCovering("fixture.Calculator", subLine)

        assertTrue(addTests.any { it.uniqueId.contains("addWorks") }, "addWorks covers the add line: $addTests")
        assertFalse(addTests.any { it.uniqueId.contains("subtractWorks") }, "subtractWorks must not cover the add line")
        assertTrue(subTests.any { it.uniqueId.contains("subtractWorks") }, "subtractWorks covers the subtract line: $subTests")

        // untested() is mutable but no test executes it -> NO_COVERAGE (empty set, not null).
        assertEquals(emptySet<TestId>(), result.index.testsCovering("fixture.Calculator", untestedLine))

        // Per-test timing recorded for every executed test.
        result.tests.forEach { t ->
            assertTrue(t.duration >= Duration.ZERO, "duration recorded for ${t.displayName}")
            assertEquals(TestOutcome.PASSED, t.outcome)
            assertTrue(result.timing(t.id) != null)
        }
    }

    @Test
    fun `inline-function body line is covered via SMAP normalisation`(@TempDir tmp: Path) {
        val fixture = FixtureProject.compile(
            tmp.toFile().resolve("inline"),
            listOf(CALC, INLINE, GREEN_TESTS),
        )

        val result = runPass(fixture)

        val inlineBodyLine = fixture.lineOf("Inline.kt", "x + x")
        val covering = result.index.testsCovering("fixture.InlineKt", inlineBodyLine)

        assertTrue(
            covering.any { it.uniqueId.contains("inlineWorks") },
            "the inline callee's body line should be covered by the test that only calls it (SMAP normalisation): $covering",
        )
    }

    @Test
    fun `inline function in main called directly from a test is credited across separate class dirs`(
        @TempDir tmp: Path,
    ) {
        // The regression shape: main and test classes in different directories.
        val fixture = FixtureProject.compileSplit(
            tmp.toFile().resolve("split"),
            mainSources = listOf(CALC, INLINE),
            testSources = listOf(GREEN_TESTS),
        )

        val result = runPass(fixture)

        val inlineBodyLine = fixture.lineOf("Inline.kt", "x + x")
        val covering = result.index.testsCovering("fixture.InlineKt", inlineBodyLine)
        assertTrue(
            covering.any { it.uniqueId.contains("inlineWorks") },
            "inline callee body line must be covered even though the inlined copy lives in the test class dir: $covering",
        )

        // And ordinary main coverage still resolves across the split.
        val addTests = result.index.testsCovering("fixture.Calculator", fixture.lineOf("Calculator.kt", "a + b"))
        assertTrue(addTests.any { it.uniqueId.contains("addWorks") })
    }

    @Test
    fun `a red suite aborts the pass with a clear error naming the failing test`(@TempDir tmp: Path) {
        val redTests = "CalculatorTest.kt" to """
            package fixture

            import org.junit.jupiter.api.Test
            import org.junit.jupiter.api.Assertions.assertEquals

            class CalculatorTest {
                @Test fun addWorks() { assertEquals(5, Calculator().add(2, 3)) }
                @Test fun brokenExpectation() { assertEquals(999, Calculator().subtract(3, 2)) }
            }
        """.trimIndent()

        val fixture = FixtureProject.compile(tmp.toFile().resolve("red"), listOf(CALC, INLINE, redTests))

        val ex = assertThrows<RedBaselineException> { runPass(fixture) }
        assertTrue(ex.failures.any { it.displayName.contains("brokenExpectation") }, ex.message)
        assertFalse(ex.failures.any { it.displayName.contains("addWorks") })
        assertTrue(ex.message!!.contains("green baseline"))
    }

    @Test
    fun `no discoverable tests fails loudly`(@TempDir tmp: Path) {
        val fixture = FixtureProject.compile(tmp.toFile().resolve("empty"), listOf(CALC, INLINE))
        assertThrows<EmptyTestSuiteException> { runPass(fixture) }
    }
}
