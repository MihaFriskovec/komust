package io.komust.compiler

/**
 * The arithmetic operator rewrites komust's **first** operator performs (#28).
 *
 * v1's catalog (ADR-0001) has the arithmetic operator do `+↔-`, `*↔/` and
 * `%→/` (the last with a `0/0` guard). This ticket ships only the **additive
 * swap** — it is its own inverse and can never produce an always-crashing junk
 * mutant by construction, so it proves the compile-once weaving contract without
 * dragging in the divide-by-zero skip-list guard. The multiplicative and
 * remainder rewrites, and the enabled/disabled-operator wiring, land with #29.
 *
 * [callee] is the Kotlin operator-function name the site desugars to
 * (`a + b` → `Int.plus`), and [replacement] the counterpart the mutant branch
 * calls instead. [token] is the stable operator segment of a mutant `id`.
 */
internal enum class ArithmeticMutation(
    val token: String,
    val callee: String,
    val replacement: String,
) {
    PLUS_TO_MINUS("ARITH_PLUS_TO_MINUS", callee = "plus", replacement = "minus"),
    MINUS_TO_PLUS("ARITH_MINUS_TO_PLUS", callee = "minus", replacement = "plus"),
    ;

    internal companion object {
        private val byCallee: Map<String, ArithmeticMutation> = entries.associateBy { it.callee }

        /** The rewrite for an operator function named [calleeName], or `null` if komust does not mutate it. */
        fun forCallee(calleeName: String): ArithmeticMutation? = byCallee[calleeName]
    }
}
