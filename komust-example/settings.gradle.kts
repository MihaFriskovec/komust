rootProject.name = "komust-example"

pluginManagement {
    // Consume the `io.komust` plugin straight from source via a composite build
    // (decision: issue #55). `includeBuild("../")` points at the komust root;
    // Gradle substitutes the plugin's marker artifact — and the engine /
    // compiler-plugin artifacts it pulls in — from that build. No publish step,
    // and nothing to keep in sync: the example always tracks current source.
    includeBuild("../")
    repositories {
        // The Kotlin Gradle plugin (applied with a version below) resolves here;
        // `io.komust` comes from the included build, so it needs no repository.
        gradlePluginPortal()
        mavenCentral()
    }
}

// Also include the root at the top level so the composite substitutes the plugin's
// *library* coordinates from source too — `io.komust:komust-engine` (forked by the
// mutationTest task) and `io.komust:komust-compiler-plugin` (the runtime guard, and
// the home of the @SuppressMutations annotation the example imports). Same build as
// the pluginManagement include above; Gradle dedupes it.
includeBuild("../")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
