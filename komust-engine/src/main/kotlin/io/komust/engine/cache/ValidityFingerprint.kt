package io.komust.engine.cache

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The per-mutant **validity fingerprint** (ADR-0003 §3): a hash of every
 * determinant whose change could flip a mutant's execution outcome.
 *
 * A cached outcome for mutant `id` is reusable **only** when the `id` *and* this
 * fingerprint both match ([MutantResultCache.lookup]). Any determinant change is
 * a cache miss and re-executes the mutant — the fingerprint is what lets "the
 * agent added one test" re-run only the affected symbols while every other
 * symbol stays a cache hit (CONTEXT.md — **Validity fingerprint**).
 *
 * The value is an opaque lowercase-hex SHA-256 digest; only equality matters.
 * It is deliberately biased *safe* (coarser than strictly necessary): a
 * whitespace-only edit to the enclosing symbol, or an unrelated method added to
 * a covering test's declaring class, both invalidate — matching #6's accepted
 * over-inclusion posture.
 */
@JvmInline
public value class ValidityFingerprint(public val hex: String) {
    override fun toString(): String = hex

    public companion object {
        /**
         * Compute the fingerprint from its determinants. Pure and deterministic:
         * the same [inputs] always produce the same digest, and the covering-test
         * classes are folded in sorted by name so their map order is irrelevant.
         */
        public fun of(inputs: FingerprintInputs): ValidityFingerprint {
            val md = MessageDigest.getInstance("SHA-256")

            // Canonical, unambiguous framing: every part is length-prefixed, so
            // no concatenation of one field can be mistaken for another.
            fun put(part: ByteArray) {
                md.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(part.size.toLong()).array())
                md.update(part)
            }
            fun put(part: String) = put(part.toByteArray(Charsets.UTF_8))
            fun put(count: Int) = put(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(count).array())

            put("komust/validity-fingerprint/v1")

            // 1. Enclosing-symbol source — the exact bytes the compiler saw for
            //    the mutant's enclosing declaration, no normalisation (ADR-0003).
            put(inputs.enclosingSymbolSource)

            // 2. Covering-test content — per covering test, its declaring-class
            //    bytecode, class-level (ADR-0003): adding a method to a covering
            //    test class invalidates every mutant that class covers, which is
            //    correct plus mild, acceptable collateral. Sorted so the digest
            //    does not depend on iteration order.
            val classes = inputs.coveringTestClassBytecode.toSortedMap()
            put(classes.size)
            for ((className, bytecode) in classes) {
                put(className)
                put(bytecode)
            }

            // 3. Operator + config — an operator's semantics changing must
            //    invalidate; the komust / operator-catalog version stands in.
            put(inputs.komustVersion)

            // 4. Environment — the K2 plugin API and operator behaviour are
            //    Kotlin-version-sensitive; the JDK likewise.
            put(inputs.kotlinVersion)
            put(inputs.jdkVersion)

            return ValidityFingerprint(HexFormat.of().formatHex(md.digest()))
        }
    }
}

/**
 * The determinants [ValidityFingerprint.of] hashes. The engine orchestrator
 * resolves these from the run — this module only defines their shape, exactly as
 * the sweep's [io.komust.engine.sweep.Mutant] and the report's
 * [io.komust.engine.report.MutantDescriptor] are contract slices, not producers.
 *
 * Not a `data class`: [coveringTestClassBytecode]'s `ByteArray` values would give
 * it a referential `equals`, and nothing compares two [FingerprintInputs] anyway
 * — the fingerprint is the comparable projection.
 *
 * @property enclosingSymbolSource raw source span of the mutant's enclosing
 *   symbol — the exact bytes the compiler saw, no normalisation (ADR-0003 §3.1).
 * @property coveringTestClassBytecode for each of the mutant's covering tests,
 *   its declaring class's compiled bytecode, keyed by binary (dotted) class name.
 *   Class-level, from the green baseline compile (ADR-0003 §3.2). Multiple
 *   covering tests in one class contribute a single entry. Empty for a
 *   `NO_COVERAGE` mutant.
 * @property komustVersion the komust / operator-catalog version (ADR-0003 §3.3).
 * @property kotlinVersion the Kotlin version the **mutation compilation** used
 *   (ADR-0003 §3.4) — the orchestrator's toolchain value, not this JVM's stdlib.
 * @property jdkVersion the JDK the mutation run executes on (ADR-0003 §3.4).
 */
public class FingerprintInputs(
    public val enclosingSymbolSource: String,
    public val coveringTestClassBytecode: Map<String, ByteArray>,
    public val komustVersion: String,
    public val kotlinVersion: String,
    public val jdkVersion: String,
)
