rootProject.name = "komust"

plugins {
    // Auto-provision the pinned JDK toolchain (jvmToolchain(21)) on machines
    // that don't already have it — keeps a fresh clone / CI runner green
    // without a manual JDK install step.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// The four v1 modules (CONTEXT.md, ADR-0005). All empty at this ticket —
// #24 wires the skeleton every other ticket builds on.
include(
    ":komust-compiler-plugin", // K2 IR mutation surface (#2, #27-#31)
    ":komust-scope",           // git diff -> Mutation Scope -> scope.json (#25, #26)
    ":komust-engine",          // build-tool-agnostic orchestrator (#32-#37)
    ":komust-gradle-plugin",   // thin Gradle adapter over the engine (#38)
)
