package io.komust.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The **mutation manifest** the plugin writes when the `manifest` SubpluginOption
 * is set (#38) — the engine's slice of the input contract (ADR-0005). Asserted
 * on a compiled fixture, in isolation from any test run (walking-skeleton story
 * 18).
 */
class MutationManifestTest {

    private val source = """
        package calc

        class Calc {
            fun add(a: Int, b: Int): Int {
                return a + b
            }
        }
    """.trimIndent()

    @Test fun `writes a manifest with one record per woven mutant`() {
        val compiled = FixtureCompiler.compile(
            "Calc.kt",
            source,
            disabledOperators = listOf("relational", "equality", "boolean-logic", "boolean-inversion",
                "constant-boundary", "boolean-return", "nullable-return", "increment", "empty-return", "void-call"),
            writeManifest = true,
        )
        assertTrue(compiled.ok, compiled.messages)

        val json = compiled.manifestText
        assertNotNull(json, "the plugin wrote no manifest")
        json!!

        assertTrue(json.contains("\"schemaVersion\": \"1.0.0\""), json)
        // The single `a + b` arithmetic site → one ARITH_PLUS_TO_MINUS mutant.
        assertEquals(1, Regex("\"id\":").findAll(json).count(), json)
        assertTrue(Regex(""""id": "Calc\.kt:5:\d+:ARITH_PLUS_TO_MINUS#0"""").containsMatchIn(json), json)
        assertTrue(json.contains("\"path\": \"Calc.kt\""), json)
        assertTrue(json.contains("\"startLine\": 5"), json)
        assertTrue(json.contains("\"operator\": \"arithmetic\""), json)
        assertTrue(json.contains("\"original\": \"+\""), json)
        assertTrue(json.contains("\"mutated\": \"-\""), json)
        assertTrue(json.contains("\"enclosingSymbol\": \"add\""), json)
        assertTrue(json.contains("\"binaryClassName\": \"calc.Calc\""), json)
    }

    @Test fun `no manifest option means no file written`() {
        val compiled = FixtureCompiler.compile("Calc.kt", source, writeManifest = false)
        assertTrue(compiled.ok, compiled.messages)
        assertEquals(null, compiled.manifestText)
    }

    @Test fun `manifest records are sorted by path then line then id and endLine spans the symbol`() {
        val compiled = FixtureCompiler.compile(
            "Calc.kt",
            """
            package calc
            fun a(x: Int, y: Int): Int {
                return x + y
            }
            fun b(x: Int, y: Int): Int {
                return x - y
            }
            """.trimIndent(),
            disabledOperators = listOf("relational", "equality", "boolean-logic", "boolean-inversion",
                "constant-boundary", "boolean-return", "nullable-return", "increment", "empty-return", "void-call"),
            writeManifest = true,
        )
        assertTrue(compiled.ok, compiled.messages)
        val json = compiled.manifestText!!
        val lineOfPlus = json.indexOf("ARITH_PLUS_TO_MINUS")
        val lineOfMinus = json.indexOf("ARITH_MINUS_TO_PLUS")
        assertTrue(lineOfPlus in 0 until lineOfMinus, "records not in (path, line, id) order:\n$json")
        // `a`'s body spans lines 2..4 — endLine is the symbol's last line, not the site line.
        assertTrue(json.contains("\"startLine\": 3, \"endLine\": 4"), json)
    }
}
