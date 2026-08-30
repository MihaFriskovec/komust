package io.komust.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The enabled/disabled-operators option (#29, AC5) — both the pure resolution
 * and its effect on what a compile weaves.
 */
class OperatorConfigTest {

    // --- pure resolution ---------------------------------------------

    @Test fun `the default is the whole default tier`() {
        assertEquals(MutationOperatorId.defaultTier, OperatorConfig.DEFAULT.enabled)
        assertTrue(MutationOperatorId.ARITHMETIC in OperatorConfig.DEFAULT)
    }

    @Test fun `disable removes an operator from the default-on set`() {
        val config = OperatorConfig.resolve(disabledSlugs = listOf("arithmetic", "equality"), enabledSlugs = emptyList())
        assertFalse(MutationOperatorId.ARITHMETIC in config)
        assertFalse(MutationOperatorId.EQUALITY in config)
        assertTrue(MutationOperatorId.RELATIONAL in config)
    }

    @Test fun `an unknown slug is reported and otherwise ignored`() {
        val unknown = mutableListOf<String>()
        val config = OperatorConfig.resolve(
            disabledSlugs = listOf("arithmetic", "not-an-operator"),
            enabledSlugs = emptyList(),
            onUnknownSlug = { unknown += it },
        )
        assertEquals(listOf("not-an-operator"), unknown)
        assertFalse(MutationOperatorId.ARITHMETIC in config)
        assertEquals(MutationOperatorId.defaultTier - MutationOperatorId.ARITHMETIC, config.enabled)
    }

    @Test fun `enable adds an operator on top of the default tier`() {
        // No experimental operators ship yet, but enable of a known slug is a no-op-safe add.
        val config = OperatorConfig.resolve(disabledSlugs = listOf("arithmetic"), enabledSlugs = listOf("arithmetic"))
        assertTrue(MutationOperatorId.ARITHMETIC in config, "enable wins over disable")
    }

    // --- effect on a compile ---------------------------------------

    @Test fun `disabling an operator drops its mutants from the weave`() {
        val src = """
            package o
            fun f(a: Int, b: Int): Int {
                return a + b
            }
        """.trimIndent()

        val withArith = FixtureCompiler.compile("O.kt", src)
        assertTrue(withArith.mutants.any { it.operator == "arithmetic" })

        val withoutArith = FixtureCompiler.compile("O.kt", src, disabledOperators = listOf("arithmetic"))
        assertTrue(withoutArith.ok, withoutArith.messages)
        assertTrue(withoutArith.mutants.none { it.operator == "arithmetic" }, "arithmetic disabled:\n${withoutArith.messages}")
        // empty-return still fires — only arithmetic was disabled.
        assertTrue(withoutArith.mutants.any { it.operator == "empty-return" })
    }

    @Test fun `an unknown slug warns but does not fail the compile`() {
        val c = FixtureCompiler.compile(
            "O.kt",
            "package o\nfun f(a: Int, b: Int) = a + b\n",
            disabledOperators = listOf("bogus-operator"),
        )
        assertTrue(c.ok, c.messages)
        assertTrue(c.messages.contains("unknown operator slug 'bogus-operator'"), c.messages)
    }
}
