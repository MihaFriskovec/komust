package io.komust.engine.report

/**
 * Renders the one-line `summary` an agent acts on directly (#5, story 28): a
 * "write a test that does X" instruction built purely from a mutant's facts, so
 * it is deterministic and needs no run state.
 *
 * Only `SURVIVED` and `NO_COVERAGE` mutants get a summary — they are the
 * actionable outcomes. A `KILLED` mutant needs no action.
 */
internal object SummaryRenderer {

    /** For a mutant a test covers but no test kills. */
    fun survived(descriptor: MutantDescriptor, coveringTestCount: Int): String {
        val where = where(descriptor)
        val covering = when (coveringTestCount) {
            1 -> "1 covering test still passes"
            else -> "$coveringTestCount covering tests still pass"
        }
        return "In $where, changing `${descriptor.original}` to `${descriptor.mutated}` is not detected — " +
            "$covering. Add or strengthen a test of $where so that this change makes it fail."
    }

    /** For a mutant on a line no test executes. */
    fun noCoverage(descriptor: MutantDescriptor): String {
        val where = where(descriptor)
        return "In $where, no test executes the `${descriptor.original}` at line ${descriptor.location.startLine}. " +
            "Write a test that exercises $where and would fail if `${descriptor.original}` became " +
            "`${descriptor.mutated}`."
    }

    /** `` `Calc.add` (Calc.kt:4) `` — the symbol + where to find it. */
    private fun where(descriptor: MutantDescriptor): String =
        "`${descriptor.enclosingSymbol}` (${descriptor.location.fileName}:${descriptor.location.startLine})"
}
