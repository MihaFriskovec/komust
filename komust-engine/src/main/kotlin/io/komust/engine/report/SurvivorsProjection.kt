package io.komust.engine.report

/**
 * Projects `survivors.json` **from** a parsed [Report] — the JSON is the source
 * of truth (story 29), so this reads a `Report` and never touches run state.
 *
 * `SURVIVED` mutants become [Survivor]s, `NO_COVERAGE` mutants become
 * [NoCoverageMutant]s (their own category), and everything else is dropped. The
 * `Report`'s `(path, startLine, id)` order is preserved, so both lists stay in
 * lock-step with `report.json`.
 */
public object SurvivorsProjection {

    public fun from(report: Report): Survivors = Survivors(
        schemaVersion = Survivors.SCHEMA_VERSION,
        survivors = report.mutants
            .filter { it.status == MutantStatus.SURVIVED }
            .map { it.toSurvivor() },
        noCoverage = report.mutants
            .filter { it.status == MutantStatus.NO_COVERAGE }
            .map { it.toNoCoverageMutant() },
    )

    private fun MutantEntry.toSurvivor() = Survivor(
        id = id,
        location = location,
        operator = operator,
        original = original,
        mutated = mutated,
        enclosingSymbol = enclosingSymbol,
        coveringTests = coveringTests,
        summary = requireSummary(),
    )

    private fun MutantEntry.toNoCoverageMutant() = NoCoverageMutant(
        id = id,
        location = location,
        operator = operator,
        original = original,
        mutated = mutated,
        enclosingSymbol = enclosingSymbol,
        summary = requireSummary(),
    )

    private fun MutantEntry.requireSummary(): String =
        summary ?: error("report.json mutant '$id' has status $status but no summary — malformed report")
}
