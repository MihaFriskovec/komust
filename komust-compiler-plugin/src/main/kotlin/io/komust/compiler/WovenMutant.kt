package io.komust.compiler

/**
 * A mutant the IR pass wove into the current compilation.
 *
 * ## Key: `(file, line, col, operator, ordinal)` — never position alone
 *
 * The #2 spike found `startOffset` is **not unique**: nested same-line operators
 * (`a + b + c` desugars to `(a + b) + c`, and the outer and inner `plus` calls
 * share one `startOffset`). [ordinal] is the tie-breaker within a position, so
 * every woven site is independently switchable. The `(file, line, col)` still
 * give the source range the Mutation Scope (ADR-0002) and coverage index
 * (ADR-0004) join against.
 *
 * ## Why the binary class name is captured here
 *
 * [binaryClassName] is the JVM name of the enclosing class — or the file facade
 * for a top-level declaration. It is known at injection time and the coverage
 * index keys on `(binary class name, source line)`, so recording it now saves a
 * later pass from re-deriving it from mutated IR.
 *
 * [startOffset] is the site's original offset, carried so the injected `if/else`
 * can be asserted to preserve it (ADR-0003's stack-trace-fidelity concern).
 *
 * The globally-unique key is the tuple `(filePath, line, column, mutation,
 * ordinal)`; [id] renders it with the file **basename** for readability and
 * ordinals restart at 0 per position *within a file*, so [id] alone is unique
 * only across a source set with distinct basenames. Downstream identity (#5) is
 * expected to fold this into a content hash.
 */
internal data class WovenMutant(
    val filePath: String,
    val fileName: String,
    val line: Int,
    val column: Int,
    val mutation: ArithmeticMutation,
    val ordinal: Int,
    val binaryClassName: String,
    val startOffset: Int,
) {
    /** The stable key string: `<file>:<line>:<col>:<operator-token>#<ordinal>`. */
    val id: String = "$fileName:$line:$column:${mutation.token}#$ordinal"
}
