dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    // Reuse the project's single version catalog inside buildSrc so the
    // convention plugin pins Kotlin/JaCoCo from the same source of truth.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
