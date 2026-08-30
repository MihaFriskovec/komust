package io.komust.engine.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The validity fingerprint (ADR-0003 §3): deterministic, order-independent for
 * covering classes, and sensitive to every determinant whose change could flip a
 * mutant's outcome.
 */
class ValidityFingerprintTest {

    private fun inputs(
        enclosingSymbolSource: String = "fun add(a: Int, b: Int) = a + b",
        coveringTestClassBytecode: Map<String, ByteArray> = mapOf(
            "fixture.CalcTest" to byteArrayOf(1, 2, 3),
            "fixture.MathTest" to byteArrayOf(4, 5, 6),
        ),
        komustVersion: String = "0.1.0",
        kotlinVersion: String = "2.2.0",
        jdkVersion: String = "21.0.1",
    ) = FingerprintInputs(
        enclosingSymbolSource, coveringTestClassBytecode, komustVersion, kotlinVersion, jdkVersion,
    )

    @Test
    fun `same determinants produce the same fingerprint`() {
        assertEquals(ValidityFingerprint.of(inputs()), ValidityFingerprint.of(inputs()))
    }

    @Test
    fun `the fingerprint is a lowercase-hex sha-256 digest`() {
        val hex = ValidityFingerprint.of(inputs()).hex
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in "0123456789abcdef" }, "not lowercase hex: $hex")
    }

    @Test
    fun `covering-class map order does not matter`() {
        val ordered = ValidityFingerprint.of(
            inputs(
                coveringTestClassBytecode = linkedMapOf(
                    "fixture.CalcTest" to byteArrayOf(1, 2, 3),
                    "fixture.MathTest" to byteArrayOf(4, 5, 6),
                ),
            ),
        )
        val reversed = ValidityFingerprint.of(
            inputs(
                coveringTestClassBytecode = linkedMapOf(
                    "fixture.MathTest" to byteArrayOf(4, 5, 6),
                    "fixture.CalcTest" to byteArrayOf(1, 2, 3),
                ),
            ),
        )
        assertEquals(ordered, reversed)
    }

    @Test
    fun `a whitespace-only edit to the enclosing symbol changes the fingerprint`() {
        assertNotEquals(
            ValidityFingerprint.of(inputs(enclosingSymbolSource = "fun add(a: Int, b: Int) = a + b")),
            ValidityFingerprint.of(inputs(enclosingSymbolSource = "fun add(a: Int, b: Int) =  a + b")),
        )
    }

    @Test
    fun `changing a covering class's bytecode changes the fingerprint`() {
        assertNotEquals(
            ValidityFingerprint.of(inputs()),
            ValidityFingerprint.of(
                inputs(
                    coveringTestClassBytecode = mapOf(
                        "fixture.CalcTest" to byteArrayOf(1, 2, 3, 4), // one extra byte — a method added
                        "fixture.MathTest" to byteArrayOf(4, 5, 6),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `adding a covering class changes the fingerprint`() {
        assertNotEquals(
            ValidityFingerprint.of(inputs()),
            ValidityFingerprint.of(
                inputs(
                    coveringTestClassBytecode = mapOf(
                        "fixture.CalcTest" to byteArrayOf(1, 2, 3),
                        "fixture.MathTest" to byteArrayOf(4, 5, 6),
                        "fixture.NewTest" to byteArrayOf(7),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `komust, Kotlin and JDK versions are each determinants`() {
        val base = ValidityFingerprint.of(inputs())
        assertNotEquals(base, ValidityFingerprint.of(inputs(komustVersion = "0.2.0")))
        assertNotEquals(base, ValidityFingerprint.of(inputs(kotlinVersion = "2.2.10")))
        assertNotEquals(base, ValidityFingerprint.of(inputs(jdkVersion = "21.0.2")))
    }

    @Test
    fun `field boundaries are unambiguous - moving a byte across a boundary still differs`() {
        // "abc" + version "d"  vs  "ab" + version "cd" — length-prefixed framing
        // must keep these distinct.
        assertNotEquals(
            ValidityFingerprint.of(inputs(enclosingSymbolSource = "abc", komustVersion = "d")),
            ValidityFingerprint.of(inputs(enclosingSymbolSource = "ab", komustVersion = "cd")),
        )
    }
}
