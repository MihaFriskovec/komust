plugins {
    id("komust.kotlin-module")
}

dependencies {
    // The engine drives tests through the JUnit Platform Launcher API directly
    // (ADR-0005 §4) — the launcher is a main dependency here, not just a test one.
    implementation(platform(libs.junit.bom))
    implementation(libs.junit.platform.launcher)

    // Coverage pass (ADR-0004): the runtime agent's control API drives per-test
    // dump/reset in-process; the core API turns each `.exec` snapshot into
    // covered source lines; ASM reads SMAP for inline-line normalisation.
    implementation(variantOf(libs.jacoco.agent) { classifier("runtime") })
    implementation(libs.jacoco.core)
    implementation(libs.asm)

    testImplementation(libs.junit.jupiter)
    // The coverage-pass integration test compiles a throwaway fixture project
    // in-process with the pinned K2 compiler (same mechanism as the
    // compiler-plugin module's tests).
    testImplementation(libs.kctfork.core)
}

tasks.withType<Test>().configureEach {
    // `PerTestCoverageListenerTest` ships small JUnit classes it feeds to the
    // Launcher itself; they must not run as part of this module's own suite.
    useJUnitPlatform { excludeTags("coverage-fixture") }
}

// The coverage pass reaches the JaCoCo runtime agent via
// `org.jacoco.agent.rt.RT.getAgent()`, which needs the agent attached to this
// JVM. Gradle's `jacoco` plugin (applied by the convention plugin) already
// attaches one to `Test` tasks, so the integration test exercises the real
// agent path with no extra wiring. That test's per-test `reset()` calls also
// zero this module's own accumulated counters mid-run, so komust-engine's own
// JaCoCo line report is not meaningful while it runs — acceptable, nothing
// gates on it.
