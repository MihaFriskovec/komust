package io.komust.compiler.ir

import org.jetbrains.kotlin.ir.declarations.IrFile

/**
 * The compat-shim seam.
 *
 * This is the **single place** `komust-compiler-plugin` is allowed to touch the
 * Kotlin-version-specific K2 compiler / IR API. Everything unstable that a
 * Kotlin upgrade is likely to break lives behind this object, so porting komust
 * to a new Kotlin release is a diff to one file rather than a scavenger hunt.
 *
 * Why this seam exists (see docs/adr/0005-gradle-plugin-architecture.md and the
 * catalog note in gradle/libs.versions.toml):
 *
 *  - The K2 compiler-plugin entry points are gated behind
 *    `@ExperimentalCompilerApi`, and IR construction behind
 *    `@UnsafeDuringIrConstructionAPI`. The Kotlin team explicitly does not keep
 *    these source- or binary-compatible across releases.
 *  - The #2 IR spike already hit concrete churn: value arguments moved onto the
 *    unified `IrMemberAccessExpression.arguments[]` index list — which is now
 *    1-to-1 with the callee's parameters (dispatch receiver, then extension
 *    receiver, then context parameters, then regular arguments, with **no**
 *    null placeholder for an absent receiver). Reading "the operand of `a + b`"
 *    is therefore a parameter-kind lookup, not a fixed index — exactly the kind
 *    of version-specific subtlety this seam is here to absorb.
 *
 * Rules for this file:
 *
 *  1. The Kotlin version is exact-pinned in the version catalog. Bumping it is a
 *     deliberate, reviewed change — and the review starts here.
 *  2. No `org.jetbrains.kotlin.*` import anywhere else in this module. New code
 *     that needs the compiler API adds a narrow helper here and calls that.
 *  3. Each helper is named for the komust-level intent ("the source line of an
 *     IR node"), not the current API shape, so callers survive a port.
 *
 * The seed helpers below are the source-location primitives every operator
 * needs. The operator-argument accessors (reading and rewriting the operands of
 * a binary call, receiver-kind aware) land with the first operator, #28, which
 * extends this object rather than reaching around it.
 */
internal object KotlinIrCompat {

    /** 1-based `(line, column)` of [offset] within [file]. */
    fun lineColumn(file: IrFile, offset: Int): Pair<Int, Int> {
        val entry = file.fileEntry
        return (entry.getLineNumber(offset) + 1) to (entry.getColumnNumber(offset) + 1)
    }

    /** Source path backing [file], as the compiler reports it. */
    fun filePath(file: IrFile): String = file.fileEntry.name
}
