import org.gradle.testing.jacoco.tasks.JacocoReport

/**
 * Shared configuration for every komust JVM module: the pinned Kotlin
 * toolchain, JUnit Platform test execution, and JaCoCo wired to the
 * catalog-pinned tool version.
 *
 * Library dependencies stay in each module's own build script (where the
 * `libs` catalog accessors are available); this plugin only carries the
 * cross-module policy.
 */

plugins {
    kotlin("jvm")
    jacoco
}

private val libs = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(21)
}

jacoco {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<JacocoReport>().configureEach {
    dependsOn(tasks.withType<Test>())
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
