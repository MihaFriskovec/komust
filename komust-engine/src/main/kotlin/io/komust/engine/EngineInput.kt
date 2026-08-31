package io.komust.engine

import kotlinx.serialization.Serializable

/**
 * The build-tool-agnostic **engine input contract** (ADR-0005): everything the
 * forked engine JVM needs to run coverage → sweep → JSON emit, serialised to one
 * JSON file the `mutationTest` task writes and hands the fork as its single
 * argument. The deferred CLI produces the identical shape.
 *
 * Every path here is a string the adapter already resolved against its build
 * model (absolute in a Gradle build); [EngineRunner] turns them into `Path`s.
 * `report.json`'s own source paths stay repo-relative — those come from the
 * mutation manifest, not from this contract.
 */
@Serializable
public data class EngineInput(
    /**
     * The mutation compilation's output dirs — the one shared compile
     * (ADR-0004): analysed for coverage with mutants **off**, then swept with
     * one mutant on at a time.
     */
    val classesUnderTest: List<String>,
    /** Compiled test-class output dirs — discovered and run through the JUnit Platform Launcher. */
    val testClassRoots: List<String>,
    /**
     * Paths to the compiler plugin's `mutants.json` manifest(s) — one per
     * mutation compilation (one per mutated source set). Merged by id.
     */
    val mutationManifests: List<String>,
    /**
     * The full classpath for a forked worker JVM: the engine, JUnit Platform + a
     * Jupiter engine, the `io.komust.runtime` switch, the Kotlin stdlib, plus
     * [reloadableRoots].
     */
    val workerClasspath: List<String>,
    /** Code-under-test + test-class dirs a worker reloads per mutant for state isolation. */
    val reloadableRoots: List<String>,
    /** Where `report.json` / `survivors.json` / `report.txt` are written (`build/komust/`). */
    val outputDir: String,
    val config: EngineConfig = EngineConfig(),
    val komustVersion: String = "0.0.0-dev",
    val kotlinVersion: String = "unknown",
    val jdkVersion: String = "unknown",
) {

    /** The resolved `komust {}` policy the engine acts on (ADR-0005 §6). */
    @Serializable
    public data class EngineConfig(
        /** Forked-worker count (`komust { workers }`). Coerced to ≥ 1 by the runner. */
        val workers: Int = 1,
        /** Baseline-relative timeout multiplier (`komust { timeoutFactor }`). */
        val timeoutFactor: Double = 3.0,
        /**
         * Cross-run cache toggle (`komust { cache }` / `--no-cache`, #10/#37).
         * Recorded on the contract today; the v1 engine runs a cold sweep
         * regardless (a cache miss is always safe — CONTEXT.md).
         */
        val cache: Boolean = true,
        /** Render `report.txt` from `report.json` (`komust { output { humanReport } }`). */
        val humanReport: Boolean = true,
        /** Print the token-dense survivor stream to stdout (`output { consoleSurvivorsOnly }`). */
        val consoleSurvivorsOnly: Boolean = false,
        /** `--tests` explicit override; `null` → coverage-mapped selection for every mutant. */
        val testOverride: TestOverrideSpec? = null,
    )

    /** The resolved `--tests` override (ADR-0004 §5): a global set and/or per-file sets of JUnit `uniqueId`s. */
    @Serializable
    public data class TestOverrideSpec(
        val global: List<String> = emptyList(),
        val perFile: Map<String, List<String>> = emptyMap(),
    )
}
