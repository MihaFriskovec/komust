package io.komust.engine

import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess

private val engineInputJson = Json { ignoreUnknownKeys = true }

/**
 * The forked engine JVM's entry point (ADR-0005 §2). The `mutationTest` task
 * launches this with one argument — the path to the serialised [EngineInput] —
 * and reads the exit code:
 *
 *  - `0` — the run completed (whatever the mutant outcomes; v1 has no score gate)
 *  - `1` — a precondition failed (red baseline, empty test suite, missing JaCoCo
 *    agent, malformed manifest) — [EngineRunner.Outcome.Aborted]
 *  - `2` — the launcher was misinvoked (no input path)
 *
 * `report.json` / `survivors.json` / `report.txt` are the real deliverables; the
 * stdout lines here are a convenience for a human watching the build log.
 */
public fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("komust-engine: expected one argument — the path to the engine-input JSON")
        exitProcess(2)
    }

    val input = engineInputJson.decodeFromString(EngineInput.serializer(), Path.of(args[0]).readText())

    when (val outcome = EngineRunner.run(input)) {
        is EngineRunner.Outcome.Completed -> {
            val c = outcome.counts
            println(
                "komust: ${c.total} mutant(s) — ${c.killed} killed, ${c.survived} survived, " +
                    "${c.noCoverage} no-coverage, ${c.timeout} timeout",
            )
            println("komust: report → ${outcome.report.reportJson}")
            println("komust: survivors → ${outcome.report.survivorsJson}")
            exitProcess(0)
        }

        is EngineRunner.Outcome.Aborted -> {
            System.err.println("komust: run aborted — ${outcome.reason}")
            exitProcess(1)
        }
    }
}
