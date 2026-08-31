package io.komust.engine

import io.komust.engine.coverage.CoveragePass
import io.komust.engine.coverage.CoveragePassException
import io.komust.engine.coverage.CoveragePassInput
import io.komust.engine.coverage.CoveragePassResult
import io.komust.engine.coverage.TestId
import io.komust.engine.report.Counts
import io.komust.engine.report.ReportBuilder
import io.komust.engine.report.ReportWriter
import io.komust.engine.report.WrittenReport
import io.komust.engine.sweep.Mutant
import io.komust.engine.sweep.MutantSweep
import io.komust.engine.sweep.SweepConfig
import io.komust.engine.sweep.SweepResult
import io.komust.engine.sweep.TestSelectionOverride
import io.komust.engine.sweep.TimeoutPolicy
import io.komust.engine.sweep.forked.ForkedMutantSweep
import java.nio.file.Path
import java.time.Instant

/**
 * The **core engine** (ADR-0005): given an [EngineInput], runs the coverage pass
 * (ADR-0004) → the mutant sweep (ADR-0003) → the JSON output (#5), and returns
 * where the artifacts landed. Build-tool-agnostic — the Gradle plugin forks a
 * JVM into [EngineMain] which calls this; the deferred CLI will call it the same
 * way.
 *
 * The sweep is the **forked worker pool** ([ForkedMutantSweep]) unless a
 * `--tests` override is configured — that lives only on the sequential
 * [MutantSweep] today (#36), so an overridden run scores in-process.
 */
public object EngineRunner {

    public sealed interface Outcome {
        /** The run completed; [report] names the three files, [counts] rolls up the mutants. */
        public data class Completed(val report: WrittenReport, val counts: Counts) : Outcome

        /** A precondition failed (red baseline, empty suite, missing JaCoCo agent, bad manifest). */
        public data class Aborted(val reason: String) : Outcome
    }

    public fun run(input: EngineInput): Outcome {
        val startedAt = Instant.now()

        val parsed = try {
            MutationManifestReader.read(input.mutationManifests.map(Path::of))
        } catch (e: IllegalArgumentException) {
            return Outcome.Aborted(e.message ?: "could not read the mutation manifest")
        }

        val coverage = try {
            CoveragePass(
                CoveragePassInput(
                    classesUnderTest = input.classesUnderTest.map(Path::of),
                    testClassRoots = input.testClassRoots.map(Path::of),
                ),
            ).run()
        } catch (e: CoveragePassException) {
            return Outcome.Aborted(e.message ?: e.toString())
        }

        val sweep =
            if (parsed.mutants.isEmpty()) SweepResult(emptyList())
            else sweep(input, coverage, parsed.mutants)

        val report = ReportBuilder.build(
            descriptors = parsed.descriptors,
            sweep = sweep,
            run = ReportBuilder.RunInfo(startedAt, Instant.now(), input.komustVersion),
        )
        val written = ReportWriter.write(Path.of(input.outputDir), report)
        return Outcome.Completed(written, report.run.counts)
    }

    private fun sweep(input: EngineInput, coverage: CoveragePassResult, mutants: List<Mutant>): SweepResult {
        val override = input.config.testOverride?.toOverride() ?: TestSelectionOverride.NONE
        if (!override.isEmpty) {
            return MutantSweep(coverage, testSelection = override).sweep(mutants)
        }
        val config = SweepConfig(
            workerCount = input.config.workers.coerceAtLeast(1),
            timeoutPolicy = TimeoutPolicy(factor = input.config.timeoutFactor),
        )
        return ForkedMutantSweep.forking(
            coveragePass = coverage,
            workerClasspath = input.workerClasspath.map(Path::of),
            reloadableRoots = input.reloadableRoots.map(Path::of),
            config = config,
        ).sweep(mutants)
    }

    private fun EngineInput.TestOverrideSpec.toOverride(): TestSelectionOverride =
        TestSelectionOverride.of(
            global = global.takeIf { it.isNotEmpty() }?.map(::TestId)?.toSet(),
            perFile = perFile.mapValues { (_, ids) -> ids.map(::TestId).toSet() },
        )
}
