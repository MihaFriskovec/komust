package io.komust.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Seam 3 (the compiler-plugin IR transform), skeleton slice.
 *
 * #27 weaves no mutants, so the only externally observable contract is: the
 * plugin loads into a real K2 compile, and a fixture compiled with it produces
 * byte-identical behaviour to the same fixture compiled without it. #28 adds the
 * first golden mutant-set assertion on top of this harness.
 */
class IdentityTransformTest {

    private val fixture = """
        package io.komust.demo

        fun add(a: Int, b: Int): Int = a + b

        fun sumThree(a: Int, b: Int, c: Int): Int = a + b + c

        fun classify(n: Int): String = if (n > 0) "pos" else if (n < 0) "neg" else "zero"

        class Calc(private val base: Int) {
            fun scale(factor: Int): Int = base * factor + 1
        }
    """.trimIndent()

    @Test
    fun `the fixture compiles with the plugin applied`() {
        val result = FixtureCompiler.compile("Demo.kt", fixture)

        assertTrue(result.ok, "fixture must compile with the plugin applied:\n${result.messages}")
        // The skeleton's only compile-time footprint: the IR pass runs and
        // weaves nothing. (#28 adds a golden mutant-set assertion here.)
        assertTrue(
            result.messages.contains("komust: identity transform") &&
                result.messages.contains("0 mutants woven"),
            "the IR extension must have run as a no-op:\n${result.messages}",
        )
    }

    @Test
    fun `every fixture symbol behaves identically with and without the plugin`() {
        val withPlugin = FixtureCompiler.compile("Demo.kt", fixture, withPlugin = true)
        val withoutPlugin = FixtureCompiler.compile("Demo.kt", fixture, withPlugin = false)

        for (compilation in listOf(withPlugin, withoutPlugin)) {
            assertEquals(5, compilation.call("io.komust.demo.DemoKt", "add", 2, 3))
            assertEquals(6, compilation.call("io.komust.demo.DemoKt", "sumThree", 1, 2, 3))
            assertEquals("neg", compilation.call("io.komust.demo.DemoKt", "classify", -4))
            assertEquals("zero", compilation.call("io.komust.demo.DemoKt", "classify", 0))
            assertEquals(21, compilation.callOn("io.komust.demo.Calc", listOf(4), "scale", 5))
        }
    }

    @Test
    fun `an empty source compiles cleanly with the plugin`() {
        val result = FixtureCompiler.compile(
            "Empty.kt",
            """
            package io.komust.demo
            val greeting: String = "hi"
            """.trimIndent(),
        )
        assertTrue(result.ok, result.messages)
        assertTrue(result.messages.contains("0 mutants woven"))
    }
}
