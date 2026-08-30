plugins {
    `kotlin-dsl`
}

dependencies {
    // Makes `kotlin("jvm")` available to the precompiled convention plugin,
    // pinned to the exact catalog version.
    implementation(libs.kotlin.gradle.plugin)
    // Puts `kotlin("plugin.serialization")` on the build classpath at the same
    // pinned Kotlin version, so a module can apply it without re-declaring a
    // version. Used by `komust-scope` (scope.json) and later `komust-engine`
    // (agent JSON output contract).
    implementation(libs.kotlin.serialization.plugin)
}
