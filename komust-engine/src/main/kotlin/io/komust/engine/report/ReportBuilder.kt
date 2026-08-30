package io.komust.engine.report

import io.komust.engine.sweep.MutantResult
import io.komust.engine.sweep.MutantStatus as SweepStatus
import io.komust.engine.sweep.SweepResult
import java.time.Instant

/**
 * Assembles the canonical [Report] from the two halves of a run:
 *
 *  - the **compile-time** [MutantDescriptor]s (identity, location, operator,
 *    `original → mutated`, enclosing symbol), and
 *  - the **execution-derived** [SweepResult] (status, covering tests, killer).
 *
 * The two are joined by mutant `id`; every descriptor must have exactly one
 * sweep result and vice versa, or the run is internally inconsistent and this
 * throws.
 *
 * The output is fully deterministic: [Report.mutants] is sorted by
 * `(path, startLine, id)` and every derived string (summaries, operator list)
 * comes only from the inputs — so `report.json` diffs cleanly between runs
 * (story 32).
 */
public object ReportBuilder {

    /**
     * Run-level facts the caller supplies — the engine orchestrator knows these,
     * the report layer does not. Kept as an explicit parameter so the builder
     * stays a pure function and tests are deterministic.
     */
    public data class RunInfo(
        val startedAt: Instant,
        val finishedAt: Instant,
        val komustVersion: String,
    )

    public fun build(
        descriptors: List<MutantDescriptor>,
        sweep: SweepResult,
        run: RunInfo,
    ): Report {
        val byId = descriptors.associateBy { it.id }
        require(byId.size == descriptors.size) {
            "duplicate mutant id(s) among descriptors: " +
                descriptors.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        }

        val resultIds = sweep.results.map { it.mutant.id }.toSet()
        val descriptorIds = byId.keys
        require(resultIds == descriptorIds) {
            "descriptor / sweep-result mismatch — " +
                "no sweep result for ${descriptorIds - resultIds}; " +
                "no descriptor for ${resultIds - descriptorIds}"
        }

        val entries = sweep.results
            .map { result -> entry(byId.getValue(result.mutant.id), result) }
            .sortedWith(
                compareBy({ it.location.path }, { it.location.startLine }, { it.id }),
            )

        val counts = Counts(
            total = entries.size,
            killed = entries.count { it.status == MutantStatus.KILLED },
            survived = entries.count { it.status == MutantStatus.SURVIVED },
            noCoverage = entries.count { it.status == MutantStatus.NO_COVERAGE },
            timeout = entries.count { it.status == MutantStatus.TIMEOUT },
        )

        return Report(
            schemaVersion = Report.SCHEMA_VERSION,
            run = RunMetadata(
                startedAt = run.startedAt.toString(),
                finishedAt = run.finishedAt.toString(),
                komustVersion = run.komustVersion,
                operators = descriptors.map { it.operator }.distinct().sorted(),
                counts = counts,
            ),
            mutants = entries,
        )
    }

    private fun entry(descriptor: MutantDescriptor, result: MutantResult): MutantEntry {
        val status = when (result.status) {
            SweepStatus.KILLED -> MutantStatus.KILLED
            SweepStatus.SURVIVED -> MutantStatus.SURVIVED
            SweepStatus.NO_COVERAGE -> MutantStatus.NO_COVERAGE
            SweepStatus.TIMEOUT -> MutantStatus.TIMEOUT
        }
        return MutantEntry(
            id = descriptor.id,
            status = status,
            location = descriptor.location,
            operator = descriptor.operator,
            original = descriptor.original,
            mutated = descriptor.mutated,
            enclosingSymbol = descriptor.enclosingSymbol,
            coveringTests = result.coveringTests.map { it.uniqueId },
            testsExecuted = result.testsExecuted,
            killedBy = result.killedBy?.uniqueId,
            summary = when (status) {
                MutantStatus.SURVIVED -> SummaryRenderer.survived(descriptor, result.coveringTests.size)
                MutantStatus.NO_COVERAGE -> SummaryRenderer.noCoverage(descriptor)
                MutantStatus.KILLED, MutantStatus.TIMEOUT -> null
            },
        )
    }
}
