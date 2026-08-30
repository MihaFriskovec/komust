package io.komust.engine.report

/**
 * Renders the human-readable report **from** a parsed [Report] (story 29) —
 * nothing here reaches into run state, so the file on disk and the console can
 * never disagree with `report.json`.
 *
 * Two modes:
 *
 *  - [render] — the full report: run metadata, roll-up, then every survivor and
 *    every no-coverage mutant with its instruction. Written to
 *    `build/komust/report.txt`.
 *  - [renderSurvivorsOnly] — the **compact survivors-only console mode** (story
 *    30): just the actionable gaps, no metadata banner, for printing to the
 *    terminal at the end of a run.
 */
public object HumanReport {

    /** The full report rendered from [report]. */
    public fun render(report: Report): String = buildString {
        val c = report.run.counts
        appendLine("komust mutation report (schema ${report.schemaVersion}, komust ${report.run.komustVersion})")
        appendLine("  operators: ${report.run.operators.joinToString(", ").ifEmpty { "(none)" }}")
        appendLine(
            "  mutants:   ${c.total} total — ${c.killed} killed, ${c.survived} survived, " +
                "${c.noCoverage} no-coverage" + if (c.timeout > 0) ", ${c.timeout} timeout" else "",
        )
        appendLine()
        appendActionable(report)
    }

    /** The compact survivors-only console view — actionable gaps only. */
    public fun renderSurvivorsOnly(report: Report): String = buildString {
        appendActionable(report)
    }

    private fun StringBuilder.appendActionable(report: Report) {
        val survivors = report.mutants.filter { it.status == MutantStatus.SURVIVED }
        val noCoverage = report.mutants.filter { it.status == MutantStatus.NO_COVERAGE }

        if (survivors.isEmpty() && noCoverage.isEmpty()) {
            appendLine("No surviving or uncovered mutants — every injected fault was caught.")
            return
        }

        if (survivors.isNotEmpty()) {
            appendLine("Surviving mutants (${survivors.size}) — a test runs this code but does not catch the change:")
            survivors.forEach { appendLine(bullet(it)) }
        }
        if (noCoverage.isNotEmpty()) {
            if (survivors.isNotEmpty()) appendLine()
            appendLine("No-coverage mutants (${noCoverage.size}) — no test executes this code at all:")
            noCoverage.forEach { appendLine(bullet(it)) }
        }
    }

    private fun bullet(m: MutantEntry): String {
        val head = "  - ${m.location.fileName}:${m.location.startLine}  ${m.enclosingSymbol}  " +
            "`${m.original}` → `${m.mutated}`  [${m.id}]"
        return m.summary?.let { "$head\n      $it" } ?: head
    }
}
