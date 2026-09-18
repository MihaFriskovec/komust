package io.komust.compiler

import io.komust.runtime.MutantRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException

/** Runtime-switch behaviour for the opt-in experimental operators (#31). */
class ExperimentalOperatorBehaviourTest {

    @AfterEach fun reset() = MutantRegistry.clear()

    @Test fun `elvis-default always evaluates to the fallback`() {
        val c = compile(
            """
            package e
            fun elvis(value: String?): String = value ?: "fallback"
            """.trimIndent(),
            "elvis-default",
        )
        assertEquals("value", c.call("e.ExperimentalKt", "elvis", "value"))

        MutantRegistry.activate(c.mutant("ELVIS_TO_DEFAULT"))
        assertEquals("fallback", c.call("e.ExperimentalKt", "elvis", "value"))
        assertEquals("fallback", c.call("e.ExperimentalKt", "elvis", null))
    }

    @Test fun `invert-negatives removes unary negation`() {
        val c = compile(
            """
            package e
            fun negative(value: Int): Int = -value
            """.trimIndent(),
            "invert-negatives",
        )
        assertEquals(-7, c.call("e.ExperimentalKt", "negative", 7))

        MutantRegistry.activate(c.mutant("NEGATE_DROP"))
        assertEquals(7, c.call("e.ExperimentalKt", "negative", 7))
    }

    @Test fun `exception-type-swap preserves the message and changes the thrown type`() {
        val c = compile(
            """
            package e
            fun fail(): Nothing = throw IllegalArgumentException("bad argument")
            """.trimIndent(),
            "exception-type-swap",
        )
        MutantRegistry.activate(c.mutant("EXCEPTION_IAE_TO_ISE"))

        val reflected = assertThrows(InvocationTargetException::class.java) {
            c.call("e.ExperimentalKt", "fail")
        }
        val swapped = assertInstanceOf(IllegalStateException::class.java, reflected.cause)
        assertEquals("bad argument", swapped.message)
    }

    private fun compile(source: String, operator: String): FixtureCompiler.Compiled =
        FixtureCompiler.compile(
            "Experimental.kt",
            source,
            disabledOperators = MutationOperatorId.defaultTier.map { it.slug },
            enabledOperators = listOf(operator),
        )

    private fun FixtureCompiler.Compiled.mutant(token: String): String {
        assertTrue(ok, messages)
        return mutants.single { it.token == token }.id
    }
}
