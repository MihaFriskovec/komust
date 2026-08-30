plugins {
    `kotlin-dsl`
}

dependencies {
    // Makes `kotlin("jvm")` available to the precompiled convention plugin,
    // pinned to the exact catalog version.
    implementation(libs.kotlin.gradle.plugin)
}
