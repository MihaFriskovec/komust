package io.komust.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Golden mutant sets for the opt-in experimental tier (#31). */
class ExperimentalOperatorGoldenTest {

    @Test fun `experimental operators are off by default`() {
        val c = FixtureCompiler.compile("Experimental.kt", fixture)

        assertTrue(c.ok, c.messages)
        assertTrue(c.mutants.none { it.operator in experimentalSlugs }, c.messages)
    }

    @Test fun `the whole experimental tier can be enabled`() {
        val c = FixtureCompiler.compile("Experimental.kt", fixture, experimentalTier = true)

        assertTrue(c.ok, c.messages)
        assertEquals(experimentalSlugs, c.mutants.map { it.operator }.filter { it in experimentalSlugs }.toSet())
    }

    @Test fun `elvis-default emits only the default branch rewrite`() {
        assertEquals(
            setOf("e.ExperimentalKt L2 ELVIS_TO_DEFAULT #0"),
            golden(
                """
                package e
                fun elvis(value: String?): String = value ?: "fallback"
                """.trimIndent(),
                only = "elvis-default",
            ),
        )
    }

    @Test fun `invert-negatives drops unary negation`() {
        assertEquals(
            setOf("e.ExperimentalKt L2 NEGATE_DROP #0"),
            golden(
                """
                package e
                fun negative(value: Int): Int = -value
                """.trimIndent(),
                only = "invert-negatives",
            ),
        )
    }

    @Test fun `exception-type-swap rewrites both supported exception types`() {
        assertEquals(
            setOf(
                "e.ExperimentalKt L2 EXCEPTION_IAE_TO_ISE #0",
                "e.ExperimentalKt L3 EXCEPTION_ISE_TO_IAE #0",
            ),
            golden(
                """
                package e
                fun argument(): Nothing = throw IllegalArgumentException("bad argument")
                fun state(): Nothing = throw IllegalStateException("bad state")
                """.trimIndent(),
                only = "exception-type-swap",
            ),
        )
    }

    private fun golden(src: String, only: String): Set<String> {
        val c = FixtureCompiler.compile(
            "Experimental.kt",
            src,
            disabledOperators = MutationOperatorId.defaultTier.map { it.slug },
            enabledOperators = listOf(only),
        )
        assertTrue(c.ok, c.messages)
        assertEquals(c.summaryCount, c.mutants.size, "summary count vs parsed mutants")
        return c.goldenSet()
    }

    private companion object {
        val experimentalSlugs = setOf("elvis-default", "invert-negatives", "exception-type-swap")

        val fixture = """
            package e
            fun elvis(value: String?): String = value ?: "fallback"
            fun negative(value: Int): Int = -value
            fun fail(flag: Boolean): Nothing = if (flag) {
                throw IllegalArgumentException("bad argument")
            } else {
                throw IllegalStateException("bad state")
            }
        """.trimIndent()
    }
}
